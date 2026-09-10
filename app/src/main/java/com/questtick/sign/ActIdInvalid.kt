package com.questtick.sign

/**
 * 米游社 act_id 疑似失效判断工具。
 *
 * 规则保持保守，避免把登录过期、风控验证码、角色不存在等问题误判为 act_id 失效。
 */
object ActIdInvalid {
    /** 可视为 act_id 疑似失效的 retcode。 */
    private val INVALID_RETCODES = setOf(-500001, 1001, 1002)

    /**
     * 明确不属于 act_id 失效的 retcode。
     * 例如 -100 通常表示登录态 / Cookie 问题，不应触发 act_id 刷新。
     */
    private val NOT_ACT_ID_RETCODES =
        setOf(
            0,
            -1,
            -100,
            -10001,
            -10002,
            -10003,
            -10004,
            -10005,
            -10006,
            -10007,
            -10008,
            1034,
        )

    /** 与 act_id 无关的错误特征，例如登录态、风控、角色或 DS 问题。 */
    private val CLEARLY_NOT_PATTERNS =
        listOf(
            Regex("OK", RegexOption.IGNORE_CASE),
            Regex("已签到|已经签到|签到过|今日已签到|already", RegexOption.IGNORE_CASE),
            Regex("登录|login|cookie|token|auth|鉴权|权限|未登录|登录失效|请重新登录", RegexOption.IGNORE_CASE),
            Regex("频繁|too many|rate|风控|captcha|验证|risk", RegexOption.IGNORE_CASE),
            Regex("角色|uid|region|game_uid|game uid|区服", RegexOption.IGNORE_CASE),
            Regex("DS|签名|signature|headers?|请求头", RegexOption.IGNORE_CASE),
        )

    /** 疑似 act_id 失效的消息特征；要求与“活动 / act”相关，避免过泛。 */
    private val INVALID_MESSAGE_PATTERNS =
        listOf(
            Regex("act_id", RegexOption.IGNORE_CASE),
            Regex("act id", RegexOption.IGNORE_CASE),
            Regex("活动.*不存在"),
            Regex("活动.*已结束"),
            Regex("活动.*过期"),
            Regex("活动.*关闭"),
            Regex("活动.*下线"),
            Regex("活动.*未开启"),
            Regex("活动.*无效"),
            Regex("activity.*not found", RegexOption.IGNORE_CASE),
            Regex("activity.*expired", RegexOption.IGNORE_CASE),
            Regex("activity.*closed", RegexOption.IGNORE_CASE),
            Regex("activity.*invalid", RegexOption.IGNORE_CASE),
            Regex("invalid.*act", RegexOption.IGNORE_CASE),
            Regex("invalid.*activity", RegexOption.IGNORE_CASE),
        )

    private fun isInvalidRetcode(retcode: Int?): Boolean {
        if (retcode == null) return false
        if (retcode in NOT_ACT_ID_RETCODES) return false
        return retcode in INVALID_RETCODES
    }

    private fun isClearlyNotActIdMessage(message: String): Boolean = message.isNotBlank() && CLEARLY_NOT_PATTERNS.any { it.containsMatchIn(message) }

    private fun isInvalidMessage(message: String): Boolean {
        val text = message.trim()
        if (text.isEmpty()) return false
        if (isClearlyNotActIdMessage(text)) return false
        return INVALID_MESSAGE_PATTERNS.any { it.containsMatchIn(text) }
    }

    /** 判断接口响应是否疑似 act_id 失效。 */
    fun isInvalid(
        retcode: Int?,
        message: String?,
    ): Boolean = isInvalidRetcode(retcode) || isInvalidMessage(message.orEmpty())
}
