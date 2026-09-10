package com.questtick.sign

/** 只保留可直接用于签到/领取时长的凭证字段，避免把续期凭证写进可见填写框。 */
object CredentialFieldSanitizer {
    private val MYS_SIGNIN_COOKIE_KEYS =
        setOf(
            "account_id",
            "account_id_v2",
            "ltuid",
            "ltuid_v2",
            "cookie_token",
            "cookie_token_v2",
            "ltoken",
            "ltoken_v2",
            "account_mid_v2",
            "ltmid_v2",
        )

    private val CLOUD_SIGNIN_TOKEN_KEYS = setOf("ai", "ci", "oi", "ct", "si", "bi")

    fun mysSigninCookie(cookie: String): String =
        CookieParser
            .parseCookieHeader(cookie)
            .filterKeys { it in MYS_SIGNIN_COOKIE_KEYS }
            .entries
            .joinToString("; ") { (key, value) -> "$key=$value" }

    fun cloudSigninToken(token: String): String {
        var hasKeyValuePart = false
        val filtered =
            token
                .split(';')
                .mapNotNull { part ->
                    val pair = part.trim().split('=', limit = 2)
                    if (pair.size != 2) return@mapNotNull null
                    hasKeyValuePart = true
                    if (pair[0] in CLOUD_SIGNIN_TOKEN_KEYS) pair[0] to pair[1] else null
                }.joinToString(";") { (key, value) -> "$key=$value" }
        return if (hasKeyValuePart) filtered else token
    }
}
