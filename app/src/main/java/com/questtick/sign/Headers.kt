package com.questtick.sign

/** 米游社与云游戏请求头常量及构造工具。 */
object Endpoints {
    const val WEB_HOST = "api-takumi.mihoyo.com"
    const val ZZZ_HOST = "act-nap-api.mihoyo.com"
    const val BBS_HOST = "bbs-api.miyoushe.com"

    // 米游社请求使用的默认版本；实际请求优先使用本地获取的版本。
    const val APP_VERSION = "2.109.0"

    /** 米游社 App 接口使用的校验密钥。 */
    const val VERIFY_KEY = "bll8iq97cem8"

    const val CHANNEL = "miyousheluodi"

    /** 普通米游社网页接口使用的 mobile-web 请求画像。 */
    const val WEB_USER_AGENT_PREFIX =
        "Mozilla/5.0 (Linux; Android 12; Unspecified Device) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Version/4.0 Chrome/103.0.5060.129 Mobile Safari/537.36 miHoYoBBS/"

    /** 获取当前生效的接口版本号，优先使用传入版本。 */
    fun effectiveAppVersion(customVersion: String): String = customVersion.ifBlank { APP_VERSION }

    /** 根据当前接口版本生成 mobile-web User-Agent。 */
    fun effectiveUserAgent(customVersion: String): String = WEB_USER_AGENT_PREFIX + effectiveAppVersion(customVersion)

    /** 兼容不带版本参数的网页请求。 */
    val USER_AGENT: String
        get() = effectiveUserAgent("")
}

object MysHeaders {
    /**
     * @param customVersion 自定义米游社版本号；为空时使用内置默认值。
     * @param ds 已生成的 DS 字符串；md5_v2 时必须根据请求 body/query 生成。
     */
    private fun common(
        cookie: String,
        customVersion: String = "",
        ds: String,
    ): MutableMap<String, String> =
        linkedMapOf(
            "DS" to ds,
            "Cookie" to cookie,
            "Host" to Endpoints.WEB_HOST,
            "User-Agent" to Endpoints.effectiveUserAgent(customVersion),
            "x-rpc-app_version" to Endpoints.effectiveAppVersion(customVersion),
            "x-rpc-client_type" to "5",
            "Accept-Language" to "zh-CN,zh-Hans;q=0.9",
            "Accept" to "application/json, text/plain, */*",
        )

    /** 查询绑定角色时使用的请求头。 */
    fun role(
        cookie: String,
        deviceId: String,
        customVersion: String = "",
        ds: String,
    ): Map<String, String> =
        common(cookie, customVersion, ds).apply {
            put("Referer", "https://webstatic.mihoyo.com/")
            put("Origin", "https://webstatic.mihoyo.com")
            put("x-rpc-device_id", deviceId)
            put("x-rpc-channel", Endpoints.CHANNEL)
            put("X-Requested-With", "com.mihoyo.hyperion")
        }

    /** 执行签到时使用的请求头。 */
    fun sign(
        cookie: String,
        deviceId: String,
        customVersion: String = "",
        ds: String,
        signgame: String? = null,
        referer: String? = null,
        origin: String? = null,
    ): Map<String, String> =
        common(cookie, customVersion, ds).apply {
            put("Referer", referer ?: "https://act.mihoyo.com/")
            put("Origin", origin ?: "https://act.mihoyo.com")
            put("x-rpc-device_id", deviceId)
            put("x-rpc-channel", Endpoints.CHANNEL)
            put("X-Requested-With", "com.mihoyo.hyperion")
            put("Content-Type", "application/json;charset=utf-8")
            if (signgame != null) put("x-rpc-signgame", signgame)
        }

    /** 获取米游社昵称时使用的请求头。 */
    fun account(cookie: String): Map<String, String> =
        linkedMapOf(
            "Cookie" to cookie,
            "Host" to Endpoints.BBS_HOST,
            "Referer" to "https://www.miyoushe.com/",
            "Origin" to "https://www.miyoushe.com",
            "User-Agent" to Endpoints.USER_AGENT,
            "Accept" to "application/json, text/plain, */*",
        )

    /** 米游币社区打卡接口请求头。 */
    fun coinCheckIn(
        cookie: String,
        deviceId: String,
        customVersion: String,
        ds: String,
    ): Map<String, String> =
        linkedMapOf(
            "DS" to ds,
            "Cookie" to cookie,
            "Host" to Endpoints.BBS_HOST,
            "User-Agent" to "okhttp/4.9.3",
            "Referer" to "https://app.mihoyo.com",
            "Content-Type" to "application/json; charset=UTF-8",
            "x-rpc-client_type" to "2",
            "x-rpc-app_version" to Endpoints.effectiveAppVersion(customVersion),
            "x-rpc-sys_version" to "12",
            "x-rpc-channel" to Endpoints.CHANNEL,
            "x-rpc-device_id" to deviceId,
            "x-rpc-device_name" to "Xiaomi MI 6",
            "x-rpc-device_model" to "Mi 6",
            "x-rpc-h265_supported" to "1",
            "x-rpc-verify_key" to Endpoints.VERIFY_KEY,
            "x-rpc-csm_source" to "discussion",
            "Connection" to "Keep-Alive",
            "Accept-Encoding" to "gzip",
        )

    /** 米游币余额查询沿用独立的网页请求参数，不与 App 打卡参数混用。 */
    fun coinState(cookie: String): Map<String, String> =
        linkedMapOf(
            "Cookie" to cookie,
            "Host" to Endpoints.BBS_HOST,
            "Referer" to "https://webstatic.mihoyo.com",
            "Origin" to "https://webstatic.mihoyo.com",
            "User-Agent" to Endpoints.effectiveUserAgent(""),
            "Accept" to "application/json, text/plain, */*",
            "Accept-Language" to "zh-CN,en-US;q=0.8",
            "X-Requested-With" to "com.mihoyo.hyperion",
        )
}
