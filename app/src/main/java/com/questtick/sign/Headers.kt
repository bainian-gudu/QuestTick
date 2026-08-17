package com.questtick.sign

/** 米游社与云游戏请求头常量及构造工具。 */
object Endpoints {
    const val WEB_HOST = "api-takumi.mihoyo.com"
    const val ZZZ_HOST = "act-nap-api.mihoyo.com"
    const val BBS_HOST = "bbs-api.miyoushe.com"

    // 米游社请求使用的默认版本；实际请求优先使用本地获取的版本。
    const val APP_VERSION = "2.109.0"

    // 米游社请求使用的设备和系统标识。
    const val DEVICE_MODEL = "iPhone17,2"
    const val DEVICE_NAME = "iPhone"
    const val OS_VERSION = "26.5.2"
    const val CHANNEL = "miyousheluodi"
    val USER_AGENT =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 26_5_2 like Mac OS X) " +
            "AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148"

    /** 获取当前生效的 App 版本号，优先使用传入版本。 */
    fun effectiveAppVersion(customVersion: String): String = customVersion.ifBlank { APP_VERSION }

    /** 根据当前 App 版本生成 User-Agent。 */
    fun effectiveUserAgent(customVersion: String): String =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 26_5_2 like Mac OS X) " +
            "AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148"
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
            put("x-rpc-device_model", Endpoints.DEVICE_MODEL)
            put("x-rpc-device_name", Endpoints.DEVICE_NAME)
            put("x-rpc-sys_version", Endpoints.OS_VERSION)
            put("x-rpc-channel", Endpoints.CHANNEL)
            put("X-Requested-With", "com.mihoyo.hyperion")
            put("x-rpc-challenge", "null")
            put("Accept-Encoding", "gzip, deflate, br")
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
            put("x-rpc-device_model", Endpoints.DEVICE_MODEL)
            put("x-rpc-device_id", deviceId)
            put("x-rpc-platform", "1")
            put("x-rpc-device_name", Endpoints.DEVICE_NAME)
            put("x-rpc-sys_version", Endpoints.OS_VERSION)
            put("x-rpc-channel", Endpoints.CHANNEL)
            put("X-Requested-With", "com.mihoyo.hyperion")
            put("Sec-Fetch-Site", "same-site")
            put("Connection", "keep-alive")
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
            "x-rpc-sys_version" to Endpoints.OS_VERSION,
            "x-rpc-channel" to Endpoints.CHANNEL,
            "x-rpc-device_id" to deviceId,
            "x-rpc-device_name" to Endpoints.DEVICE_NAME,
            "x-rpc-device_model" to Endpoints.DEVICE_MODEL,
            "x-rpc-h265_supported" to "1",
            "x-rpc-verify_key" to "bll8iq97cem8",
            "x-rpc-csm_source" to "home",
        )

    /** 米游币任务状态查询使用网页 Cookie 请求头。 */
    fun coinState(cookie: String): Map<String, String> =
        linkedMapOf(
            "Cookie" to cookie,
            "Host" to Endpoints.BBS_HOST,
            "Referer" to "https://webstatic.mihoyo.com",
            "Origin" to "https://webstatic.mihoyo.com",
            "User-Agent" to Endpoints.USER_AGENT,
            "Accept" to "application/json, text/plain, */*",
            "Accept-Language" to "zh-CN,en-US;q=0.8",
            "X-Requested-With" to "com.mihoyo.hyperion",
        )
}
