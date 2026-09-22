@file:Suppress(
    "detekt:LongMethod",
    "detekt:LongParameterList",
    "detekt:TooGenericExceptionCaught",
)

package com.questtick.sign

/*
 * 云游戏签到执行器：单账号内云原神 / 云崩铁的任务循环，以及 Token 失效后的刷新重试。
 */

import com.questtick.data.Account
import com.questtick.data.FailureCategory
import com.questtick.data.TaskResult
import com.questtick.net.HttpTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

private const val MIN_TASK_DELAY_SECONDS = 2
private const val MAX_TASK_DELAY_SECONDS = 5
private const val MILLIS_PER_SECOND = 1_000L

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
        val context = CloudRunContext(CloudSignIn(cloudDeviceId, httpTransport), cloudDeviceId)
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
                val refreshed = refreshCloudToken(context, currentAccount, binding, game, webCookie)
                if (refreshed != null) {
                    token = refreshed.token
                    tokenRefreshed = true
                    currentAccount = refreshed.account
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
                logOutcome(
                    TaskOutcomeLog(
                        account = currentAccount.label,
                        game = game.name,
                        success = false,
                        skipped = false,
                        message = message,
                        elapsedMs = taskElapsed,
                    ),
                )
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
                    safeCloudRun(context.cloud, token, game)
                }

            if (credentialCoordinator.shouldRefreshCloudToken(currentAccount, binding, outcome, tokenRefreshed)) {
                val retry = retryCloudRunAfterRefresh(context, currentAccount, binding, game, webCookie)
                if (retry != null) {
                    token = retry.token
                    tokenRefreshed = true
                    currentAccount = retry.account
                    outcome = retry.outcome
                }
            }

            val taskElapsed = System.currentTimeMillis() - taskStart
            val result = buildCloudTaskResult(game.name, game.key, currentAccount, outcome)
            logOutcome(
                TaskOutcomeLog(
                    account = currentAccount.label,
                    game = game.name,
                    success = outcome.success,
                    skipped = outcome.skipped,
                    message = outcome.message,
                    detail = outcome.detail,
                    elapsedMs = taskElapsed,
                ),
            )
            if (!outcome.success && !outcome.skipped) {
                recordError?.invoke("云游戏签到", "${game.name}: ${outcome.detail.ifBlank { outcome.message }}")
            }
            onTaskCompleted(taskId, result, "${currentAccount.label} · ${game.name}")

            if (idx < bindings.lastIndex) {
                randomSleep(MIN_TASK_DELAY_SECONDS, MAX_TASK_DELAY_SECONDS)
            }
        }

        return currentAccount
    }

    private suspend fun refreshCloudToken(
        context: CloudRunContext,
        account: Account,
        binding: CloudGameBinding,
        game: CloudSignIn.GameConfig,
        webCookie: String,
    ): CloudTokenRefresh? {
        val token =
            credentialCoordinator.refreshCloudTokenForRun(
                account,
                game,
                game.key,
                webCookie,
                context.cloudDeviceId,
            ) ?: return null
        val updatedAccount =
            credentialCoordinator.saveCloudTokenForRun(account, game, binding, token) ?: account
        return CloudTokenRefresh(updatedAccount, token)
    }

    private suspend fun retryCloudRunAfterRefresh(
        context: CloudRunContext,
        account: Account,
        binding: CloudGameBinding,
        game: CloudSignIn.GameConfig,
        webCookie: String,
    ): CloudRetryResult? {
        val refreshed = refreshCloudToken(context, account, binding, game, webCookie) ?: return null
        val outcome =
            limitedRequest("${refreshed.account.label} · ${game.name} 云游戏签到重试") {
                safeCloudRun(context.cloud, refreshed.token, game)
            }
        return CloudRetryResult(refreshed.account, refreshed.token, outcome)
    }

    private suspend fun safeCloudRun(
        cloud: CloudSignIn,
        token: String,
        game: CloudSignIn.GameConfig,
    ): CloudSignIn.Outcome =
        try {
            cloud.runForToken(token, game)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
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
        delay(Random.nextInt(min, max + 1) * MILLIS_PER_SECOND)
    }

    private fun logOutcome(entry: TaskOutcomeLog) {
        val elapsed = if (entry.elapsedMs > 0) " (${formatSignInElapsed(entry.elapsedMs)})" else ""
        val level =
            when {
                entry.skipped -> "WARN"
                entry.success -> "OK"
                else -> "ERROR"
            }
        log(level, "[${entry.account} · ${entry.game}] ${entry.message}$elapsed", entry.detail)
    }

    private data class CloudRunContext(
        val cloud: CloudSignIn,
        val cloudDeviceId: String,
    )

    private data class CloudTokenRefresh(
        val account: Account,
        val token: String,
    )

    private data class CloudRetryResult(
        val account: Account,
        val token: String,
        val outcome: CloudSignIn.Outcome,
    )
}
