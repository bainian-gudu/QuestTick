package com.questtick.sign

import com.questtick.log.AppLog
import com.questtick.core.throwIfCancellation
import com.questtick.data.FailureCategory
import com.questtick.net.HttpTransport
import com.questtick.net.getIdempotent
import com.questtick.net.postJson
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * 米游社签到引擎。
 *
 * 原神、崩坏：星穹铁道、绝区零使用各自 luna 接口；崩坏 2、崩坏 3 与未定事件簿使用通用
 * luna 接口。负责角色查询、奖励查询、签到请求与 act_id 刷新重试。
 */
class MysSignIn(
    private val deviceId: String,
    private val httpTransport: HttpTransport,
    /** act_id 疑似失效时自动抓取最新值并重试一次。 */
    private val actIdAutoRefresh: Boolean = false,
    /** act_id 缓存：gameKey -> 最新 act_id，由调用方负责持久化。 */
    private val actIdCache: MutableMap<String, String> = mutableMapOf(),
    /** act_id 刷新过程日志回调。 */
    private val onLog: (level: String, message: String) -> Unit = { _, _ -> },
    /** 本次请求使用的米游社 App 版本号；为空时使用内置默认值。 */
    private val customVersion: String = "",
    /** 多账号并行时保护 actIdCache 读写的 Mutex。 */
    private val cacheMutex: Mutex? = null,
    /** act_id 获取失败的应用级错误回调。 */
    private val recordError: ((feature: String, detail: String) -> Unit)? = null,
) {
    data class GameConfig(
        val key: String,
        val name: String,
        val gameBiz: String,
        val actId: String,
        val signgame: String?, // null 表示使用通用 luna 接口
        val defaultRegion: String,
        val actPage: String,
    )

    data class Reward(
        val day: Int,
        val name: String,
        val cnt: String,
        val icon: String = "",
    )

    data class Role(
        val gameUid: String,
        val region: String,
        val nickname: String,
    )

    sealed class RoleResult {
        data class Ok(
            val role: Role,
        ) : RoleResult()

        object NoRole : RoleResult()

        data class Failed(
            val message: String,
            val detail: String = "",
            val failure: TaskFailureDescriptor = TaskFailureDescriptor(FailureCategory.INTERNAL_ERROR),
        ) : RoleResult()
    }

    /** 单个 Cookie、单个游戏的签到结果。 */
    data class Outcome(
        val success: Boolean,
        val skipped: Boolean,
        val message: String,
        val alreadySigned: Boolean = false,
        val reward: Reward? = null,
        /** 详细日志使用的技术细节（retcode / exception）。 */
        val detail: String = "",
        val failure: TaskFailureDescriptor = TaskFailureDescriptor(FailureCategory.NONE),
    )

    companion object {
        private const val TAG = "MysSignIn"

        val GAMES: Map<String, GameConfig> =
            linkedMapOf(
                "Honkai2" to
                    GameConfig(
                        "Honkai2",
                        "崩坏学园2-米游社",
                        "bh2_cn",
                        "e202203291431091",
                        null,
                        "",
                        "https://webstatic.mihoyo.com/bbs/event/signin/bh2/index.html",
                    ),
                "Honkai3rd" to
                    GameConfig(
                        "Honkai3rd",
                        "崩坏3-米游社",
                        "bh3_cn",
                        "e202306201626331",
                        null,
                        "",
                        "https://webstatic.mihoyo.com/bbs/event/signin/bh3/index.html",
                    ),
                "TearsOfThemis" to
                    GameConfig(
                        "TearsOfThemis",
                        "未定事件簿-米游社",
                        "nxx_cn",
                        "e202202251749321",
                        null,
                        "",
                        "https://webstatic.mihoyo.com/bbs/event/signin/nxx/index.html",
                    ),
                "Genshin" to
                    GameConfig(
                        "Genshin",
                        "原神-米游社",
                        "hk4e_cn",
                        "e202311201442471",
                        "hk4e",
                        "cn_gf01",
                        "https://act.mihoyo.com/bbs/event/signin/hk4e/index.html",
                    ),
                "StarRail" to
                    GameConfig(
                        "StarRail",
                        "星穹铁道-米游社",
                        "hkrpg_cn",
                        "e202304121516551",
                        "hkrpg",
                        "prod_gf_cn",
                        "https://act.mihoyo.com/bbs/event/signin/hkrpg/index.html",
                    ),
                "ZZZ" to
                    GameConfig(
                        "ZZZ",
                        "绝区零-米游社",
                        "nap_cn",
                        "e202406242138391",
                        "zzz",
                        "prod_gf_cn",
                        "https://act.mihoyo.com/bbs/event/signin/zzz/index.html",
                    ),
            )

        private val ALREADY_SIGNED = Regex("已签到|已经签到|签到过|今日已签到|already", RegexOption.IGNORE_CASE)
    }

    private fun host(game: GameConfig): String = if (game.key == "ZZZ") Endpoints.ZZZ_HOST else Endpoints.WEB_HOST

    /** 当前生效的 act_id：优先使用动态缓存，缺失时回退到内置默认值。 */
    private fun currentActId(game: GameConfig): String = actIdCache[game.key]?.takeIf { ActId.isValid(it) } ?: game.actId

    // 角色查询。

    suspend fun getRole(
        cookie: String,
        game: GameConfig,
    ): RoleResult {
        val query = Ds.sortedQueryString("game_biz=${game.gameBiz}")
        val ds = Ds.generateWeb()
        val headers = MysHeaders.role(cookie, deviceId, customVersion, ds)
        return try {
            val res =
                httpTransport.getIdempotent(
                    "https://${Endpoints.WEB_HOST}/binding/api/getUserGameRolesByCookie?$query",
                    headers,
                )
            val data = res.json()
            val retcode = data.optInt("retcode", -999)
            if (res.code !in 200..299 || retcode != 0) {
                val friendly =
                    if (res.code !in 200..299) {
                        "角色查询服务暂时不可用，请稍后重试"
                    } else {
                        ErrorText.fromRetcode(retcode) ?: "登录校验失败，请检查 Cookie 是否有效"
                    }
                AppLog.w(TAG, "getRole failed: ${game.key}, http=${res.code}, retcode=$retcode")
                return RoleResult.Failed(
                    message = friendly,
                    detail =
                        "getUserGameRolesByCookie http=${res.code}, retcode=$retcode, " +
                            "message=${data.optString("message")}",
                    failure =
                        if (res.code !in 200..299) {
                            TaskFailureClassifier.fromHttpCode(res.code)
                        } else {
                            TaskFailureClassifier.fromRetcode(retcode)
                        },
                )
            }
            val list = data.optJSONObject("data")?.optJSONArray("list")
            val first = if (list != null && list.length() > 0) list.optJSONObject(0) else null
            val uid = first?.optString("game_uid").orEmpty()
            if (first == null || uid.isEmpty()) return RoleResult.NoRole
            RoleResult.Ok(
                Role(
                    gameUid = uid,
                    region = first.optString("region"),
                    nickname = first.optString("nickname"),
                ),
            )
        } catch (e: Exception) {
            e.throwIfCancellation()
            AppLog.w(TAG, "getRole exception: ${game.key}", e)
            RoleResult.Failed(
                message = "查询角色信息时${ErrorText.fromException(e)}",
                detail = Mask.sensitive(ErrorText.detailOf(e)),
                failure = TaskFailureClassifier.fromException(e),
            )
        }
    }

    // 签到请求。

    private fun signUrl(game: GameConfig): String =
        if (game.signgame != null) {
            "https://${host(game)}/event/luna/${game.signgame}/sign"
        } else {
            "https://${host(game)}/event/luna/sign"
        }

    private fun signHeaders(
        cookie: String,
        game: GameConfig,
        ds: String,
    ): Map<String, String> =
        if (game.signgame != null) {
            MysHeaders.sign(cookie, deviceId, customVersion, ds, signgame = game.signgame).toMutableMap().apply {
                put("Host", host(game))
            }
        } else {
            val referer =
                "${game.actPage}?bbs_auth_required=true&act_id=${currentActId(game)}" +
                    "&bbs_presentation_style=fullscreen&utm_source=bbs&utm_medium=mys&utm_campaign=icon"
            MysHeaders.sign(cookie, deviceId, customVersion, ds, referer = referer, origin = "https://webstatic.mihoyo.com").toMutableMap().apply {
                put("Host", host(game))
            }
        }

    /** SignResult：ok 表示成功或今日已签；already 表示今天此前已经签到。 */
    data class SignResult(
        val ok: Boolean,
        val already: Boolean,
        val message: String,
        val detail: String = "",
        val failure: TaskFailureDescriptor = TaskFailureDescriptor(FailureCategory.NONE),
    )

    suspend fun signIn(
        cookie: String,
        game: GameConfig,
        role: Role,
        retryOnActIdInvalid: Boolean = true,
    ): SignResult {
        val region = role.region.ifEmpty { game.defaultRegion }
        if (region.isEmpty()) {
            return SignResult(
                ok = false,
                already = false,
                message = "缺少游戏服务器（region）信息，无法签到",
                failure = TaskFailureDescriptor(FailureCategory.CONFIGURATION_ERROR),
            )
        }

        val actId = currentActId(game)
        // 按 key 字母序构造 body，确保 DS 签名中的 body 与 HTTP 发送的 body 完全一致
        val body =
            JSONObject().apply {
                put("act_id", actId)
                put("lang", "zh-cn")
                put("region", region)
                put("uid", role.gameUid)
            }
        val ds = Ds.generateWeb()

        return try {
            val res = httpTransport.postJson(signUrl(game), signHeaders(cookie, game, ds), body)
            val data = res.json()
            val message = data.optString("message", "Unknown")
            val retcode = data.optInt("retcode", -999)
            // 通用 luna 接口中 data.success == 1 表示需要验证码。
            val httpSuccess = res.code in 200..299
            val captchaRequired = httpSuccess && data.optJSONObject("data")?.optInt("success", 0) == 1
            if (captchaRequired) {
                return SignResult(
                    ok = false,
                    already = false,
                    message = "触发风控验证码，请前往米游社 App 手动签到一次后再试",
                    detail = "luna sign captcha required (data.success == 1), retcode=$retcode",
                    failure = TaskFailureDescriptor(FailureCategory.CAPTCHA_REQUIRED, "retcode:$retcode"),
                )
            }
            // -5003 是官方“今日已签到”错误码，按已签到处理。
            val already = httpSuccess && (retcode == -5003 || ALREADY_SIGNED.containsMatchIn(message))
            when {
                already -> SignResult(true, true, "今日已签到")
                httpSuccess && (message == "OK" || retcode == 0) -> SignResult(true, false, "签到成功")
                else -> {
                    // act_id 疑似失效时尝试动态刷新并重试一次。
                    if (httpSuccess &&
                        retryOnActIdInvalid &&
                        actIdAutoRefresh &&
                        ActIdInvalid.isInvalid(retcode, message)
                    ) {
                        val retryResult = refreshActIdAndRetry(cookie, game, role, actId)
                        if (retryResult != null) return retryResult
                    }
                    SignResult(
                        ok = false,
                        already = false,
                        message =
                            if (res.code !in 200..299) {
                                "签到服务暂时不可用，请稍后重试"
                            } else {
                                ErrorText.fromRetcode(retcode) ?: "签到失败：$message"
                            },
                        detail = "luna sign http=${res.code}, retcode=$retcode, message=$message, act_id=$actId",
                        // 签到 POST 已获得完整服务端响应，但仍属于非幂等操作；除上方明确的 act_id 刷新外，
                        // HTTP 或业务错误都不能交给 Worker 自动重发。
                        failure = TaskFailureClassifier.fromNonIdempotentResponse(res.code, retcode),
                    )
                }
            }
        } catch (e: Exception) {
            e.throwIfCancellation()
            val failure = TaskFailureClassifier.fromException(e)
            SignResult(
                ok = false,
                already = false,
                message =
                    if (failure.category == FailureCategory.RESULT_UNKNOWN) {
                        "签到结果待确认：为避免重复请求，今日不会自动重试"
                    } else {
                        "签到时${ErrorText.fromException(e)}"
                    },
                detail = Mask.sensitive(ErrorText.detailOf(e)),
                failure = failure,
            )
        }
    }

    /**
     * 刷新 act_id 并重试一次签到。
     *
     * 获取失败、获取到的值与当前值相同，或重试本身再次失败时不再继续递归，避免死循环。
     * @return 发起重试时返回重试结果，未重试时返回 null。
     */
    private suspend fun refreshActIdAndRetry(
        cookie: String,
        game: GameConfig,
        role: Role,
        staleActId: String,
    ): SignResult? {
        onLog("WARN", "[${game.name}] act_id 疑似失效（当前 $staleActId），尝试自动获取最新 act_id…")

        val latest =
            ActId.fetchLatest(game, httpTransport) { detail ->
                recordError?.invoke("ACT_ID 自动刷新", detail)
            }
        if (latest == null) {
            onLog("WARN", "[${game.name}] 未能获取最新 act_id，跳过重试")
            return null
        }
        if (latest == staleActId) {
            onLog("WARN", "[${game.name}] 获取到的 act_id 与当前相同，跳过重试")
            return null
        }

        // Mutex 保护并发写
        if (cacheMutex != null) {
            cacheMutex.withLock { actIdCache[game.key] = latest }
        } else {
            actIdCache[game.key] = latest
        }
        onLog("INFO", "[${game.name}] 已获取最新 act_id=$latest，重试签到…")
        return signIn(cookie, game, role, retryOnActIdInvalid = false)
    }

    // 奖励查询。

    private fun infoUrl(game: GameConfig): String =
        if (game.signgame != null) {
            "https://${host(game)}/event/luna/${game.signgame}/info"
        } else {
            "https://${host(game)}/event/luna/info"
        }

    private fun homeUrl(game: GameConfig): String =
        if (game.signgame != null) {
            "https://${host(game)}/event/luna/${game.signgame}/home"
        } else {
            "https://${host(game)}/event/luna/home"
        }

    suspend fun getReward(
        cookie: String,
        game: GameConfig,
        role: Role,
    ): Reward? {
        val region = role.region.ifEmpty { game.defaultRegion }
        if (region.isEmpty()) return null
        return try {
            val actId = currentActId(game)
            val infoQuery = Ds.sortedQueryString("act_id=$actId&region=$region&uid=${role.gameUid}&lang=zh-cn")
            // 通用 luna /home 不接受 region / uid，单游戏 /home 才需要携带。
            val homeQuery = if (game.signgame != null) infoQuery else Ds.sortedQueryString("act_id=$actId&lang=zh-cn")

            val infoDs = Ds.generateWeb()
            val infoData = httpTransport.getIdempotent("${infoUrl(game)}?$infoQuery", signHeaders(cookie, game, infoDs)).json()
            val homeDs = Ds.generateWeb()
            val homeData = httpTransport.getIdempotent("${homeUrl(game)}?$homeQuery", signHeaders(cookie, game, homeDs)).json()

            val infoRetcode = infoData.optInt("retcode", -999)
            val homeRetcode = homeData.optInt("retcode", -999)
            if (infoRetcode != 0 || homeRetcode != 0) {
                AppLog.w(TAG, "getReward failed: ${game.key}, infoRetcode=$infoRetcode, homeRetcode=$homeRetcode")
                onLog(
                    "ERROR",
                    "[${game.name}] 奖励查询失败：infoRetcode=$infoRetcode, infoMessage=${infoData.optString("message")}, " +
                        "homeRetcode=$homeRetcode, homeMessage=${homeData.optString("message")}",
                )
                return null
            }

            val infoObject = infoData.optJSONObject("data")
            val homeObject = homeData.optJSONObject("data")
            val totalSignDay =
                firstPositiveInt(infoObject, "total_sign_day", "totalSignDay", "sign_day", "signDay")
                    ?: firstPositiveInt(homeObject, "total_sign_day", "totalSignDay", "sign_day", "signDay")
                    ?: 0
            val awards =
                homeObject?.optJSONArray("awards")
                    ?: homeObject?.optJSONArray("award_list")
                    ?: infoObject?.optJSONArray("awards")
            if (totalSignDay <= 0 || awards == null || awards.length() == 0) {
                onLog(
                    "ERROR",
                    "[${game.name}] 奖励数据缺失：totalSignDay=$totalSignDay, awards=${awards?.length() ?: 0}",
                )
                return null
            }

            val award = awards.optJSONObject(totalSignDay - 1)
            if (award == null) {
                onLog("ERROR", "[${game.name}] 奖励数据缺失：无法读取第${totalSignDay}天奖励")
                return null
            }
            val icon = normalizeRewardIcon(extractRewardIcon(award))
            if (icon.isBlank()) {
                onLog("ERROR", "[${game.name}] 奖励图片地址缺失：$award")
            }
            Reward(
                day = totalSignDay,
                name = award.optString("name"),
                cnt = award.optString("cnt"),
                icon = icon,
            )
        } catch (e: Exception) {
            e.throwIfCancellation()
            AppLog.w(TAG, "getReward exception: ${game.key}", e)
            onLog("ERROR", "[${game.name}] 奖励查询异常：${ErrorText.detailOf(e)}")
            null
        }
    }

    private fun firstPositiveInt(
        json: JSONObject?,
        vararg keys: String,
    ): Int? {
        if (json == null) return null
        keys.forEach { key ->
            val value = json.optInt(key, 0)
            if (value > 0) return value
        }
        return null
    }

    private fun normalizeRewardIcon(raw: String): String {
        val value = raw.trim()
        return when {
            value.startsWith("//") -> "https:$value"
            value.startsWith("/") -> "https://webstatic.mihoyo.com$value"
            else -> value
        }
    }

    private fun extractRewardIcon(json: JSONObject): String {
        val directKeys = listOf("icon", "icon_url", "iconUrl", "reward_icon", "rewardIcon", "image", "image_url", "imageUrl")
        directKeys.firstNotNullOfOrNull { key -> json.optString(key).trim().takeIf { it.isNotBlank() } }?.let { return it }
        listOf("award", "reward", "item").forEach { key ->
            json.optJSONObject(key)?.let { nested ->
                directKeys.firstNotNullOfOrNull { name -> nested.optString(name).trim().takeIf { it.isNotBlank() } }?.let { return it }
            }
        }
        return ""
    }

    /** 执行单个 Cookie、单个游戏的完整流程：查角色、查奖励并提交签到。 */
    suspend fun runForCookie(
        cookie: String,
        game: GameConfig,
    ): Outcome {
        when (val rr = getRole(cookie, game)) {
            is RoleResult.NoRole -> return Outcome(
                success = true,
                skipped = true,
                message = "未注册该游戏，已跳过签到",
                detail = "getUserGameRolesByCookie returned no role for game_biz=${game.gameBiz}",
                failure = TaskFailureDescriptor(FailureCategory.NO_ROLE),
            )
            is RoleResult.Failed -> return Outcome(
                success = false,
                skipped = false,
                message = rr.message,
                detail = rr.detail,
                failure = rr.failure,
            )
            is RoleResult.Ok -> {
                val role = rr.role
                val sr = signIn(cookie, game, role)
                if (!sr.ok) {
                    return Outcome(
                        success = false,
                        skipped = false,
                        message = sr.message,
                        detail = sr.detail,
                        failure = sr.failure,
                    )
                }
                val reward = getReward(cookie, game, role)
                val base = if (sr.already) "今日已签到" else "签到成功"
                val display =
                    if (reward != null) {
                        "$base · 第${reward.day}天 · ${reward.name} ×${reward.cnt}"
                    } else {
                        base
                    }
                return Outcome(
                    success = true,
                    skipped = false,
                    message = display,
                    alreadySigned = sr.already,
                    reward = reward,
                )
            }
        }
    }
}
