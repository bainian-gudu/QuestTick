package com.questtick.sign

import com.questtick.BuildConfig
import com.questtick.core.throwIfCancellation
import com.questtick.net.HttpTransport
import com.questtick.net.getIdempotent
import com.questtick.net.postJson
import kotlinx.coroutines.delay
import org.json.JSONObject

/** 固定使用原神社区（gids=2）执行每日米游币打卡。 */
internal object MysCoinCheckIn {
    /** 米游币功能暂仅在 Debug 版开放，正式版保留数据但不展示、不执行。 */
    val featureEnabled: Boolean get() = BuildConfig.DEBUG
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
        val signDay: Int = 0,
    )

    private data class CoinState(
        val balance: Int,
        val receivedToday: Int?,
        val signDay: Int,
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
        stoken: String = "",
        mid: String = "",
        uid: String = "",
    ): Outcome {
        val body = JSONObject().put("gids", GENSHIN_GIDS)
        val bodyText = body.toString()
        // 米游币 App 接口使用独立的 SToken Cookie；不要把普通 cookie_token/ltoken 混入，
        // 否则部分服务端会按冲突的登录态返回 -100。
        val coinCookie = appAuthCookie(cookie, stoken, mid, uid)
        if (coinCookie.isNullOrBlank()) {
            return Outcome(
                success = false,
                alreadyDone = false,
                message = "米游币打卡凭证不完整，无法形成一致的 Cookie 组合",
                detail = "缺少完整 V2 或 V1 认证字段；未发送签到请求",
            )
        }
        return try {
            val beforeRead = fetchState(coinCookie, httpTransport)
            val before = beforeRead.state
            val response =
                httpTransport.postJson(
                    SIGN_URL,
                    MysHeaders.coinCheckIn(coinCookie, deviceId, appVersion, Ds.generateX6(body = bodyText)),
                    body,
                )
            val json = response.json()
            val retcode = json.optInt("retcode", -999)
            val serverMessage = json.optString("message").ifBlank { "未知错误" }
            val httpSuccess = response.code in 200..299
            val already = httpSuccess && ALREADY_DONE.containsMatchIn(serverMessage)
            if (httpSuccess && (retcode == 0 || already)) delay(400)
            val afterRead = fetchState(coinCookie, httpTransport)
            val after = afterRead.state
            val balance = after?.balance ?: -1
            val gained =
                if (before != null && after != null) {
                    if (after.receivedToday != null && before.receivedToday != null) {
                        (after.receivedToday - before.receivedToday).coerceAtLeast(0)
                    } else {
                        (after.balance - before.balance).coerceAtLeast(0)
                    }
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
                        success = true,
                        alreadyDone = false,
                        message = if (balance >= 0 && gained >= 0) "原神社区打卡成功，获得 $gained 米游币，余额 $balance" else "原神社区打卡成功，米游币数量暂时无法读取",
                        detail = detail(response.code, retcode, serverMessage) + ", $stateDetail",
                        coinBalance = balance,
                        coinGained = gained,
                        signDay = after?.signDay ?: 0,
                    )
                already ->
                    Outcome(
                        success = true,
                        alreadyDone = true,
                        message = if (balance >= 0) "原神社区今日已打卡，米游币余额 $balance" else "原神社区今日已打卡",
                        detail = detail(response.code, retcode, serverMessage) + ", $stateDetail",
                        coinBalance = balance,
                        coinGained = if (gained >= 0) gained else 0,
                        signDay = after?.signDay ?: 0,
                    )
                else ->
                    Outcome(
                        success = false,
                        alreadyDone = false,
                        message = ErrorText.fromRetcode(retcode) ?: "原神社区打卡失败：$serverMessage",
                        detail = detail(response.code, retcode, serverMessage) + ", $stateDetail",
                        coinBalance = balance,
                        coinGained = gained,
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
                val data = json.optJSONObject("data") ?: json
                val state =
                    data.let {
                        val balance = findInt(it, "total_points", "totalPoints", "coin_balance", "coinBalance", "balance", "points")
                        val receivedToday = findInt(it, "already_received_points", "alreadyReceivedPoints", "received_today", "receivedToday", "today_received_points")
                        CoinState(
                            balance = balance ?: -1,
                            receivedToday = receivedToday,
                            signDay = firstPositiveInt(it, "sign_day", "signDay", "total_sign_day", "totalSignDay", "continuous_days", "consecutive_days") ?: 0,
                        ).takeIf { value -> value.balance >= 0 }
                    }
                StateRead(state, if (state == null) "http=${response.code}, retcode=$retcode, message=$message, 缺少有效余额字段" else "")
            }
        } catch (e: Exception) {
            e.throwIfCancellation()
                StateRead(null, ErrorText.detailOf(e))
        }

    private fun detail(httpCode: Int, retcode: Int, message: String): String =
        "miyoubi check-in http=$httpCode, retcode=$retcode, message=$message, gids=$GENSHIN_GIDS"

    private fun firstPositiveInt(json: JSONObject, vararg keys: String): Int? =
        keys.firstNotNullOfOrNull { key -> findInt(json, key)?.takeIf { it > 0 } }

    private fun findInt(json: JSONObject, vararg keys: String): Int? {
        val wanted = keys.toSet()
        fun scan(value: Any?, depth: Int): Int? {
            if (depth > 3 || value == null) return null
            if (value is JSONObject) {
                wanted.forEach { key ->
                    if (value.has(key) && !value.isNull(key)) {
                        value.optInt(key, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }?.let { return it }
                    }
                }
                val iterator = value.keys()
                while (iterator.hasNext()) scan(value.opt(iterator.next()), depth + 1)?.let { return it }
            } else if (value is org.json.JSONArray) {
                for (index in 0 until value.length()) scan(value.opt(index), depth + 1)?.let { return it }
            }
            return null
        }
        return scan(json, 0)
    }

    /**
     * 构造单一版本的 App Cookie。V2/V1 不做字段级混合，也不带网页 cookie_token。
     * 完整 V2 优先，其次完整 V1；无法形成完整组合时返回 null。
     */
    private fun appAuthCookie(
        cookie: String,
        stoken: String,
        mid: String,
        uid: String,
    ): String? {
        val source = linkedMapOf<String, String>()
        cookie.split(';').forEach { part ->
            val separator = part.indexOf('=')
            if (separator > 0) {
                source[part.substring(0, separator).trim().lowercase()] = part.substring(separator + 1).trim()
            }
        }
        fun value(vararg names: String): String = names.firstNotNullOfOrNull { source[it] }.orEmpty()
        fun sameIdentity(first: String, second: String): Boolean = first.isBlank() || second.isBlank() || first == second

        val v2Stoken = value("stoken_v2").ifBlank { stoken.trim() }
        val v2Mid = value("mid", "stmid_v2").ifBlank { mid.trim() }
        val v2Ltoken = value("ltoken_v2")
        val v2Ltmid = value("ltmid_v2")
        val v2Account = value("account_id_v2").ifBlank { uid.trim() }
        val v2Complete =
            v2Stoken.isNotBlank() && v2Mid.isNotBlank() && v2Ltoken.isNotBlank() &&
                v2Ltmid.isNotBlank() && v2Account.isNotBlank() &&
                sameIdentity(v2Account, uid.trim())
        if (v2Complete) {
            return listOf(
                "stoken_v2=$v2Stoken",
                "mid=$v2Mid",
                "ltoken_v2=$v2Ltoken",
                "ltmid_v2=$v2Ltmid",
                "account_id_v2=$v2Account",
            ).joinToString("; ")
        }

        val v1Stoken = value("stoken").ifBlank { stoken.trim() }
        val v1Mid = value("mid", "stmid").ifBlank { mid.trim() }
        val v1Ltoken = value("ltoken")
        val v1Stuid = value("stuid").ifBlank { uid.trim() }
        val v1Account = value("account_id").ifBlank { uid.trim() }
        val v1Complete =
            v1Stoken.isNotBlank() && v1Mid.isNotBlank() && v1Ltoken.isNotBlank() &&
                v1Stuid.isNotBlank() && v1Account.isNotBlank() &&
                sameIdentity(v1Stuid, v1Account) && sameIdentity(v1Account, uid.trim())
        if (v1Complete) {
            return listOf(
                "stoken=$v1Stoken",
                "mid=$v1Mid",
                "stuid=$v1Stuid",
                "ltoken=$v1Ltoken",
                "account_id=$v1Account",
            ).joinToString("; ")
        }
        return null
    }
}
