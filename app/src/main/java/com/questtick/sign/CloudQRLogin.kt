package com.questtick.sign

import android.util.Log
import com.questtick.core.throwIfCancellation
import com.questtick.net.HttpRequestConfig
import com.questtick.net.HttpTransport
import com.questtick.net.postJson
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.json.JSONObject
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 云游戏二维码扫码登录管理器。
 *
 * 电脑网页版云游戏登录流程：先通过 Passport Web QR 获取网页登录态，再调用对应游戏 SDK 的
 * `combo/granter/login/webLogin` 换取 open_id / combo_token，最后组装云游戏接口需要的
 * `x-rpc-combo_token`（形如 `ai=...;ci=...;oi=...;ct=...;si=...;bi=...`）。
 */
object CloudQRLogin {
    private const val TAG = "CloudQRLogin"

    private const val PASSPORT_CREATE_URL =
        "https://passport-api.mihoyo.com/account/ma-cn-passport/web/createQRLogin"
    private const val PASSPORT_QUERY_URL =
        "https://passport-api.mihoyo.com/account/ma-cn-passport/web/queryQRLoginStatus"

    private const val MAX_QR_RESPONSE_BYTES = 1024 * 1024

    /** 各云游戏在网页端 webLogin 与 combo_token 中使用的 app_id。 */
    const val APP_ID_GENSHIN = "4" // 云原神 app_id
    const val APP_ID_STARRAIL = "8" // 云崩铁 app_id

    private data class CloudWebConfig(
        val loginAppId: String,
        val sdkAppId: String,
        val channelId: String,
        val gameBiz: String,
        val comboWebLoginUrl: String,
        val origin: String,
        val referer: String,
        val appVersion: String,
        val appKey: String,
    )

    /** 电脑网页版云游戏使用的 app_key 配置。 */
    private val CONFIGS: Map<String, CloudWebConfig> =
        linkedMapOf(
            "CloudYS" to
                CloudWebConfig(
                    loginAppId = "c76ync6mutq8",
                    sdkAppId = APP_ID_GENSHIN,
                    channelId = "1",
                    gameBiz = "hk4e_cn",
                    comboWebLoginUrl = "https://hk4e-sdk.mihoyo.com/hk4e_cn/combo/granter/login/webLogin",
                    origin = "https://ys.mihoyo.com",
                    referer = "https://ys.mihoyo.com/cloud/",
                    appVersion = "6.7.0",
                    // 云原神网页端 combo-web app_key。
                    appKey = "d0d3a7342df2026a70f650b907800111",
                ),
            "CloudSR" to
                CloudWebConfig(
                    loginAppId = "c90mr1bwo2rk",
                    sdkAppId = APP_ID_STARRAIL,
                    channelId = "1",
                    gameBiz = "hkrpg_cn",
                    comboWebLoginUrl = "https://hkrpg-sdk.mihoyo.com/hkrpg_cn/combo/granter/login/webLogin",
                    origin = "https://sr.mihoyo.com",
                    referer = "https://sr.mihoyo.com/cloud/",
                    appVersion = "4.3.0",
                    // 云崩铁网页端 combo-web app_key。
                    appKey = "4650f3a396d34d576c3d65df26415394",
                ),
        )

    private const val WEB_USER_AGENT =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"

    // 数据结构。

    data class QRCode(val ticket: String, val url: String)

    sealed class ScanStatus {
        object Created : ScanStatus()

        object Scanned : ScanStatus()

        data class Confirmed(
            val uid: String,
            val mid: String,
            /** queryQRLoginStatus 确认登录后返回的 Cookie，用于后续 combo webLogin。 */
            val cookieHeader: String,
        ) : ScanStatus()

        object Expired : ScanStatus()

        object Cancelled : ScanStatus()

        data class TransientError(val msg: String) : ScanStatus()

        data class Error(val msg: String) : ScanStatus()
    }

    // 对外 API。

