package com.questtick.repository.auth

import com.questtick.data.Account
import com.questtick.sign.CloudQRLogin
import com.questtick.sign.CookieParser
import com.questtick.sign.CookieRefresher
import com.questtick.net.HttpTransport
import javax.inject.Inject
import javax.inject.Singleton

/** 认证仓库：统一管理 Cookie 健康检查、Cookie 刷新与云游戏 Token 续期。 */
@Singleton
class AuthRepository
    @Inject
    constructor(
        private val httpTransport: HttpTransport,
    ) {
        enum class CredentialState {
            VALID,
            EXPIRING,
            REFRESHING,
            EXPIRED,
        }

        data class CredentialHealth(
            val state: CredentialState,
            val reason: String = "",
        )

        fun checkCookieHealth(account: Account): CredentialHealth {
            val cookie = account.mysCookie.trim()
            if (cookie.isBlank()) return CredentialHealth(CredentialState.EXPIRED, "未填写 Cookie")
            val lower = cookie.lowercase()
            val missing = mutableListOf<String>()
            if (!lower.contains("cookie_token")) missing.add("cookie_token")
            if (!lower.contains("account_id")) missing.add("account_id")
            if (cookie.length < 50) missing.add("长度过短(${cookie.length}字符)")
            if (missing.isNotEmpty()) {
                return CredentialHealth(CredentialState.EXPIRING, "Cookie 可能不完整：缺少${missing.joinToString("、")}")
            }
            return CredentialHealth(CredentialState.VALID)
        }

        fun parseLoginCookies(
            setCookieHeaders: List<String>,
            userUid: String = "",
            userMid: String = "",
        ): CookieParser.LoginCookies = CookieParser.parseLoginCookies(setCookieHeaders, userUid, userMid)

        fun buildCookie(
            uid: String,
            cookieToken: String,
            ltoken: String,
            mid: String,
        ): String = CookieRefresher.buildCookie(uid, cookieToken, ltoken, mid)

        suspend fun refreshCookie(
            account: Account,
            onDiagnostic: (message: String, detail: String) -> Unit = { _, _ -> },
        ): CookieRefresher.RefreshedCookie? {
            if (!account.hasMysKeepLogin) return null
            return CookieRefresher.refresh(account.mysUid, account.stoken, account.stmid, httpTransport, onDiagnostic)
        }

        fun isCookieExpired(message: String): Boolean = CookieRefresher.isCookieExpired(message)

        suspend fun refreshCloudToken(
            gameKey: String,
            webCookie: String,
            deviceId: String,
        ): Result<String> {
            if (webCookie.isBlank()) return Result.failure(IllegalStateException("未保持登录"))
            return CloudQRLogin.exchangeComboToken(gameKey, webCookie, deviceId, httpTransport)
        }

        /** 判断云游戏接口结果是否明确指向 Token / 登录态失效。 */
        fun isCloudTokenExpired(
            message: String,
            detail: String = "",
        ): Boolean {
            val text = detail.ifBlank { message }.lowercase()
            if (text.isBlank()) return false
            val expiredKeywords =
                listOf(
                    "登录状态已失效",
                    "尚未登录",
                    "请先登录",
                    "请重新登录",
                    "未登录",
                    "unauthorized",
                    "authorization",
                    "auth",
                    "login",
                    "combo_token",
                    "invalid token",
                    "token invalid",
                    "token expired",
                )
            val expiredCodes = listOf("-100", "-101", "10001", "10103")
            return expiredKeywords.any { text.contains(it.lowercase()) } ||
                expiredCodes.any { text.contains(it) }
        }
    }
