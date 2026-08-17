package com.questtick.sign

/**
 * 敏感信息脱敏工具。
 *
 * 用于日志与错误文本，统一覆盖 CookieRefresher 拼接的 key=value、SignInRunner 自定义字段、
 * HTTP Header、云游戏 token 以及 URL 查询参数中的凭证，避免 Cookie / Token 外泄。
 */
object Mask {
    private val rules: List<Pair<Regex, String>> =
        listOf(
            // Cookie 字段：来自 CookieRefresher.buildCookie 与米游社接口。
            Regex("(cookie_token=)[^;,\\s]+", RegexOption.IGNORE_CASE) to "$1***",
            Regex("(cookie_token_v2=)[^;,\\s]+", RegexOption.IGNORE_CASE) to "$1***",
            Regex("(ltoken=)[^;,\\s]+", RegexOption.IGNORE_CASE) to "$1***",
            Regex("(ltoken_v2=)[^;,\\s]+", RegexOption.IGNORE_CASE) to "$1***",
            Regex("(ltuid=)\\d+", RegexOption.IGNORE_CASE) to "$1***",
            Regex("(ltuid_v2=)\\d+", RegexOption.IGNORE_CASE) to "$1***",
            Regex("(stoken=)[^;,\\s&]+", RegexOption.IGNORE_CASE) to "$1***",
            Regex("(stoken_v2=)[^;,\\s]+", RegexOption.IGNORE_CASE) to "$1***",
            // stuid / stmid 来自 CookieRefresher.refresh，需要单独脱敏。
            Regex("(stuid=)\\d+", RegexOption.IGNORE_CASE) to "$1***",
            Regex("(stmid=)[^;,\\s]+", RegexOption.IGNORE_CASE) to "$1***",
            Regex("(account_id=)\\d+", RegexOption.IGNORE_CASE) to "$1***",
            Regex("(account_id_v2=)\\d+", RegexOption.IGNORE_CASE) to "$1***",
            Regex("(account_mid_v2=)[^;,\\s]+", RegexOption.IGNORE_CASE) to "$1***",
            Regex("(ltmid_v2=)[^;,\\s]+", RegexOption.IGNORE_CASE) to "$1***",
            Regex("(login_uid=)\\d+", RegexOption.IGNORE_CASE) to "$1***",
            Regex("(oi=)\\d+", RegexOption.IGNORE_CASE) to "$1***",
            Regex("(login_ticket=)[^;,\\s]+", RegexOption.IGNORE_CASE) to "$1***",
            Regex("(mid=)[^;,\\s]+", RegexOption.IGNORE_CASE) to "$1***",
            // SignInRunner 日志会输出 mysUid=<uid>，需要单独脱敏。
            Regex("(mysUid=)[^;,\\s]+", RegexOption.IGNORE_CASE) to "$1***",
            // HTTP Header 字段。
            Regex("(Cookie:\\s*)[^\\n\\r]+", RegexOption.IGNORE_CASE) to "$1***",
            Regex("(Authorization:\\s*Bearer\\s+)[A-Za-z0-9._-]+", RegexOption.IGNORE_CASE) to "$1***",
            // 云游戏 Token 字段。
            Regex("(x-rpc-combo_token:\\s*)[^\\n\\r]+", RegexOption.IGNORE_CASE) to "$1***",
            Regex("(combo_token=)[^;&\\s]+", RegexOption.IGNORE_CASE) to "$1***",
            Regex("(access_token[\"']?\\s*[:=]\\s*[\"']?)[^\"',\\s}]+", RegexOption.IGNORE_CASE) to "$1***",
            Regex("(refresh_token[\"']?\\s*[:=]\\s*[\"']?)[^\"',\\s}]+", RegexOption.IGNORE_CASE) to "$1***",
            // 兜底 token 规则只匹配独立的 token=…
            Regex("(?<![A-Za-z0-9_])(token[\"']?\\s*[:=]\\s*[\"']?)[^\"',\\s}]+", RegexOption.IGNORE_CASE) to "$1***",
            // URL 查询参数中的 stoken / ltoken / uid 也需要脱敏。
            Regex("([?&]stoken(?:_v2)?=)[^&\\s]+", RegexOption.IGNORE_CASE) to "$1***",
            Regex("([?&]ltoken(?:_v2)?=)[^&\\s]+", RegexOption.IGNORE_CASE) to "$1***",
            Regex("([?&]uid=)\\d+", RegexOption.IGNORE_CASE) to "$1***",
        )

    fun sensitive(text: String?): String {
        var result = text ?: ""
        for ((regex, replacement) in rules) {
            result = regex.replace(result, replacement)
        }
        return result
    }

    /**
     * UID 脱敏：保留足够的首尾信息以便用户辨认账号，同时隐藏中间主体。
     *
     * 示例：`123456789` → `12****89`；短 ID 会保留原字符并追加星号，避免丢失长度信息。
     */
    fun uid(uid: String?): String {
        val value = uid?.trim().orEmpty()
        if (value.isEmpty()) return ""
        val n = value.length
        // 单字符 ID 直接显示并追加星号。
        if (n <= 1) return value
        // 2 到 3 字符：保留原文并追加星号。
        if (n <= 3) return "$value****"
        // 4 字符：保留原文并追加星号。
        if (n <= 4) return "$value****"
        // 5 字符及以上：保留前 2 与后 2，中间替换为星号。
        return "${value.take(2)}****${value.takeLast(2)}"
    }
}
