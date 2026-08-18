package com.questtick.sign

import android.util.Log
import com.questtick.core.throwIfCancellation
import com.questtick.net.HttpRequestConfig
import com.questtick.net.HttpTransport
import com.questtick.net.get
import com.questtick.net.getIdempotent
import com.questtick.net.postJson
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * 米游社二维码扫码登录管理器。
 *
 * 流程：创建二维码获取 ticket / url，轮询 Created、Scanned、Confirmed、Expired 等状态，
 * Confirmed 后优先从响应体 tokens[token_type=1] 提取 stoken，并合并 Set-Cookie 中的登录凭证。
 */
object QRLoginManager {
    private const val TAG = "QRLoginManager"

    private const val APP_ID = "ddxf5dufpuyo"
    private const val CLIENT_TYPE = "3"
    private const val HYP_USER_AGENT = "HYPContainer/1.1.4.133"
    private const val TOKEN_TYPE_STOKEN = "1"
    private const val TOKEN_TYPE_LTOKEN = "2"
    private const val TOKEN_TYPE_COOKIE_TOKEN = "4"
    private const val MULTI_TOKEN_TYPES_STOKEN = "3"

    private const val CREATE_URL =
        "https://passport-api.mihoyo.com/account/ma-cn-passport/app/createQRLogin"
    private const val QUERY_URL =
        "https://passport-api.mihoyo.com/account/ma-cn-passport/app/queryQRLoginStatus"
    private const val MULTI_TOKEN_URL =
        "https://api-takumi.mihoyo.com/auth/api/getMultiTokenByLoginTicket"

    private const val MAX_QR_RESPONSE_BYTES = 1024 * 1024

    // 数据结构。

    data class QRCode(val ticket: String, val url: String)

    sealed class ScanStatus {
        object Created : ScanStatus()

        object Scanned : ScanStatus()

        data class Confirmed(
            val uid: String,
            val cookieToken: String,
            val ltoken: String,
            val stoken: String,
            val mid: String,
            /** 原始响应中的 login_ticket，仅用于 stoken 缺失时兜底兑换。 */
            val loginTicket: String = "",
            /** 米游社账号昵称，用于扫码成功后自动回填账号名称。 */
            val nickname: String = "",
        ) : ScanStatus()

        object Expired : ScanStatus()

        object Cancelled : ScanStatus()

        data class TransientError(val msg: String) : ScanStatus()

        data class Error(val msg: String) : ScanStatus()
    }

    // 对外 API。

    /** 生成登录二维码。 */
    suspend fun createQRCode(
        deviceId: String,
        httpTransport: HttpTransport,
        recordError: ((String) -> Unit)? = null,
    ): Result<QRCode> {
        return try {
            val headers = qrPassportHeaders(deviceId)
            val resp = httpTransport.postJson(CREATE_URL, headers, JSONObject())
            val json = resp.json()
            val retcode = json.optInt("retcode", -999)
            if (retcode != 0) {
                recordError?.invoke("createQRLogin http=${resp.code}, retcode=$retcode, message=${json.optString("message")}")
                Log.w(TAG, "createQRCode failed: retcode=$retcode")
                return Result.failure(RuntimeException("createQRLogin retcode=$retcode: ${json.optString("message")}"))
            }
            val data =
                json.optJSONObject("data")
                    ?: return Result.failure(RuntimeException("createQRLogin: data is null"))
            val ticket = data.optString("ticket")
            val url = data.optString("url")
            if (ticket.isBlank() || url.isBlank()) {
                return Result.failure(RuntimeException("createQRLogin: ticket or url is blank"))
            }
            Result.success(QRCode(ticket = ticket, url = url))
        } catch (e: Exception) {
            e.throwIfCancellation()
            recordError?.invoke("createQRLogin exception: ${ErrorText.detailOf(e)}")
            Log.w(TAG, "createQRCode failed", e)
            Result.failure(e)
        }
    }

