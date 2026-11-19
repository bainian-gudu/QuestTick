package com.questtick.sign

/** Cookie 解析工具，统一处理扫码登录和单元测试中的凭证提取。 */
object CookieParser {
    data class LoginCookies(
        val uid: String,
        val cookieToken: String,
        val ltoken: String,
        val stoken: String,
        val mid: String,
        /** 登录票据；部分扫码响应不直接返回 stoken，可用它继续兑换自动刷新凭证。 */
        val loginTicket: String,
    )

    fun parseCookieHeader(header: String): Map<String, String> {
        if (header.isBlank()) return emptyMap()
        return header
            .split(';')
            .mapNotNull { part ->
                val pair = part.trim().split('=', limit = 2)
                if (pair.size == 2 && pair[0].isNotBlank()) pair[0].trim() to pair[1].trim() else null
            }.toMap()
    }

    fun parseSetCookieHeaders(headers: List<String>): Map<String, String> {
        if (headers.isEmpty()) return emptyMap()
        val result = linkedMapOf<String, String>()
        headers.forEach { raw ->
            parseCookieHeader(raw).forEach { (key, value) ->
                if (key.lowercase() !in SET_COOKIE_ATTRIBUTES) result[key] = value
            }
        }
        return result
    }

    fun parseLoginCookies(
        setCookieHeaders: List<String>,
        userUid: String = "",
        userMid: String = "",
    ): LoginCookies {
        val cookies = parseSetCookieHeaders(setCookieHeaders)
        val uid =
            userUid
                .ifBlank { cookies["stuid"].orEmpty() }
                .ifBlank { cookies["account_id"].orEmpty() }
                .ifBlank { cookies["account_id_v2"].orEmpty() }
                .ifBlank { cookies["login_uid"].orEmpty() }
                .ifBlank { cookies["ltuid_v2"].orEmpty() }
        val mid =
            userMid
                .ifBlank { cookies["stmid_v2"].orEmpty() }
                .ifBlank { cookies["stmid"].orEmpty() }
                .ifBlank { cookies["ltmid_v2"].orEmpty() }
                .ifBlank { cookies["account_mid_v2"].orEmpty() }
                .ifBlank { cookies["mid"].orEmpty() }
        val cookieToken =
            cookies["cookie_token_v2"]
                .orEmpty()
                .ifBlank { cookies["cookie_token"].orEmpty() }
        val ltoken =
            cookies["ltoken_v2"]
                .orEmpty()
                .ifBlank { cookies["ltoken"].orEmpty() }
        val stoken =
            cookies["stoken_v2"]
                .orEmpty()
                .ifBlank { cookies["stoken"].orEmpty() }
        val loginTicket =
            cookies["login_ticket"]
                .orEmpty()
                .ifBlank { cookies["login_ticket_v2"].orEmpty() }

        return LoginCookies(
            uid = uid,
            cookieToken = cookieToken,
            ltoken = ltoken,
            stoken = stoken,
            mid = mid,
            loginTicket = loginTicket,
        )
    }

    private val SET_COOKIE_ATTRIBUTES =
        setOf(
            "path",
            "domain",
            "expires",
            "max-age",
            "secure",
            "httponly",
            "samesite",
        )
}
