package com.questtick.sign

import android.util.Log
import com.questtick.core.throwIfCancellation
import com.questtick.data.FailureCategory
import com.questtick.net.HttpTransport
import com.questtick.net.getIdempotent
import com.questtick.net.postJson
import kotlinx.coroutines.delay
import org.json.JSONObject

/** 云游戏签到引擎：支持动态版本、通知确认和结构化失败结果。 */
class CloudSignIn(
    private val deviceId: String,
    private val httpTransport: HttpTransport,
) {
    data class GameConfig(
        val key: String,
        val name: String,
        val baseURL: String,
        val gameHeaders: Map<String, String>,
    )

    data class Outcome(
        val success: Boolean,
        val skipped: Boolean,
        val message: String,
        /** 详细日志使用的技术细节。 */
        val detail: String = "",
        val failure: TaskFailureDescriptor = TaskFailureDescriptor(FailureCategory.NONE),
    )

    companion object {
        private const val TAG = "CloudSignIn"

        private const val DEFAULT_CLIENT_TYPE = "17"
        private const val WALLET_REFRESH_DELAY_MS = 1_200L

        internal fun calculateClaimedFreeTime(
            beforeFreeTime: Int,
            afterFreeTime: Int,
        ): Int = (afterFreeTime - beforeFreeTime).coerceAtLeast(0)

        val GAMES: Map<String, GameConfig> =
            linkedMapOf(
                "CloudYS" to
                    GameConfig(
                        key = "CloudYS",
                        name = "云原神",
                        baseURL = "https://api-cloudgame.mihoyo.com/hk4e_cg_cn",
                        gameHeaders =
                            linkedMapOf(
                                "Host" to "api-cloudgame.mihoyo.com",
                                "origin" to "https://ys.mihoyo.com",
                                "Referer" to "https://ys.mihoyo.com/",
                                "x-rpc-app_id" to "4",
                                "x-rpc-app_version" to "6.7.0",
                                "x-rpc-cg_game_biz" to "hk4e_cn",
                                "x-rpc-op_biz" to "clgm_cn",
                            ),
                    ),
                "CloudSR" to
                    GameConfig(
                        key = "CloudSR",
                        name = "云崩铁",
                        baseURL = "https://cg-hkrpg-api.mihoyo.com/hkrpg_cn/cg",
                        gameHeaders =
                            linkedMapOf(
                                "Host" to "cg-hkrpg-api.mihoyo.com",
                                "origin" to "https://sr.mihoyo.com",
                                "Referer" to "https://sr.mihoyo.com/",
                                "x-rpc-app_id" to "8",
                                "x-rpc-app_version" to "4.3.0",
                                "x-rpc-cg_game_biz" to "hkrpg_cn",
                                "x-rpc-op_biz" to "clgm_hkrpg-cn",
                            ),
                    ),
            )
    }

    private fun effectiveVersion(game: GameConfig): String {
        return when (game.key) {
            "CloudYS" -> CloudVersionRepository.effectiveYs().ifBlank { game.gameHeaders["x-rpc-app_version"] ?: "6.7.0" }
            "CloudSR" -> CloudVersionRepository.effectiveSr().ifBlank { game.gameHeaders["x-rpc-app_version"] ?: "4.3.0" }
            else -> game.gameHeaders["x-rpc-app_version"] ?: "1.0.0"
        }
    }

    private fun buildHeaders(
        game: GameConfig,
        token: String,
        version: String? = null,
        clientType: String = DEFAULT_CLIENT_TYPE,
    ): Map<String, String> {
        val effectiveVersion = version ?: effectiveVersion(game)
        val base = game.gameHeaders.toMutableMap()
        // 云游戏接口核心鉴权与设备指纹必须四件套：combo_token / client_type / app_version / device_id。
        // 当前固定使用网页版 client_type=17 及其对应的设备与渠道头。
        base["x-rpc-combo_token"] = token
        base["x-rpc-client_type"] = clientType
        base["x-rpc-app_version"] = effectiveVersion
        base["x-rpc-device_id"] = deviceId
        // client_type=17 对应网页端云游戏请求，设备与渠道头需要一起匹配。
        base["x-rpc-device_model"] = "Macintosh"
        base["x-rpc-device_name"] = "Apple Macintosh"
        base["x-rpc-sys_version"] = "Mac OS 10.15.7"
        base["x-rpc-channel"] = "mihoyo"
        base["x-rpc-cps"] = "mac_mihoyo"
        base["x-rpc-language"] = "zh-cn"
        base["x-rpc-vendor_id"] = "2"
        base["Accept"] = "application/json, text/plain, */*"
        base["Accept-Language"] = "zh-CN,zh;q=0.9"
        base["Connection"] = "Keep-Alive"
        base["sec-ch-ua"] = "\"Chromium\";v=\"140\", \"Not=A?Brand\";v=\"24\", \"Google Chrome\";v=\"140\""
        base["sec-ch-ua-mobile"] = "?0"
        base["sec-ch-ua-platform"] = "\"macOS\""
        base["sec-fetch-dest"] = "empty"
        base["sec-fetch-mode"] = "cors"
        base["sec-fetch-site"] = "same-site"
        base["User-Agent"] =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"
        return base
    }

    suspend fun runForToken(
        token: String,
        game: GameConfig,
    ): Outcome {
        val version = effectiveVersion(game)
        return runWithVersion(token, game, version)
    }

    private suspend fun runWithVersion(
        token: String,
        game: GameConfig,
        version: String,
    ): Outcome {
        val details = ArrayList<String>()

        val beforeWallet = getWallet(game, token, version, DEFAULT_CLIENT_TYPE)
        if (!beforeWallet.ok) {
            if (beforeWallet.detail.isNotBlank()) details.add(beforeWallet.detail)
            return Outcome(
                success = false,
                skipped = false,
                message = "领取免费时长失败，请检查 Token、云游戏版本或设备参数是否有效",
                detail = details.joinToString("; "),
                failure = beforeWallet.failure,
            )
        }
        if (beforeWallet.detail.isNotBlank()) details.add("before ${beforeWallet.detail}")

        // 云游戏页面展示的是 free_time.free_time（免费时长）。本次领取量不能直接使用
        // send_freetime，而应在领取前后各查询一次钱包，确认弹窗通知后再查询一次，
        // 用领取后免费时长 - 领取前免费时长计算。
        var afterWallet = beforeWallet
        val notifications = listNotifications(game, token, version, beforeWallet.clientType)
        if (notifications.detail.isNotBlank()) {
            details.add(notifications.detail)
        }
        if (notifications.ok && notifications.ids.isNotEmpty()) {
            var ackOk = 0
            var ackFailed = 0
            for (id in notifications.ids) {
                val ack = ack(game, token, id, version, beforeWallet.clientType)
                if (ack.ok) {
                    ackOk++
                } else {
                    ackFailed++
                    details.add(ack.detail.ifBlank { "ackNotification(${maskNotificationId(id)}) failed" })
                    if (ack.failure.category == FailureCategory.RESULT_UNKNOWN) {
                        return Outcome(
                            success = false,
                            skipped = false,
                            message = "签到结果待确认：为避免重复请求，今日不会自动重试",
                            detail = details.joinToString("; "),
                            failure = ack.failure,
                        )
                    }
                }
            }
            details.add("ackNotifications: total=${notifications.ids.size}, ok=$ackOk, failed=$ackFailed")
            if (ackOk > 0) {
                delay(WALLET_REFRESH_DELAY_MS)
                afterWallet = getWallet(game, token, version, beforeWallet.clientType)
                if (afterWallet.detail.isNotBlank()) details.add("after ${afterWallet.detail}")
            }
        }

        val claimedTime = calculateClaimedFreeTime(beforeWallet, afterWallet)
        val currentFreeTime = if (afterWallet.ok) afterWallet.freeTime else beforeWallet.freeTime
        details.add(
            "claimed: beforeFree=${beforeWallet.freeTime}, afterFree=$currentFreeTime, " +
                "claimed=$claimedTime",
        )
        val message =
            if (claimedTime > 0) {
                "领取完成 · 本次+${formatMinutes(claimedTime)} · " +
                    "当前${formatMinutes(currentFreeTime)} · v$version"
            } else {
                "检查完成 · 本次+0分钟 · 当前${formatMinutes(currentFreeTime)} · " +
                    "可能今日已领取或已达上限 · v$version"
            }
        return Outcome(
            success = true,
            skipped = false,
            message = message,
            detail = details.joinToString("; "),
        )
    }

    private data class Wallet(
        val ok: Boolean,
        val freeTime: Int,
        val sentFreeTime: Int = 0,
        val totalTime: Int = 0,
        val playCard: String = "",
        val coin: Int = 0,
        val clientType: String = DEFAULT_CLIENT_TYPE,
        val detail: String = "",
        val failure: TaskFailureDescriptor = TaskFailureDescriptor(FailureCategory.NONE),
    )

    private data class Notifications(val ok: Boolean, val ids: List<String>, val detail: String = "")

    private data class AckResult(
        val ok: Boolean,
        val detail: String = "",
        val failure: TaskFailureDescriptor = TaskFailureDescriptor(FailureCategory.NONE),
    )

    private fun calculateClaimedFreeTime(
        beforeWallet: Wallet,
        afterWallet: Wallet,
    ): Int =
        if (beforeWallet.ok && afterWallet.ok) {
            calculateClaimedFreeTime(beforeWallet.freeTime, afterWallet.freeTime)
        } else {
            0
        }

    private suspend fun getWallet(
        game: GameConfig,
        token: String,
        version: String,
        clientType: String,
    ): Wallet =
        try {
            val headers = buildHeaders(game, token, version, clientType)
            val res = httpTransport.getIdempotent("${game.baseURL}/wallet/wallet/get", headers)
            val data = res.json()
            val retcode = data.optInt("retcode", -999)
            val walletData = data.optJSONObject("data")
            val freeTimeData = walletData?.optJSONObject("free_time")
            val free = freeTimeData?.optInt("free_time", 0) ?: 0
            val sentFree = freeTimeData?.optInt("send_freetime", 0) ?: 0
            val totalTime = walletData?.optInt("total_time", 0) ?: 0
            val playCard = walletData?.optJSONObject("play_card")?.optString("short_msg").orEmpty()
            val coin = walletData?.optJSONObject("coin")?.optInt("coin_num", 0) ?: 0
            val message = data.optString("message")
            if (res.code in 200..299 && (retcode == 0 || message == "OK") && walletData != null) {
                Wallet(
                    ok = true,
                    freeTime = free,
                    sentFreeTime = sentFree,
                    totalTime = totalTime,
                    playCard = playCard,
                    coin = coin,
                    clientType = clientType,
                    detail = buildWalletDetail(
                        res.code,
                        retcode,
                        message,
                        version,
                        clientType,
                        freeTimeData != null,
                        free,
                        sentFree,
                        totalTime,
                        playCard,
                        coin,
                    ),
                )
            } else {
                Wallet(
                    ok = false,
                    freeTime = 0,
                    clientType = clientType,
                    detail = buildWalletFailureDetail(res.code, retcode, message, version, clientType),
                    failure = classifyApiFailure(res.code, retcode),
                )
            }
        } catch (e: Exception) {
            e.throwIfCancellation()
            Log.w(TAG, "getWallet failed: ${game.key}, version=$version, clientType=$clientType", e)
            Wallet(
                ok = false,
                freeTime = 0,
                clientType = clientType,
                detail = ErrorText.detailOf(e),
                failure = TaskFailureClassifier.fromException(e),
            )
        }

    private suspend fun listNotifications(
        game: GameConfig,
        token: String,
        version: String,
        clientType: String,
    ): Notifications =
        try {
            val headers = buildHeaders(game, token, version, clientType)
            val url = "${game.baseURL}/gamer/api/listNotifications?status=NotificationStatusUnread&type=NotificationTypePopup&is_sort=true"
            val res = httpTransport.getIdempotent(url, headers)
            val data = res.json()
            val retcode = data.optInt("retcode", -999)
            val message = data.optString("message")
            if (res.code in 200..299 && (retcode == 0 || message == "OK")) {
                val arr = data.optJSONObject("data")?.optJSONArray("list")
                val ids = ArrayList<String>()
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        arr.optJSONObject(i)?.optString("id")?.takeIf { it.isNotEmpty() }?.let { ids.add(it) }
                    }
                }
                val detail =
                    "listNotifications ok: http=${res.code}, retcode=$retcode, " +
                        "message=$message, count=${ids.size}, version=$version, client_type=$clientType"
                Notifications(true, ids, detail)
            } else {
                val detail =
                    "listNotifications failed: http=${res.code}, retcode=$retcode, " +
                        "message=$message, version=$version, client_type=$clientType"
                Notifications(false, emptyList(), detail)
            }
        } catch (e: Exception) {
            e.throwIfCancellation()
            Log.w(TAG, "listNotifications failed: ${game.key}, version=$version", e)
            Notifications(false, emptyList(), e.message ?: "notifications exception")
        }

    private suspend fun ack(
        game: GameConfig,
        token: String,
        id: String,
        version: String,
        clientType: String,
    ): AckResult =
        try {
            val headers = buildHeaders(game, token, version, clientType)
            val res =
                httpTransport.postJson(
                    "${game.baseURL}/gamer/api/ackNotification",
                    headers,
                    JSONObject().put("id", id),
                )
            val data = res.json()
            val retcode = data.optInt("retcode", -999)
            val message = data.optString("message")
            if (res.code in 200..299 && (retcode == 0 || message == "OK")) {
                AckResult(true, "ackNotification(${maskNotificationId(id)}) ok")
            } else {
                val detail =
                    "ackNotification(${maskNotificationId(id)}) failed: http=${res.code}, " +
                        "retcode=$retcode, message=$message, version=$version, client_type=$clientType"
                // 非幂等确认 POST 已收到完整响应；保留失败信息，但不允许整套任务自动重发。
                AckResult(false, detail, TaskFailureClassifier.fromNonIdempotentResponse(res.code, retcode))
            }
        } catch (e: Exception) {
            e.throwIfCancellation()
            runCatching {
                Log.w(TAG, "ack failed: ${game.key}, id=${maskNotificationId(id)}, version=$version", e)
            }
            val failure = TaskFailureClassifier.fromException(e)
            AckResult(false, ErrorText.detailOf(e), failure)
        }


    private fun classifyApiFailure(
        httpCode: Int,
        retcode: Int,
    ): TaskFailureDescriptor =
        when {
            httpCode !in 200..299 -> TaskFailureClassifier.fromHttpCode(httpCode)
            retcode != 0 -> TaskFailureClassifier.fromRetcode(retcode)
            else -> TaskFailureDescriptor(FailureCategory.SERVER_ERROR, "missing-data", retryable = true)
        }

    private fun buildWalletDetail(
        httpCode: Int,
        retcode: Int,
        message: String,
        version: String,
        clientType: String,
        hasFreeTimeNode: Boolean,
        freeTime: Int,
        sentFreeTime: Int,
        totalTime: Int,
        playCard: String,
        coin: Int,
    ): String =
        "wallet/get ok: http=$httpCode, retcode=$retcode, message=$message, " +
            "version=$version, client_type=$clientType, hasFreeTime=$hasFreeTimeNode, " +
            "free=$freeTime, send=$sentFreeTime, total=$totalTime, playCard=$playCard, coin=$coin"

    private fun buildWalletFailureDetail(
        httpCode: Int,
        retcode: Int,
        message: String,
        version: String,
        clientType: String,
    ): String =
        "wallet/get failed: http=$httpCode, retcode=$retcode, message=$message, " +
            "version=$version, client_type=$clientType"

    private fun maskNotificationId(id: String): String =
        when {
            id.length <= 6 -> id
            else -> "***" + id.takeLast(6)
        }

    private fun formatMinutes(minutes: Int): String {
        if (minutes <= 0) return "0分钟"
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h > 0 && m > 0 -> "${h}小时${m}分钟"
            h > 0 -> "${h}小时"
            else -> "${m}分钟"
        }
    }
}
