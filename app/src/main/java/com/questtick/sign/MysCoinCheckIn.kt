package com.questtick.sign

import com.questtick.core.throwIfCancellation
import com.questtick.net.HttpTransport
import com.questtick.net.getIdempotent
import com.questtick.net.postJson
import kotlinx.coroutines.delay
import org.json.JSONObject

/** 固定使用原神社区（gids=2）执行每日米游币打卡。 */
internal object MysCoinCheckIn {
    const val GAME_KEY = "MysCoin"
    const val DISPLAY_NAME = "原神社区米游币打卡"
    private const val SIGN_URL = "https://bbs-api.miyoushe.com/apihub/app/api/signIn"
    private const val STATE_URL = "https://bbs-api.miyoushe.com/apihub/wapi/getUserMissionsState?point_sn=myb"
    private const val GENSHIN_GIDS = "2"
    private val ALREADY_DONE = Regex("已.*(签到|打卡)|重复|already", RegexOption.IGNORE_CASE)

    data class Outcome(
        val success: Boolean,
        val alreadyDone: Boolean,
        val message: String,
        val detail: String,
        val coinBalance: Int = -1,
        val coinGained: Int = -1,
        val rewardIcon: String = "",
        val signDay: Int = 0,
    )

    private data class CoinState(
        val balance: Int,
        val receivedToday: Int,
        val signDay: Int,
        val rewardIcon: String,
    )

    private data class StateRead(
        val state: CoinState?,
        val detail: String = "",
    )

    suspend fun run(
        cookie: String,
        deviceId: String,
        appVersion: String,
        httpTransport: HttpTransport,
    ): Outcome {
        val body = JSONObject().put("gids", GENSHIN_GIDS)
        val bodyText = body.toString()
        return try {
            val beforeRead = fetchState(cookie, httpTransport)
            val before = beforeRead.state
            val response =
                httpTransport.postJson(
                    SIGN_URL,
                    MysHeaders.coinCheckIn(cookie, deviceId, appVersion, Ds.generateX6(body = bodyText)),
                    body,
                )
            val json = response.json()
            val retcode = json.optInt("retcode", -999)
            val serverMessage = json.optString("message").ifBlank { "未知错误" }
            val httpSuccess = response.code in 200..299
            val already = httpSuccess && ALREADY_DONE.containsMatchIn(serverMessage)
            if (httpSuccess && (retcode == 0 || already)) delay(400)
            val afterRead = fetchState(cookie, httpTransport)
            val after = afterRead.state
            val balance = after?.balance ?: -1
            val gained =
                if (before != null && after != null) {
                    (after.receivedToday - before.receivedToday).coerceAtLeast(0)
                } else {
                    -1
                }
            val stateDetail =
                "beforeBalance=${before?.balance ?: "unknown"}, beforeReceived=${before?.receivedToday ?: "unknown"}, " +
                    "afterBalance=${after?.balance ?: "unknown"}, afterReceived=${after?.receivedToday ?: "unknown"}, " +
                    "beforeState=${beforeRead.detail.ifBlank { "ok" }}, afterState=${afterRead.detail.ifBlank { "ok" }}"
            when {
                httpSuccess && retcode == 0 ->
                    Outcome(
                        true,
                        false,
                        if (balance >= 0 && gained >= 0) "原神社区打卡成功，获得 $gained 米游币，余额 $balance" else "原神社区打卡成功，米游币数量暂时无法读取",
                        detail(response.code, retcode, serverMessage) + ", $stateDetail",
                        balance,
                        gained,
                        after?.rewardIcon.orEmpty(),
                        after?.signDay ?: 0,
                    )
                already ->
                    Outcome(
                        true,
                        true,
                        if (balance >= 0) "原神社区今日已打卡，米游币余额 $balance" else "原神社区今日已打卡",
                        detail(response.code, retcode, serverMessage) + ", $stateDetail",
                        balance,
                        if (gained >= 0) gained else 0,
                        after?.rewardIcon.orEmpty(),
                        after?.signDay ?: 0,
                    )
                else ->
                    Outcome(
                        success = false,
                        alreadyDone = false,
                        message = ErrorText.fromRetcode(retcode) ?: "原神社区打卡失败：$serverMessage",
                        detail = detail(response.code, retcode, serverMessage) + ", $stateDetail",
                        coinBalance = balance,
                        coinGained = gained,
                        rewardIcon = after?.rewardIcon.orEmpty(),
                        signDay = after?.signDay ?: 0,
                    )
            }
        } catch (e: Exception) {
            e.throwIfCancellation()
            Outcome(
                success = false,
                alreadyDone = false,
                message = "原神社区打卡${ErrorText.fromException(e)}",
                detail = ErrorText.detailOf(e),
            )
        }
    }

    private suspend fun fetchState(
        cookie: String,
        httpTransport: HttpTransport,
    ): StateRead =
        try {
            val response = httpTransport.getIdempotent(STATE_URL, MysHeaders.coinState(cookie))
            val json = response.json()
            val retcode = json.optInt("retcode", -999)
            val message = json.optString("message").ifBlank { "未知错误" }
            if (response.code !in 200..299 || retcode != 0) {
                StateRead(null, "http=${response.code}, retcode=$retcode, message=$message")
            } else {
                val data = json.optJSONObject("data")
                val state =
                    data?.let {
                        CoinState(
                            balance = it.optInt("total_points", -1),
                            receivedToday = it.optInt("already_received_points", -1),
                            signDay = firstPositiveInt(it, "sign_day", "signDay", "total_sign_day", "totalSignDay", "continuous_days", "consecutive_days") ?: 0,
                            rewardIcon = normalizeRewardIcon(
                                it.optString("icon").ifBlank {
                                    it.optString("reward_icon").ifBlank { it.optString("rewardIcon") }
                                },
                            ),
                        ).takeIf { value -> value.balance >= 0 && value.receivedToday >= 0 }
                    }
                StateRead(state, if (state == null) "http=${response.code}, retcode=$retcode, message=缺少有效余额字段" else "")
            }
        } catch (e: Exception) {
            e.throwIfCancellation()
            StateRead(null, ErrorText.detailOf(e))
        }

    private fun detail(httpCode: Int, retcode: Int, message: String): String =
        "miyoubi check-in http=$httpCode, retcode=$retcode, message=$message, gids=$GENSHIN_GIDS"

    private fun firstPositiveInt(json: JSONObject, vararg keys: String): Int? =
        keys.firstNotNullOfOrNull { key -> json.optInt(key, 0).takeIf { it > 0 } }

    private fun normalizeRewardIcon(raw: String): String {
        val value = raw.trim()
        return when {
            value.startsWith("//") -> "https:$value"
            value.startsWith("/") -> "https://webstatic.mihoyo.com$value"
            else -> value
        }
    }
}
