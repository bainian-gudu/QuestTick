package com.questtick.sign

/*
 * 云游戏签到执行器：单账号内云原神 / 云崩铁的任务循环，以及 Token 失效后的刷新重试。
 */

import com.questtick.data.Account
import com.questtick.data.FailureCategory
import com.questtick.data.TaskResult
import com.questtick.net.HttpTransport
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * 云游戏签到执行器。
 *
 * 负责单个账号下云原神 / 云崩铁的时长领取流程、Token 续期与结果回传。
 */
internal class CloudSignExecutor(
    private val credentialCoordinator: SignInCredentialCoordinator,
    private val httpTransport: HttpTransport,
    private val requestLimiter: Semaphore,
    private val log: (level: String, message: String, detail: String) -> Unit,
    private val recordError: ((feature: String, detail: String) -> Unit)? = null,
) {
    suspend fun runAccount(
        acc: Account,
        cloudGameBindings: List<CloudGameBinding>,
        cloudDeviceId: String,
        total: Int,
        done: AtomicInteger,
        onProgress: (String, Int, Int) -> Unit,
        shouldExecuteTask: (taskId: String) -> Boolean,
        onTaskStarted: (String, String) -> Boolean,
        onTaskCompleted: (taskId: String, result: TaskResult, progressLabel: String) -> Unit,
    ): Account {
        var currentAccount = acc
        val cloud = CloudSignIn(cloudDeviceId, httpTransport)
        val bindings =
            acc.selectedCloudBindings(cloudGameBindings).filter { binding ->
                shouldExecuteTask(progressTaskId(acc, RunTaskType.CLOUD, binding.game.key))
            }

        for ((idx, binding) in bindings.withIndex()) {
            val game = binding.game
            val taskStart = System.currentTimeMillis()
            val taskId = progressTaskId(currentAccount, RunTaskType.CLOUD, game.key)
            onProgress("${currentAccount.label} · ${game.name}", done.get(), total)

            var token = binding.tokenOf(currentAccount)
            var tokenRefreshed = false
            val webCookie = binding.webCookieOf(currentAccount)

            if (token.isBlank() && binding.hasKeepLogin(currentAccount)) {
                credentialCoordinator.refreshCloudTokenForRun(currentAccount, game, game.key, webCookie, cloudDeviceId)?.let { newToken ->
                    token = newToken
                    tokenRefreshed = true
                    credentialCoordinator.saveCloudTokenForRun(currentAccount, game, binding, newToken)?.let { updated ->
                        currentAccount = updated
                    }
                }
            }

            if (token.isBlank()) {
                val taskElapsed = System.currentTimeMillis() - taskStart
                val message = "未能获取有效的${game.name} Token，请重新扫码登录"
                val outcome =
                    CloudSignIn.Outcome(
                        success = false,
                        skipped = false,
                        message = message,
                        failure = TaskFailureDescriptor(FailureCategory.AUTH_EXPIRED),
                    )
                val result = buildCloudTaskResult(game.name, game.key, currentAccount, outcome)
                logOutcome(currentAccount.label, game.name, false, false, message, elapsedMs = taskElapsed)
                recordError?.invoke("云游戏签到", "${game.name}: $message")
                onTaskCompleted(taskId, result, "${currentAccount.label} · ${game.name}")
                continue
            }

            log("INFO", "[${currentAccount.label} · ${game.name}] 开始领取云游戏时长…", "tokenLength=${token.length}")
            log("DEBUG", "云游戏请求画像：cloud_web，client_type=17，认证=combo_token", "")
            var outcome =
                limitedRequest("${currentAccount.label} · ${game.name} 云游戏签到") {
                    check(onTaskStarted(taskId, "${currentAccount.label} · ${game.name}")) {
                        "无法获取任务执行权: $taskId"
                    }
                    safeCloudRun(cloud, token, game)
                }

            if (credentialCoordinator.shouldRefreshCloudToken(currentAccount, binding, outcome, tokenRefreshed)) {
                credentialCoordinator.refreshCloudTokenForRun(currentAccount, game, game.key, webCookie, cloudDeviceId)?.let { newToken ->
                    token = newToken
                    tokenRefreshed = true
                    credentialCoordinator.saveCloudTokenForRun(currentAccount, game, binding, newToken)?.let { updated ->
                        currentAccount = updated
                    }
                    outcome =
                        limitedRequest("${currentAccount.label} · ${game.name} 云游戏签到重试") {
                            safeCloudRun(cloud, token, game)
                        }
                }
            }

            val taskElapsed = System.currentTimeMillis() - taskStart
            val result = buildCloudTaskResult(game.name, game.key, currentAccount, outcome)
            logOutcome(currentAccount.label, game.name, outcome.success, outcome.skipped, outcome.message, outcome.detail, taskElapsed)
            if (!outcome.success && !outcome.skipped) {
                recordError?.invoke("云游戏签到", "${game.name}: ${outcome.detail.ifBlank { outcome.message }}")
            }
            onTaskCompleted(taskId, result, "${currentAccount.label} · ${game.name}")

            if (idx < bindings.lastIndex) {
                randomSleep(2, 5)
            }
        }

        return currentAccount
    }

    private suspend fun safeCloudRun(
        cloud: CloudSignIn,
        token: String,
        game: CloudSignIn.GameConfig,
    ): CloudSignIn.Outcome =
        try {
            cloud.runForToken(token, game)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            CloudSignIn.Outcome(
                success = false,
                skipped = false,
                message = ErrorText.fromException(e),
                detail = ErrorText.detailOf(e),
                failure = TaskFailureClassifier.fromException(e),
            )
        }

    private suspend fun <T> limitedRequest(
        name: String,
        minDelayMs: Long = 600L,
        maxDelayMs: Long = 1600L,
        block: suspend () -> T,
    ): T =
        requestLimiter.withPermit {
            val jitter = Random.nextLong(minDelayMs, maxDelayMs + 1)
            log("INFO", "请求限流：$name 等待 ${jitter}ms", "")
            delay(jitter)
            block()
        }

    private suspend fun randomSleep(
        min: Int,
        max: Int,
    ) {
        delay(Random.nextInt(min, max + 1) * 1000L)
    }

    private fun logOutcome(
        account: String,
        game: String,
        success: Boolean,
        skipped: Boolean,
        message: String,
        detail: String = "",
        elapsedMs: Long = 0,
    ) {
        val elapsed = if (elapsedMs > 0) " (${formatSignInElapsed(elapsedMs)})" else ""
        val level =
            when {
                skipped -> "WARN"
                success -> "OK"
                else -> "ERROR"
            }
        log(level, "[$account · $game] $message$elapsed", detail)
    }
}
