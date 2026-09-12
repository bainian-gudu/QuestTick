package com.questtick.sign

/*
 * 米游社签到执行器：单账号内的游戏任务循环、Cookie 失效后的刷新重试与结果回传。
 */

import com.questtick.data.Account
import com.questtick.data.AppSettings
import com.questtick.data.FailureCategory
import com.questtick.data.TaskResult
import com.questtick.core.throwIfCancellation
import com.questtick.net.HttpTransport
import com.questtick.net.TrustedUrlPolicy
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * 米游社签到执行器。
 *
 * 负责单个账号下所有米游社游戏的签到循环、Cookie 刷新重试与结果回传。
 */
internal class MysSignExecutor(
    private val credentialCoordinator: SignInCredentialCoordinator,
    private val httpTransport: HttpTransport,
    private val requestLimiter: Semaphore,
    private val log: (level: String, message: String, detail: String) -> Unit,
    private val recordError: ((feature: String, detail: String) -> Unit)? = null,
) {
    suspend fun runAccount(
        acc: Account,
        settings: AppSettings,
        runMysAppVersion: String,
        actIdCache: ConcurrentHashMap<String, String>,
        actIdCacheMutex: Mutex,
        mysDeviceId: String,
        total: Int,
        done: AtomicInteger,
        onProgress: (String, Int, Int) -> Unit,
        shouldExecuteTask: (taskId: String) -> Boolean,
        onTaskStarted: (String, String) -> Boolean,
        onTaskCompleted: (taskId: String, result: TaskResult, progressLabel: String) -> Unit,
    ): Account {
        val games =
            acc.selectedMysGames().filter { game ->
                shouldExecuteTask(progressTaskId(acc, RunTaskType.MYS, game.key))
            }
        if (games.isEmpty() && !(MysCoinCheckIn.featureEnabled && acc.mysCoinEnabled)) {
            log("INFO", "[${acc.label}] 未选择或未配置可执行的米游社签到任务，跳过米游社签到", "")
            return acc
        }

        if (games.isNotEmpty()) {
            log(
                "INFO",
                "[${acc.label}] 米游社签到: ${games.size} 款游戏",
                "games=${games.joinToString { "${it.key}(act_id=${actIdCache[it.key] ?: it.actId})" }}",
            )
            log("DEBUG", "米游社请求画像：mobile_web，client_type=5，DS=web_md5_v1", "")
        }

        val mys =
            MysSignIn(
                deviceId = mysDeviceId,
                httpTransport = httpTransport,
                actIdAutoRefresh = settings.actIdAutoRefresh,
                actIdCache = actIdCache,
                onLog = { level, message -> log(level, message, "") },
                customVersion = runMysAppVersion,
                cacheMutex = actIdCacheMutex,
                recordError = recordError,
            )

        var currentAccount = acc
        var cookie = acc.mysCookie
        var cookieRefreshed = false

        if (cookie.isBlank()) {
            log(
                "WARN",
                "[${acc.label}] 米游社 Cookie 为空，尝试用 SToken 自动刷新…",
                "mysUid=${Mask.uid(acc.mysUid)}, hasStoken=${acc.stoken.isNotBlank()}",
            )
            val refreshed = credentialCoordinator.refreshMysCookieForRun(acc)
            if (refreshed != null) {
                cookie = refreshed.fullCookie
                cookieRefreshed = true
                credentialCoordinator.saveRefreshedMysCookieForRun(acc, cookie, refreshed.ltoken)?.let { updated ->
                    currentAccount = updated
                }
                log("OK", "[${acc.label}] Cookie 自动刷新成功，继续执行米游社签到", "newCookieLength=${cookie.length}")
            } else {
                log("ERROR", "[${acc.label}] Cookie 自动刷新失败，SToken 可能已过期，请重新扫码登录", "")
                if (games.isNotEmpty()) emitCookieRefreshFailedResults(acc, games, onTaskCompleted)
                return currentAccount
            }
        }

        validateCookie(currentAccount.copy(mysCookie = cookie))

        for ((index, game) in games.withIndex()) {
            val taskStart = System.currentTimeMillis()
            val taskId = progressTaskId(acc, RunTaskType.MYS, game.key)
            onProgress("${acc.label} · ${game.name}", done.get(), total)
            log(
                "INFO",
                "[${acc.label} · ${game.name}] 开始签到…",
                "gameKey=${game.key}, gameBiz=${game.gameBiz}, act_id=${actIdCache[game.key] ?: game.actId}",
            )

            var outcome =
                limitedRequest("${acc.label} · ${game.name} 米游社签到") {
                    check(onTaskStarted(taskId, "${acc.label} · ${game.name}")) {
                        "无法获取任务执行权: $taskId"
                    }
                    safeMysRun(mys, cookie, game)
                }

            if (credentialCoordinator.shouldRefreshMysCookie(acc, outcome, cookieRefreshed)) {
                log(
                    "WARN",
                    "[${acc.label}] Cookie 失效，尝试用 SToken 自动刷新…",
                    "mysUid=${Mask.uid(acc.mysUid)}, hasStoken=true",
                )
                val refreshed = credentialCoordinator.refreshMysCookieForRun(acc)
                if (refreshed != null) {
                    cookie = refreshed.fullCookie
                    cookieRefreshed = true
                    credentialCoordinator.saveRefreshedMysCookieForRun(acc, cookie, refreshed.ltoken)?.let { updated ->
                        currentAccount = updated
                    }
                    log("OK", "[${acc.label}] Cookie 自动刷新成功，使用新 Cookie 重试", "newCookieLength=${cookie.length}")
                    outcome =
                        limitedRequest("${acc.label} · ${game.name} 米游社签到重试") {
                            safeMysRun(mys, cookie, game)
                        }
                } else {
                    log("ERROR", "[${acc.label}] Cookie 自动刷新失败，SToken 可能已过期，请重新扫码登录", "")
                }
            }

            val taskElapsed = System.currentTimeMillis() - taskStart
            val result = buildMysTaskResult(game.name, game.key, acc, outcome)

            val reward = outcome.reward
            if (reward != null && reward.icon.isBlank()) {
                recordError?.invoke(
                    "米游社奖励图片",
                    "${game.name} 奖励图片地址为空：name=${reward.name}, count=${reward.cnt}, day=${reward.day}",
                )
            } else if (reward != null && !TrustedUrlPolicy.isRewardIconUrl(reward.icon)) {
                recordError?.invoke("米游社奖励图片", "${game.name} 奖励图片地址不受信任：${reward.icon}")
            }
            if (!outcome.success && outcome.detail.isNotBlank()) {
                recordError?.invoke("米游社签到", "${game.name}: ${outcome.detail}")
            }
            val rewardInfo =
                if (reward != null) {
                    " | 奖励: ${reward.name}×${reward.cnt}（第${reward.day}天）"
                } else {
                    ""
                }
            logOutcome(
                account = acc.label,
                game = game.name,
                success = outcome.success,
                skipped = outcome.skipped,
                message = outcome.message + rewardInfo,
                detail = buildMysOutcomeDetail(outcome),
                elapsedMs = taskElapsed,
            )

            onTaskCompleted(taskId, result, "${acc.label} · ${game.name}")
            if (index < games.size - 1) randomSleep(2, 5)
        }

        if (MysCoinCheckIn.featureEnabled && acc.mysCoinEnabled) {
            val startedAt = System.currentTimeMillis()
            val taskId = progressTaskId(acc, RunTaskType.MYS, MysCoinCheckIn.GAME_KEY)
            onProgress("${acc.label} · ${MysCoinCheckIn.DISPLAY_NAME}", done.get(), total)
            log("DEBUG", "米游币请求画像：android_app，client_type=2，DS=x6_md5_v2", "")
            val outcome =
                try {
                    limitedRequest("${acc.label} · 原神社区米游币打卡") {
                        check(onTaskStarted(taskId, "${acc.label} · ${MysCoinCheckIn.DISPLAY_NAME}")) {
                            "无法获取任务执行权: $taskId"
                        }
                        MysCoinCheckIn.run(
                            cookie = cookie,
                            deviceId = mysDeviceId,
                            appVersion = runMysAppVersion,
                            httpTransport = httpTransport,
                            stoken = currentAccount.stoken,
                            mid = currentAccount.stmid,
                            uid = currentAccount.mysUid,
                        )
                    }
                } catch (e: Exception) {
                    e.throwIfCancellation()
                    MysCoinCheckIn.Outcome(
                        success = false,
                        alreadyDone = false,
                        message = "米游币打卡${ErrorText.fromException(e)}",
                        detail = ErrorText.detailOf(e),
                    )
                }
            logOutcome(
                account = acc.label,
                game = "米游币打卡",
                success = outcome.success,
                skipped = false,
                message = outcome.message,
                detail = outcome.detail,
                elapsedMs = System.currentTimeMillis() - startedAt,
            )
            if (outcome.warning.isNotBlank()) {
                log("WARN", "[${acc.label} · ${MysCoinCheckIn.DISPLAY_NAME}] ${outcome.warning}", "")
            }
            if (!outcome.success) {
                recordError?.invoke("米游币签到", outcome.detail.ifBlank { outcome.message })
            }
            onTaskCompleted(
                taskId,
                buildMysCoinTaskResult(acc, outcome),
                "${acc.label} · ${MysCoinCheckIn.DISPLAY_NAME}",
            )
        }

        return currentAccount
    }

    private fun emitCookieRefreshFailedResults(
        account: Account,
        games: List<MysSignIn.GameConfig>,
        onTaskCompleted: (taskId: String, result: TaskResult, progressLabel: String) -> Unit,
    ) {
        val outcome =
            MysSignIn.Outcome(
                success = false,
                skipped = false,
                message = "Cookie 自动刷新失败，请重新扫码登录",
                detail = "keepLoginRefreshFailed=true",
                failure = TaskFailureDescriptor(FailureCategory.AUTH_EXPIRED),
            )
        games.forEach { game ->
            val result = buildMysTaskResult(game.name, game.key, account, outcome)
            logOutcome(
                account = account.label,
                game = game.name,
                success = false,
                skipped = false,
                message = outcome.message,
                detail = outcome.detail,
            )
            onTaskCompleted(
                progressTaskId(account, RunTaskType.MYS, game.key),
                result,
                "${account.label} · ${game.name}",
            )
        }
    }

    private fun validateCookie(account: Account) {
        val cookie = account.mysCookie
        val required = listOf("cookie_token", "account_id")
        val lower = cookie.lowercase()
        val missing = required.filter { !lower.contains(it) }
        val hasLtoken = lower.contains("ltoken")
        val hasStoken = lower.contains("stoken")

        when {
            missing.isNotEmpty() -> {
                log(
                    "WARN",
                    "[${account.label}] Cookie 缺少必要字段: ${missing.joinToString(", ")}",
                    "cookieLength=${cookie.length}, hasLtoken=$hasLtoken, hasStoken=$hasStoken, fieldsFound=${required.filter {
                        lower.contains(
                            it,
                        )
                    }.joinToString()}",
                )
            }

            cookie.length < 50 -> {
                log(
                    "WARN",
                    "[${account.label}] Cookie 过短（${cookie.length} 字符），可能不完整",
                    "hasLtoken=$hasLtoken, hasStoken=$hasStoken",
                )
            }

            else -> {
                log(
                    "INFO",
                    "[${account.label}] Cookie 校验通过",
                    "cookieLength=${cookie.length}, hasLtoken=$hasLtoken, hasStoken=$hasStoken",
                )
            }
        }
    }

    private suspend fun safeMysRun(
        mys: MysSignIn,
        cookie: String,
        game: MysSignIn.GameConfig,
    ): MysSignIn.Outcome =
        try {
            mys.runForCookie(cookie, game)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            MysSignIn.Outcome(
                success = false,
                skipped = false,
                message = ErrorText.fromException(e),
                detail = ErrorText.detailOf(e),
                failure = TaskFailureClassifier.fromException(e),
            )
        }

    private suspend fun <T> limitedRequest(
        name: String,
        minDelayMs: Long = 400L,
        maxDelayMs: Long = 1200L,
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
