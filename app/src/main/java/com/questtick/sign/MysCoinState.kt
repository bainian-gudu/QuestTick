@file:Suppress(
    "detekt:TooGenericExceptionCaught",
)

package com.questtick.sign

/*
 * 米游币打卡的状态读取：请求任务/余额接口，并从返回 JSON 里扫描出余额、今日已获取与签到天数。
 */
import com.questtick.core.throwIfCancellation
import com.questtick.net.HttpTransport
import com.questtick.net.getIdempotent
import org.json.JSONObject

private const val STATE_URL = "https://bbs-api.miyoushe.com/apihub/sapi/getUserMissionsState?point_sn=myb"
private const val DEFAULT_RETCODE = -999
private const val HTTP_SUCCESS_MIN = 200
private const val HTTP_SUCCESS_MAX = 299

/** JSON 深层扫描的最大深度：接口在不同版本里把数据嵌套在不同层级。 */
private const val STATE_SCAN_MAX_DEPTH = 3

/** 打卡前后的米游币状态快照。 */
internal data class CoinState(
    val balance: Int,
    val receivedToday: Int?,
    val signDay: Int,
)

/** 一次状态读取的结果；[detail] 只在失败时携带诊断信息。 */
internal data class StateRead(
    val state: CoinState?,
    val detail: String = "",
)

internal object MysCoinState {
    suspend fun read(
        cookie: String,
        deviceId: String,
        appVersion: String,
        httpTransport: HttpTransport,
    ): StateRead =
        try {
            // 任务/余额接口是 App 读接口：必须带移动端 DS（md5_v1 + 社区 salt），否则一律 -100。
            val response =
                httpTransport.getIdempotent(
                    STATE_URL,
                    MysHeaders.coinState(cookie, deviceId, appVersion, Ds.generateBbs()),
                )
            val json = response.json()
            val retcode = json.optInt("retcode", DEFAULT_RETCODE)
            val message = json.optString("message").ifBlank { "未知错误" }
            if (response.code !in HTTP_SUCCESS_MIN..HTTP_SUCCESS_MAX || retcode != 0) {
                StateRead(null, "http=${response.code}, retcode=$retcode, message=$message")
            } else {
                val data = json.optJSONObject("data") ?: json
                val state =
                    data.let {
                        val balance =
                            findInt(it, "total_points", "totalPoints", "coin_balance", "coinBalance", "balance", "points")
                        val receivedToday =
                            findInt(
                                it,
                                "already_received_points",
                                "alreadyReceivedPoints",
                                "received_today",
                                "receivedToday",
                                "today_received_points",
                            )
                        CoinState(
                            balance = balance ?: -1,
                            receivedToday = receivedToday,
                            signDay =
                                firstPositiveInt(
                                    it,
                                    "sign_day",
                                    "signDay",
                                    "total_sign_day",
                                    "totalSignDay",
                                    "continuous_days",
                                    "consecutive_days",
                                ) ?: 0,
                        ).takeIf { value -> value.balance >= 0 }
                    }
                StateRead(
                    state,
                    if (state == null) {
                        "http=${response.code}, retcode=$retcode, message=$message, 缺少有效余额字段"
                    } else {
                        ""
                    },
                )
            }
        } catch (e: Exception) {
            e.throwIfCancellation()
            StateRead(null, ErrorText.detailOf(e))
        }

    private fun firstPositiveInt(
        json: JSONObject,
        vararg keys: String,
    ): Int? = keys.firstNotNullOfOrNull { key -> findInt(json, key)?.takeIf { it > 0 } }

    private fun findInt(
        json: JSONObject,
        vararg keys: String,
    ): Int? {
        val wanted = keys.toSet()

        fun scan(
            value: Any?,
            depth: Int,
        ): Int? =
            if (depth > STATE_SCAN_MAX_DEPTH || value == null) {
                null
            } else {
                when (value) {
                    is JSONObject -> {
                        val direct =
                            wanted.firstNotNullOfOrNull { key ->
                                if (value.has(key) && !value.isNull(key)) {
                                    value.optInt(key, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }
                                } else {
                                    null
                                }
                            }
                        direct
                            ?: value
                                .keys()
                                .asSequence()
                                .firstNotNullOfOrNull { key -> scan(value.opt(key), depth + 1) }
                    }

                    is org.json.JSONArray -> {
                        (0 until value.length()).firstNotNullOfOrNull { index -> scan(value.opt(index), depth + 1) }
                    }

                    else -> {
                        null
                    }
                }
            }

        return scan(json, 0)
    }
}