    /** 查询一次扫码状态；需要底层 OkHttp 读取 Set-Cookie 响应头。 */
    suspend fun queryStatus(
        ticket: String,
        deviceId: String,
        httpTransport: HttpTransport,
        recordError: ((String) -> Unit)? = null,
    ): ScanStatus {
        return try {
            val body = JSONObject().apply { put("ticket", ticket) }

            val response =
                httpTransport.postJson(
                    url = QUERY_URL,
                    headers = qrPassportHeaders(deviceId),
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
                recordError?.invoke("queryQRLoginStatus http=${response.code}, retcode=$retcode, message=${json.optString("message")}")
            }

            val data = json.optJSONObject("data")
            val dataStatus = data?.optString("status")
            val confirmedData = data?.takeIf { retcode == 0 && it.optString("status") == "Confirmed" }
            if (confirmedData != null) {
                parseConfirmed(response.headerValues("Set-Cookie"), confirmedData, httpTransport, recordError).also { result ->
                    if (result is ScanStatus.Error) recordError?.invoke("queryQRLoginStatus confirmed: ${result.msg}")
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
            recordError?.invoke("queryQRLoginStatus exception: ${ErrorText.detailOf(e)}")
            Log.w(TAG, "queryStatus transient failure", e)
            ScanStatus.TransientError(Mask.sensitive(ErrorText.fromException(e)))
        }
    }

    /** 使用协程 Flow 轮询扫码状态，遇到成功、过期或错误等终态后结束。 */
    fun pollStatus(
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
                val status = queryStatus(ticket, deviceId, httpTransport, recordError)
                emit(status)
                when (status) {
                    is ScanStatus.Confirmed,
                    is ScanStatus.Expired,
                    is ScanStatus.Cancelled,
                    is ScanStatus.Error,
                    -> return@flow // 终态，结束轮询。
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

    // 内部辅助方法。

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

    internal fun parseConfirmedCookies(
        setCookieHeaders: List<String>,
        userUid: String = "",
        userMid: String = "",
    ): ScanStatus {
        val parsed =
            CookieParser.parseLoginCookies(
                setCookieHeaders = setCookieHeaders,
                userUid = userUid,
                userMid = userMid,
            )
        return buildConfirmedStatus(
            uid = parsed.uid,
            cookieToken = parsed.cookieToken,
            ltoken = parsed.ltoken,
            stoken = parsed.stoken,
            mid = parsed.mid,
            loginTicket = parsed.loginTicket,
            nickname = "",
        )
    }

    internal fun parseConfirmedData(
        setCookieHeaders: List<String>,
        data: JSONObject,
    ): ScanStatus {
        val userInfo = data.optJSONObject("user_info")
        val bodyTokens = parseConfirmedBodyTokens(data)
        val parsed =
            CookieParser.parseLoginCookies(
                setCookieHeaders = setCookieHeaders,
                userUid = userInfo.firstNonBlank("aid", "uid", "account_id", "stuid", "user_id"),
                userMid = userInfo.firstNonBlank("mid", "account_mid", "account_mid_v2", "stmid", "stmid_v2"),
            )
        return buildConfirmedStatus(
            uid = parsed.uid.ifBlank { bodyTokens.uid },
            cookieToken = parsed.cookieToken.ifBlank { bodyTokens.cookieToken },
            ltoken = parsed.ltoken.ifBlank { bodyTokens.ltoken },
            stoken = bodyTokens.stoken.ifBlank { parsed.stoken },
            mid = parsed.mid.ifBlank { bodyTokens.mid },
            loginTicket = parsed.loginTicket.ifBlank { bodyTokens.loginTicket },
            nickname = parseConfirmedNickname(data),
        )
    }

    private fun parseConfirmedNickname(data: JSONObject): String =
        data.optJSONObject("user_info").firstNonBlank("nickname", "name", "user_name", "username")
            .ifBlank { data.optJSONObject("account_info").firstNonBlank("nickname", "name") }
            .ifBlank { data.optJSONObject("user").firstNonBlank("nickname", "name", "username") }
            .ifBlank { data.optJSONObject("account").firstNonBlank("nickname", "name") }
            .ifBlank { data.firstNonBlank("nickname", "name", "user_name", "username") }

    internal fun parseMysUserNickname(json: JSONObject): String =
        json.optJSONObject("data")
            ?.optJSONObject("user_info")
            .firstNonBlank("nickname", "name")

    private fun buildConfirmedStatus(
        uid: String,
        cookieToken: String,
        ltoken: String,
        stoken: String,
        mid: String,
        loginTicket: String,
        nickname: String,
    ): ScanStatus {
        if (uid.isBlank()) {
            return ScanStatus.Error("扫码确认成功，但未能解析账号 UID")
        }
        if (cookieToken.isBlank() && stoken.isBlank() && loginTicket.isBlank()) {
            return ScanStatus.Error("扫码确认成功，但响应未返回 cookie_token、stoken 或 login_ticket")
        }

        return ScanStatus.Confirmed(
            uid = uid,
            cookieToken = cookieToken,
            ltoken = ltoken,
            stoken = stoken,
            mid = mid,
            loginTicket = loginTicket,
            nickname = nickname,
        )
    }

    private data class ConfirmedBodyTokens(
        val uid: String = "",
        val cookieToken: String = "",
        val ltoken: String = "",
        val stoken: String = "",
        val mid: String = "",
        val loginTicket: String = "",
    )

    private fun parseConfirmedBodyTokens(data: JSONObject): ConfirmedBodyTokens {
        val tokens = data.optJSONArray("tokens") ?: return ConfirmedBodyTokens()
        var uid = ""
        var cookieToken = ""
        var ltoken = ""
        var stokenFromTokenType = ""
        var stokenFallback = ""
        var mid = ""
        var loginTicket = ""

        for (index in 0 until tokens.length()) {
            val item = tokens.optJSONObject(index) ?: continue
            val token = item.firstNonBlank("token", "value", "token_value", "tokenValue")
            val tokenName = item.firstNonBlank("name", "token_name", "tokenName").lowercase()
            val tokenType = item.firstNonBlank("token_type", "tokenType", "type")
            val tokenCookies = CookieParser.parseCookieHeader(token)

            uid = uid.ifBlank { item.firstNonBlank("uid", "aid", "account_id", "stuid", "login_uid") }
            uid = uid.ifBlank { tokenCookies.firstNonBlank("stuid", "account_id", "account_id_v2", "login_uid") }
            mid = mid.ifBlank { item.firstNonBlank("mid", "account_mid", "account_mid_v2", "stmid", "stmid_v2") }
            mid = mid.ifBlank { tokenCookies.firstNonBlank("stmid_v2", "stmid", "account_mid_v2", "mid") }
            cookieToken = cookieToken.ifBlank { tokenCookies.firstNonBlank("cookie_token_v2", "cookie_token") }
            ltoken = ltoken.ifBlank { tokenCookies.firstNonBlank("ltoken_v2", "ltoken") }
            stokenFallback = stokenFallback.ifBlank { tokenCookies.firstNonBlank("stoken_v2", "stoken") }
            loginTicket = loginTicket.ifBlank { tokenCookies.firstNonBlank("login_ticket", "login_ticket_v2") }

            when {
                tokenType == TOKEN_TYPE_STOKEN -> {
                    stokenFromTokenType = stokenFromTokenType.ifBlank { token }
                }
                tokenName == "stoken" || tokenName == "stoken_v2" -> {
                    stokenFallback = stokenFallback.ifBlank { token }
                }
                tokenName == "ltoken" || tokenName == "ltoken_v2" || tokenType == TOKEN_TYPE_LTOKEN -> {
                    ltoken = ltoken.ifBlank { token }
                }
                tokenName == "cookie_token" || tokenName == "cookie_token_v2" || tokenType == TOKEN_TYPE_COOKIE_TOKEN -> {
                    cookieToken = cookieToken.ifBlank { token }
                }
                tokenName == "login_ticket" || tokenName == "login_ticket_v2" -> {
                    loginTicket = loginTicket.ifBlank { token }
                }
            }
        }

        return ConfirmedBodyTokens(
            uid = uid,
            cookieToken = cookieToken,
            ltoken = ltoken,
            stoken = stokenFromTokenType.ifBlank { stokenFallback },
            mid = mid,
            loginTicket = loginTicket,
        )
    }

    internal data class MultiToken(
        val stoken: String = "",
        val ltoken: String = "",
    )

    internal fun parseMultiTokenResponse(json: JSONObject): MultiToken {
        val list = json.optJSONObject("data")?.optJSONArray("list") ?: return MultiToken()
        var stokenFromTokenType = ""
        var stokenFallback = ""
        var ltoken = ""
        for (index in 0 until list.length()) {
            val item = list.optJSONObject(index) ?: continue
            val token = item.firstNonBlank("token", "value", "token_value", "tokenValue")
            val tokenName = item.firstNonBlank("name", "token_name", "tokenName").lowercase()
            val tokenType = item.firstNonBlank("token_type", "tokenType", "type")
            when {
                tokenType == TOKEN_TYPE_STOKEN -> stokenFromTokenType = stokenFromTokenType.ifBlank { token }
                tokenName == "stoken" || tokenName == "stoken_v2" -> stokenFallback = stokenFallback.ifBlank { token }
                tokenName == "ltoken" || tokenName == "ltoken_v2" || tokenType == TOKEN_TYPE_LTOKEN -> {
                    ltoken = ltoken.ifBlank { token }
                }
            }
        }
        return MultiToken(stoken = stokenFromTokenType.ifBlank { stokenFallback }, ltoken = ltoken)
    }

    internal suspend fun exchangeStokenByLoginTicket(
        uid: String,
        loginTicket: String,
        httpTransport: HttpTransport,
        recordError: ((String) -> Unit)? = null,
    ): Result<MultiToken> {
        if (uid.isBlank() || loginTicket.isBlank()) {
            recordError?.invoke("getMultiTokenByLoginTicket 缺少 login_ticket 或 UID")
            return Result.failure(IllegalArgumentException("login_ticket 或 UID 为空"))
        }
        return try {
            val url = MULTI_TOKEN_URL +
                "?login_ticket=${urlEncode(loginTicket)}&token_types=$MULTI_TOKEN_TYPES_STOKEN&uid=${urlEncode(uid)}"
            val headers = linkedMapOf(
                "Cookie" to "login_ticket=$loginTicket; login_uid=$uid",
                "User-Agent" to HYP_USER_AGENT,
                "Accept" to "application/json, text/plain, */*",
                "Referer" to "https://user.mihoyo.com/",
                "Origin" to "https://user.mihoyo.com",
                "x-rpc-app_version" to Endpoints.APP_VERSION,
                "x-rpc-client_type" to CLIENT_TYPE,
                "x-rpc-app_id" to APP_ID,
            )
            val resp = httpTransport.get(url, headers)
            val json = resp.json()
            val retcode = json.optInt("retcode", -999)
            if (retcode != 0) {
                recordError?.invoke("getMultiTokenByLoginTicket http=${resp.code}, retcode=$retcode, message=${json.optString("message")}")
                return Result.failure(
                    RuntimeException("getMultiTokenByLoginTicket retcode=$retcode: ${json.optString("message")}"),
                )
            }
            val token = parseMultiTokenResponse(json)
            if (token.stoken.isBlank()) {
                Result.failure(RuntimeException("getMultiTokenByLoginTicket 未返回 stoken"))
            } else {
                Result.success(token)
            }
        } catch (e: Exception) {
            e.throwIfCancellation()
            recordError?.invoke("getMultiTokenByLoginTicket exception: ${ErrorText.detailOf(e)}")
            Result.failure(e)
        }
    }

    private fun JSONObject?.firstNonBlank(vararg keys: String): String {
        if (this == null) return ""
        for (key in keys) {
            val value = optString(key).trim()
            if (value.isNotBlank()) return value
        }
        return ""
    }

    private fun Map<String, String>.firstNonBlank(vararg keys: String): String {
        for (key in keys) {
            val value = this[key].orEmpty().trim()
            if (value.isNotBlank()) return value
        }
        return ""
    }

    private fun urlEncode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private fun qrPassportHeaders(deviceId: String): Map<String, String> =
        linkedMapOf(
            "x-rpc-app_id" to APP_ID,
            "x-rpc-client_type" to CLIENT_TYPE,
            "x-rpc-device_id" to normalizeQrDeviceId(deviceId),
            "User-Agent" to HYP_USER_AGENT,
            "Accept" to "application/json",
            "Content-Type" to "application/json;charset=utf-8",
        )

    private fun normalizeQrDeviceId(deviceId: String): String {
        val seed = deviceId.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }.ifBlank { "mysqrcode" }
        return buildString {
            while (length < 53) append(seed)
        }.take(53)
    }

    private suspend fun fetchMysUserNickname(
        uid: String,
        httpTransport: HttpTransport,
        recordError: ((String) -> Unit)? = null,
    ): String {
        if (uid.isBlank()) return ""
        return try {
            val resp = httpTransport.getIdempotent(
                "https://${Endpoints.BBS_HOST}/user/wapi/getUserFullInfo?uid=${urlEncode(uid)}&gids=2",
                MysHeaders.account(""),
            )
            val json = resp.json()
            if (json.optInt("retcode", -999) == 0) parseMysUserNickname(json) else ""
        } catch (e: Exception) {
            e.throwIfCancellation()
            recordError?.invoke("二维码账号昵称获取失败: ${ErrorText.detailOf(e)}")
            Log.w(TAG, "fetch mys nickname failed, uid=${Mask.uid(uid)}", e)
            ""
        }
    }

    private suspend fun fillCookieTokenByStokenIfNeeded(
        status: ScanStatus.Confirmed,
        httpTransport: HttpTransport,
    ): ScanStatus.Confirmed {
        if (status.cookieToken.isNotBlank() || status.stoken.isBlank()) return status
        val refreshed = CookieRefresher.refresh(status.uid, status.stoken, status.mid, httpTransport) ?: return status
        return status.copy(
            cookieToken = refreshed.cookieToken,
            ltoken = refreshed.ltoken.ifBlank { status.ltoken },
        )
    }

    /** 从 Confirmed 响应中提取登录凭证，必要时用 login_ticket 兜底兑换 stoken。 */
    private suspend fun parseConfirmed(
        setCookieHeaders: List<String>,
        data: JSONObject,
        httpTransport: HttpTransport,
        recordError: ((String) -> Unit)? = null,
    ): ScanStatus {
        val parsed = parseConfirmedData(
            setCookieHeaders = setCookieHeaders,
            data = data,
        )
        if (parsed !is ScanStatus.Confirmed) {
            return parsed
        }

        val named = parsed.copy(nickname = parsed.nickname.ifBlank { fetchMysUserNickname(parsed.uid, httpTransport, recordError) })
        val withBodyStokenCookie = fillCookieTokenByStokenIfNeeded(named, httpTransport)
        if (withBodyStokenCookie.stoken.isNotBlank()) {
            return withBodyStokenCookie
        }

        val exchanged = exchangeStokenByLoginTicket(
            uid = named.uid,
            loginTicket = named.loginTicket,
            httpTransport = httpTransport,
            recordError = recordError,
        ).getOrElse { e ->
            recordError?.invoke("扫码确认凭据解析失败: ${ErrorText.detailOf(e)}")
            Log.w(TAG, "exchange stoken by login_ticket failed: ${e.message}")
            return if (named.cookieToken.isBlank() && named.stoken.isBlank()) {
                ScanStatus.Error("扫码确认成功，但未能通过 login_ticket 换取 stoken")
            } else {
                named
            }
        }

        return fillCookieTokenByStokenIfNeeded(
            named.copy(
                stoken = exchanged.stoken.ifBlank { named.stoken },
                ltoken = exchanged.ltoken.ifBlank { named.ltoken },
            ),
            httpTransport,
        )
    }
}