    /** 生成电脑网页版 Passport 扫码二维码。 */
    suspend fun createQRCode(
        gameKey: String,
        deviceId: String,
        httpTransport: HttpTransport,
        recordError: ((String) -> Unit)? = null,
    ): Result<QRCode> {
        return try {
            val config = configFor(gameKey)
            val resp =
                httpTransport.postJson(
                    PASSPORT_CREATE_URL,
                    passportHeaders(config, deviceId),
                    JSONObject(),
                )
            val json = resp.json()
            val retcode = json.optInt("retcode", -999)
            if (retcode != 0) {
                recordError?.invoke("$gameKey createQRLogin http=${resp.code}, retcode=$retcode, message=${json.optString("message")}")
                Log.w(TAG, "createQRLogin failed: retcode=$retcode")
                return Result.failure(RuntimeException("createQRLogin retcode=$retcode: ${json.optString("message")}"))
            }
            val data =
                json.optJSONObject("data")
                    ?: return Result.failure(RuntimeException("createQRLogin: data is null"))
            val ticket = data.optString("ticket")
            val qrUrl = data.optString("url")
            if (ticket.isBlank() || qrUrl.isBlank()) {
                return Result.failure(RuntimeException("createQRLogin: ticket or url is blank"))
            }
            Result.success(QRCode(ticket = ticket, url = qrUrl))
        } catch (e: Exception) {
            e.throwIfCancellation()
            recordError?.invoke("$gameKey createQRLogin exception: ${ErrorText.detailOf(e)}")
            Log.w(TAG, "createQRCode failed", e)
            Result.failure(e)
        }
    }

    /** 查询一次扫码状态；确认登录时需要读取 Set-Cookie，因此直接使用 OkHttp。 */
    suspend fun queryStatus(
        gameKey: String,
        ticket: String,
        deviceId: String,
        httpTransport: HttpTransport,
        recordError: ((String) -> Unit)? = null,
    ): ScanStatus {
        return try {
            val config = configFor(gameKey)
            val body = JSONObject().put("ticket", ticket)
            val response =
                httpTransport.postJson(
                    url = PASSPORT_QUERY_URL,
                    headers = passportHeaders(config, deviceId),
                    json = body,
                    config = HttpRequestConfig(maxResponseBytes = MAX_QR_RESPONSE_BYTES),
                )
            val json =
                try {
                    JSONObject(response.body)
                } catch (e: Exception) {
                    e.throwIfCancellation()
                    Log.w(
                        TAG,
                        "queryStatus response parse failed: http=${response.code}, " +
                            "bodyLength=${response.body.length}, error=${e.javaClass.simpleName}",
                    )
                    JSONObject()
                }
            val retcode = json.optInt("retcode", -999)
            if (response.code !in 200..299 || retcode != 0) {
                recordError?.invoke("$gameKey queryQRLoginStatus http=${response.code}, retcode=$retcode, message=${json.optString("message")}")
            }

            val data = json.optJSONObject("data")
            val dataStatus = data?.optString("status")
            val confirmedData = data?.takeIf { retcode == 0 && it.optString("status") == "Confirmed" }
            if (retcode == 0 && data == null) {
                recordError?.invoke("$gameKey queryQRLoginStatus confirmed: data is null")
                ScanStatus.Error("queryQRLoginStatus: data is null")
            } else if (confirmedData != null) {
                parsePassportConfirmed(response.headerValues("Set-Cookie"), confirmedData).also { result ->
                    if (result is ScanStatus.Error) recordError?.invoke("$gameKey queryQRLoginStatus confirmed: ${result.msg}")
                }
            } else {
                mapQueryStatus(
                    retcode = retcode,
                    message = json.optString("message", "retcode=$retcode"),
                    dataStatus = dataStatus,
                    confirmed = ScanStatus.Error("confirmed status missing data"),
                )
            }
        } catch (e: Exception) {
            e.throwIfCancellation()
            recordError?.invoke("$gameKey queryQRLoginStatus exception: ${ErrorText.detailOf(e)}")
            Log.w(TAG, "queryStatus transient failure", e)
            ScanStatus.TransientError(Mask.sensitive(ErrorText.fromException(e)))
        }
    }

    /** 持续轮询扫码状态，直到成功、过期或失败。 */
    fun pollStatus(
        gameKey: String,
        ticket: String,
        deviceId: String,
        httpTransport: HttpTransport,
        intervalMs: Long = 800,
        maxIntervalMs: Long = 2000,
        recordError: ((String) -> Unit)? = null,
    ): Flow<ScanStatus> =
        flow {
            var attempts = 0
            var currentInterval = intervalMs.coerceAtLeast(500L)
            while (true) {
                val status = queryStatus(gameKey, ticket, deviceId, httpTransport, recordError)
                emit(status)
                when (status) {
                    is ScanStatus.Confirmed,
                    is ScanStatus.Expired,
                    is ScanStatus.Cancelled,
                    is ScanStatus.Error,
                    -> return@flow
                    else -> {
                        attempts++
                        val waitMs = if (status is ScanStatus.Scanned) 600L else currentInterval
                        val jitter = kotlin.random.Random.nextLong(0, 160)
                        delay(waitMs + jitter)
                        currentInterval = (currentInterval * 1.25).toLong().coerceAtMost(maxIntervalMs)
                        // 二维码通常 180s 过期，超时主动结束防止无限轮询。
                        if (attempts >= 160) {
                            emit(ScanStatus.Error("轮询超时，请刷新二维码重试"))
                            return@flow
                        }
                    }
                }
            }
        }

