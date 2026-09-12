package com.questtick.sign

// 协调签到前的 Cookie、Token 和保持登录凭证刷新流程。

import com.questtick.core.throwIfCancellation
import com.questtick.data.Account
import com.questtick.data.FailureCategory
import com.questtick.repository.account.AccountRepository
import com.questtick.repository.auth.AuthRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.random.Random

internal fun shouldRefreshMysCookieDecision(
    hasKeepLogin: Boolean,
    outcome: MysSignIn.Outcome,
    cookieRefreshed: Boolean,
    expired: Boolean,
): Boolean {
    val authExpired =
        outcome.failure.category == FailureCategory.AUTH_EXPIRED ||
            (outcome.failure.category == FailureCategory.NONE && expired)
    return !outcome.success && !outcome.skipped && !cookieRefreshed && hasKeepLogin && authExpired
}

internal fun shouldRefreshCloudTokenDecision(
    hasKeepLogin: Boolean,
    outcome: CloudSignIn.Outcome,
    tokenRefreshed: Boolean,
    expired: Boolean,
): Boolean {
    val authExpired =
        outcome.failure.category == FailureCategory.AUTH_EXPIRED ||
            (outcome.failure.category == FailureCategory.NONE && expired)
    return !outcome.success && !outcome.skipped && !tokenRefreshed && hasKeepLogin && authExpired
}

internal class SignInCredentialCoordinator(
    private val authRepository: AuthRepository,
    private val accountRepository: AccountRepository,
    private val refreshLimiter: Semaphore,
    private val log: (level: String, message: String, detail: String) -> Unit,
) {
    fun shouldRefreshMysCookie(
        account: Account,
        outcome: MysSignIn.Outcome,
        cookieRefreshed: Boolean,
    ): Boolean =
        shouldRefreshMysCookieDecision(
            hasKeepLogin = account.hasMysKeepLogin,
            outcome = outcome,
            cookieRefreshed = cookieRefreshed,
            expired = authRepository.isCookieExpired(outcome.message),
        )

    suspend fun refreshMysCookieForRun(acc: Account): CookieRefresher.RefreshedCookie? =
        limitedRefreshRequest("${acc.label} Cookie 自动刷新") {
            authRepository.refreshCookie(acc) { message, detail ->
                log("WARN", "[${acc.label}] $message", detail)
            }
        }

    suspend fun saveRefreshedMysCookieForRun(
        acc: Account,
        newCookie: String,
        ltoken: String,
    ): Account? =
        try {
            val updated =
                accountRepository.update(acc.id) { latest ->
                    latest.copy(mysCookie = CredentialFieldSanitizer.mysSigninCookie(newCookie), ltoken = ltoken)
                }
            if (updated == null) {
                log("WARN", "[${acc.label}] 新 Cookie 已获取，但账号已不存在，跳过保存", "")
            }
            updated
        } catch (e: Exception) {
            e.throwIfCancellation()
            log(
                "WARN",
                "[${acc.label}] 新 Cookie 已获取，但保存到本地失败",
                ErrorText.detailOf(e),
            )
            null
        }

    fun shouldRefreshCloudToken(
        account: Account,
        binding: CloudGameBinding,
        outcome: CloudSignIn.Outcome,
        tokenRefreshed: Boolean,
    ): Boolean =
        shouldRefreshCloudTokenDecision(
            hasKeepLogin = binding.hasKeepLogin(account),
            outcome = outcome,
            tokenRefreshed = tokenRefreshed,
            expired = authRepository.isCloudTokenExpired(outcome.message, outcome.detail),
        )

    suspend fun refreshCloudTokenForRun(
        acc: Account,
        game: CloudSignIn.GameConfig,
        gameKey: String,
        webCookie: String,
        cloudDeviceId: String,
    ): String? {
        if (webCookie.isBlank()) return null
        val refreshed =
            limitedRefreshRequest("${acc.label} · ${game.name} 云游戏 Token 续期") {
                authRepository.refreshCloudToken(gameKey, webCookie, cloudDeviceId)
            }
        val newToken = refreshed.getOrNull()
        if (newToken == null) {
            val e = refreshed.exceptionOrNull()
            log(
                "WARN",
                "[${acc.label} · ${game.name}] Token 续期失败",
                ErrorText.detailOf(e),
            )
        }
        return newToken
    }

    suspend fun saveCloudTokenForRun(
        acc: Account,
        game: CloudSignIn.GameConfig,
        binding: CloudGameBinding,
        newToken: String,
    ): Account? =
        try {
            val updated =
                accountRepository.update(acc.id) { latest ->
                    binding.updateToken(latest, CredentialFieldSanitizer.cloudSigninToken(newToken))
                }
            if (updated == null) {
                log("WARN", "[${acc.label} · ${game.name}] 新 Token 已获取，但账号已不存在，跳过保存", "")
            }
            updated
        } catch (e: Exception) {
            e.throwIfCancellation()
            log(
                "WARN",
                "[${acc.label} · ${game.name}] 新 Token 已获取，但保存到本地失败",
                ErrorText.detailOf(e),
            )
            null
        }

    private suspend fun <T> limitedRefreshRequest(
        name: String,
        minDelayMs: Long = 800L,
        maxDelayMs: Long = 1800L,
        block: suspend () -> T,
    ): T =
        refreshLimiter.withPermit {
            val jitter = Random.nextLong(minDelayMs, maxDelayMs + 1)
            log("INFO", "请求限流：$name 等待 ${jitter}ms", "")
            delay(jitter)
            block()
        }
}