    internal fun mapQueryStatus(
        retcode: Int,
        message: String,
        dataStatus: String?,
        confirmed: ScanStatus,
    ): ScanStatus =
        when {
            retcode == -3501 -> ScanStatus.Expired
            retcode == -3505 -> ScanStatus.Cancelled
            retcode != 0 -> ScanStatus.Error(message.ifBlank { "retcode=$retcode" })
            dataStatus == "Created" -> ScanStatus.Created
            dataStatus == "Scanned" -> ScanStatus.Scanned
            dataStatus == "Confirmed" -> confirmed
            else -> ScanStatus.Error("unknown status: $dataStatus")
        }

    /**
     * 使用 Passport 网页登录 Cookie 调用 SDK webLogin，换取云游戏接口需要的 `x-rpc-combo_token`。
     *
     * @return 成功时返回形如 `ai=4;ci=1;oi=...;ct=...;si=...;bi=hk4e_cn` 的字符串。
     */
    suspend fun exchangeComboToken(
        gameKey: String,
        cookieHeader: String,
        deviceId: String,
        httpTransport: HttpTransport,
        recordError: ((String) -> Unit)? = null,
    ): Result<String> {
        return try {
            val config = configFor(gameKey)
            if (cookieHeader.isBlank()) {
                return Result.failure(RuntimeException("webLogin: Cookie 为空"))
            }

            val body =
                JSONObject().apply {
                    put("app_id", config.sdkAppId.toIntOrNull() ?: config.sdkAppId)
                    put("channel_id", config.channelId.toIntOrNull() ?: config.channelId)
                }
            val resp =
                httpTransport.postJson(
                    config.comboWebLoginUrl,
                    comboWebLoginHeaders(config, deviceId, cookieHeader),
                    body,
                )
            val json = resp.json()
            val retcode = json.optInt("retcode", -999)
            if (retcode != 0) {
                recordError?.invoke("$gameKey webLogin http=${resp.code}, retcode=$retcode, message=${json.optString("message")}")
                Log.w(TAG, "combo webLogin failed: retcode=$retcode")
                return Result.failure(RuntimeException("combo webLogin retcode=$retcode: ${json.optString("message")}"))
            }

            parseWebLoginComboToken(gameKey, json)
        } catch (e: Exception) {
            e.throwIfCancellation()
            recordError?.invoke("$gameKey webLogin exception: ${ErrorText.detailOf(e)}")
            Log.w(TAG, "exchangeComboToken failed", e)
            Result.failure(e)
        }
    }

    internal fun parseWebLoginComboToken(
        gameKey: String,
        json: JSONObject,
    ): Result<String> {
        val config = configFor(gameKey)
        val data =
            jsonObjectFrom(json.opt("data"))
                ?: return Result.failure(RuntimeException("combo webLogin: data is null"))
        val nested = jsonObjectFrom(data.opt("data"))

        val appId =
            data.optString("app_id")
                .ifBlank { nested?.optString("app_id").orEmpty() }
                .ifBlank { config.sdkAppId }
        val channelId =
            data.optString("channel_id")
                .ifBlank { nested?.optString("channel_id").orEmpty() }
                .ifBlank { config.channelId }
        val openId =
            data.optString("open_id")
                .ifBlank { nested?.optString("open_id").orEmpty() }
        val comboToken =
            data.optString("combo_token")
                .ifBlank { nested?.optString("combo_token").orEmpty() }

        if (openId.isBlank() || comboToken.isBlank()) {
            return Result.failure(RuntimeException("combo webLogin: open_id 或 combo_token 为空"))
        }

        return Result.success(buildWebComboToken(config, appId, channelId, openId, comboToken))
    }

    internal fun buildWebComboToken(
        gameKey: String,
        appId: String,
        channelId: String,
        openId: String,
        comboToken: String,
    ): String = buildWebComboToken(configFor(gameKey), appId, channelId, openId, comboToken)

    internal fun parsePassportConfirmedCookies(
        setCookieHeaders: List<String>,
        userUid: String = "",
        userMid: String = "",
    ): ScanStatus {
        val cookieHeader = buildCookieHeader(setCookieHeaders)
        if (cookieHeader.isBlank()) return ScanStatus.Error("扫码确认成功，但响应未返回 Cookie")

        val uid =
            userUid
                .ifBlank { cookieValue(cookieHeader, "account_id") }
                .ifBlank { cookieValue(cookieHeader, "account_id_v2") }
                .ifBlank { cookieValue(cookieHeader, "ltuid_v2") }
        val mid =
            userMid
                .ifBlank { cookieValue(cookieHeader, "account_mid_v2") }
                .ifBlank { cookieValue(cookieHeader, "ltmid_v2") }

        if (uid.isBlank()) {
            return ScanStatus.Error("扫码确认成功，但未能确认账号 UID")
        }

        return ScanStatus.Confirmed(uid = uid, mid = mid, cookieHeader = cookieHeader)
    }

    // 内部辅助方法。

    private fun configFor(gameKey: String): CloudWebConfig = CONFIGS[gameKey] ?: CONFIGS.getValue("CloudYS")

    private fun passportHeaders(
        config: CloudWebConfig,
        deviceId: String,
    ): Map<String, String> =
        linkedMapOf(
            "x-rpc-app_id" to config.loginAppId,
            "x-rpc-app_version" to config.appVersion,
            "x-rpc-client_type" to "17",
            "x-rpc-game_biz" to config.gameBiz,
            "x-rpc-device_id" to deviceId,
            "Content-Type" to "application/json;charset=utf-8",
            "Accept" to "application/json, text/plain, */*",
            "Origin" to config.origin,
            "Referer" to config.referer,
            "User-Agent" to WEB_USER_AGENT,
        )

    private fun comboWebLoginHeaders(
        config: CloudWebConfig,
        deviceId: String,
        cookieHeader: String,
    ): Map<String, String> =
        linkedMapOf(
            "Cookie" to cookieHeader,
            "Content-Type" to "application/json;charset=utf-8",
            "Accept" to "application/json, text/plain, */*",
            "Origin" to config.origin,
            "Referer" to config.referer,
            "User-Agent" to WEB_USER_AGENT,
            "x-rpc-app_id" to config.sdkAppId,
            "x-rpc-app_version" to config.appVersion,
            "x-rpc-client_type" to "17",
            "x-rpc-channel" to "mihoyo",
            "x-rpc-device_id" to deviceId,
            "x-rpc-device_model" to "Macintosh",
            "x-rpc-device_name" to "Apple Macintosh",
            "x-rpc-language" to "zh-cn",
            "x-rpc-sys_version" to "Mac OS 10.15.7",
            "x-rpc-vendor_id" to "2",
            "x-rpc-cps" to "mac_mihoyo",
        )

    private fun parsePassportConfirmed(
        setCookieHeaders: List<String>,
        data: JSONObject,
    ): ScanStatus {
        val userInfo = data.optJSONObject("user_info")
        return parsePassportConfirmedCookies(
            setCookieHeaders = setCookieHeaders,
            userUid = userInfo?.optString("aid").orEmpty(),
            userMid = userInfo?.optString("mid").orEmpty(),
        )
    }

    private fun buildCookieHeader(setCookies: List<String>): String {
        val cookies = linkedMapOf<String, String>()
        setCookies.forEach { raw ->
            val pair = raw.substringBefore(";").split("=", limit = 2)
            if (pair.size == 2 && pair[0].isNotBlank()) {
                cookies[pair[0].trim()] = pair[1].trim()
            }
        }
        return cookies.entries.joinToString("; ") { (k, v) -> "$k=$v" }
    }

    private fun cookieValue(
        cookieHeader: String,
        name: String,
    ): String {
        return cookieHeader.split(';')
            .map { it.trim() }
            .firstOrNull { it.startsWith("$name=") }
            ?.substringAfter('=')
            .orEmpty()
    }

    private fun jsonObjectFrom(value: Any?): JSONObject? =
        when (value) {
            is JSONObject -> value
            is String -> if (value.isBlank()) null else runCatching { JSONObject(value) }.getOrNull()
            else -> null
        }

    private fun buildWebComboToken(
        config: CloudWebConfig,
        appId: String,
        channelId: String,
        openId: String,
        comboToken: String,
    ): String {
        val signParams =
            sortedMapOf(
                "app_id" to appId,
                "channel_id" to channelId,
                "combo_token" to comboToken,
                "open_id" to openId,
            )
        val signPayload = signParams.entries.joinToString("&") { (k, v) -> "$k=$v" }
        val sign = hmacSha256Hex(signPayload, config.appKey)
        return listOf(
            "ai=$appId",
            "ci=$channelId",
            "oi=$openId",
            "ct=$comboToken",
            "si=$sign",
            "bi=${config.gameBiz}",
        ).joinToString(";")
    }

    private fun hmacSha256Hex(
        payload: String,
        key: String,
    ): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(payload.toByteArray(Charsets.UTF_8))
            .joinToString("") { b -> (b.toInt() and 0xff).toString(16).padStart(2, '0') }
    }
}
