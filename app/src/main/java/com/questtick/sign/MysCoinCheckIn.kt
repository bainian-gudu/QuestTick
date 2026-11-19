@file:Suppress(
    "detekt:CyclomaticComplexMethod",
    "detekt:LongMethod",
    "detekt:LongParameterList",
    "detekt:TooGenericExceptionCaught",
)

package com.questtick.sign

/* 米游社社区打卡（米游币）：发起打卡请求，并对比打卡前后的余额以计算本次获取量。
 * 是否启用由调用方按设置快照判定（见 mysCoinFeatureEnabled）。 */

import com.questtick.core.throwIfCancellation
import com.questtick.data.DEFAULT_MYS_COIN_FORUMS
import com.questtick.net.HttpResponse
import com.questtick.net.HttpTransport
import com.questtick.net.getIdempotent
import com.questtick.net.postJson
import kotlinx.coroutines.delay
import org.json.JSONObject

/** 米游社社区打卡：按所选社区逐个打卡，并对比打卡前后的余额以计算本次获取量。 */
internal object MysCoinCheckIn {
    const val GAME_KEY = "MysCoin"
    const val DISPLAY_NAME = "米游社社区米游币打卡"

    /** 可打卡的米游社社区：gids 与米游社 App 一致。 */
    data class CoinForum(
        val id: String,
        val name: String,
    )

    /** 社区顺序固定，界面上按此顺序展示，与米游社 App 的板块顺序一致。 */
    val FORUMS: List<CoinForum> =
        listOf(
            CoinForum("1", "崩坏3"),
            CoinForum("2", "原神"),
            CoinForum("3", "崩坏学园2"),
            CoinForum("4", "未定事件簿"),
            CoinForum("5", "大别野"),
            CoinForum("6", "崩坏：星穹铁道"),
            CoinForum("8", "绝区零"),
            CoinForum("9", "崩坏：因缘精灵"),
            CoinForum("10", "星布谷地"),
        )

    /**
     * 过滤未知 gids 并按固定顺序去重。
     *
     * 空集合（旧配置或用户把开关点到没有）回退到默认社区，避免打卡任务什么都没做。
     */
    fun sanitizeForums(ids: Set<String>): List<CoinForum> {
        val known = FORUMS.filter { it.id in ids }
        return known.ifEmpty { FORUMS.filter { it.id in DEFAULT_MYS_COIN_FORUMS } }
    }

    private const val SIGN_URL = "https://bbs-api.miyoushe.com/apihub/app/api/signIn"

    // App 客户端走 sapi（wapi 是网页端，缺移动端 DS 时会直接判登录失效）。
    private const val DEFAULT_RETCODE = -999
    private const val HTTP_SUCCESS_MIN = 200
    private const val HTTP_SUCCESS_MAX = 299
    private const val BALANCE_REFRESH_DELAY_MS = 400L

    /** 多个社区之间的间隔：连续打卡太快更容易触发社区风控。 */
    private const val FORUM_INTERVAL_DELAY_MS = 1_500L

    /** 社区打卡“今日已完成”的错误码：App 接口返回 1008，网页接口返回 -5003。 */
    private const val RETCODE_ALREADY_DONE_APP = 1008
    private const val RETCODE_ALREADY_DONE_WEB = -5003
    private val ALREADY_DONE_RETCODES = setOf(RETCODE_ALREADY_DONE_APP, RETCODE_ALREADY_DONE_WEB)

    /** 社区打卡触发极验验证码；App 内无解，只能提示用户在米游社里手动完成。 */
    private const val RETCODE_CAPTCHA_REQUIRED = 1034

    /** 登录态失效错误码：-100 登录失效、-101 账号异常、10001 未登录。 */
    private const val RETCODE_LOGIN_INVALID = -100
    private const val RETCODE_ACCOUNT_ABNORMAL = -101
    private const val RETCODE_NOT_LOGGED_IN = 10001
    private val LOGIN_INVALID_RETCODES = setOf(RETCODE_LOGIN_INVALID, RETCODE_ACCOUNT_ABNORMAL, RETCODE_NOT_LOGGED_IN)

    /** V2 版 stoken 的前缀：这种令牌必须同时带上 mid。 */
    private const val STOKEN_PREFIX_CHARS = 3
    private const val CAPTCHA_HINT = "原神社区打卡需要验证码，请在米游社 App 内手动完成一次社区打卡后再试"

    private val ALREADY_DONE = Regex("已.*(签到|打卡)|already", RegexOption.IGNORE_CASE)

    data class Outcome(
        val success: Boolean,
        val alreadyDone: Boolean,
        val message: String,
        val detail: String,
        val coinBalance: Int = -1,
        val coinGained: Int = -1,
        val signDay: Int = 0,
        /** 任务结果不变时的附加诊断警告，例如余额查询失败。 */
        val warning: String = "",
    )

    @Suppress("detekt:LongMethod")
    suspend fun run(
        cookie: String,
        deviceId: String,
        appVersion: String,
        httpTransport: HttpTransport,
        stoken: String = "",
        mid: String = "",
        uid: String = "",
        ltoken: String = "",
        forums: Set<String> = DEFAULT_MYS_COIN_FORUMS,
    ): Outcome {
        // 米游币接口要的是 App 登录态：优先与 App 一致的 stuid + stoken 组合（v2_ 令牌带 mid），
        // 再回退浏览器的 V1 / V2 字段。账号里保存的 stoken / stmid / ltoken 必须参与拼装——
        // 只依赖 Cookie 字符串里已有的字段时，扫码登录后仍可能拼出服务端不认的组合（retcode=-100）。
        val accountStoken = MysCoinCookies.extractStoken(stoken)
        val built =
            MysCoinCookies.build(cookie, accountStoken, mid, uid, ltoken)
        if (built.candidates.isEmpty()) {
            return Outcome(
                success = false,
                alreadyDone = false,
                message = "米游币打卡凭证不完整，无法形成一致的 Cookie 组合",
                detail = "${built.detail}；未发送签到请求",
            )
        }
        val targets = sanitizeForums(forums)
        // App 组合被跳过时把原因带进日志，避免只看到 cookieVariant=v1 却不知道为何没用 App 组合。
        val appSkipDetail =
            if (built.candidates.any { it.label == MysCoinCookies.LABEL_APP }) {
                ""
            } else {
                ", appSkipped=[stoken=${if (stoken.isBlank()) "missing" else "present"}, " +
                    "uid=${if (uid.isBlank()) "missing" else "present"}, " +
                    "mid=${if (mid.isBlank()) "missing" else "present"}]"
            }
        return try {
            // 余额接口同时充当登录态探测：按候选顺序读一次状态，第一个成功的即最优组合，
            // 它的结果也正好是签到前的余额快照，因此健康路径只多出这一条可复用请求。
            var used = built.candidates.first()
            var beforeRead = StateRead(null)
            for (candidate in built.candidates) {
                val read = MysCoinState.read(candidate.cookie, deviceId, appVersion, httpTransport)
                if (candidate == used) beforeRead = read
                if (read.state != null) {
                    used = candidate
                    beforeRead = read
                    break
                }
            }
            val before = beforeRead.state
            val results = mutableListOf<ForumResult>()
            for ((index, forum) in targets.withIndex()) {
                // 社区之间留一点间隔，避免连续请求触发社区风控。
                if (index > 0) delay(FORUM_INTERVAL_DELAY_MS)
                val (result, candidate) = signForum(forum, built.candidates, used, deviceId, appVersion, httpTransport)
                used = candidate
                results += result
            }
            if (results.any { it.success }) delay(BALANCE_REFRESH_DELAY_MS)
            val afterRead = MysCoinState.read(used.cookie, deviceId, appVersion, httpTransport)
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
            // 所有社区都被判登录失效时，网页 Cookie 有效也不代表 App 登录态可用，提示重新扫码。
            val authWarning =
                if (results.isNotEmpty() && results.all { isLoginInvalid(it.retcode) }) {
                    val keys = MysCoinCookies.stokenCookieKeys(stoken)
                    val prefix = accountStoken.take(STOKEN_PREFIX_CHARS)
                    val probe =
                        "uid=${if (uid.isBlank()) "missing" else "present"}, stokenLen=${accountStoken.length}, " +
                            "stokenPrefix=$prefix, hasSemicolon=${accountStoken.contains(';')}" +
                            if (keys.isEmpty()) "" else ", stokenLooksLikeCookie=[${keys.joinToString(",")}]"
                    val checked =
                        if (uid.isNotBlank() && accountStoken.isNotBlank()) {
                            ", stokenCheck[${MysCoinCookies.probeStoken(uid, accountStoken, mid, httpTransport)}]"
                        } else {
                            ""
                        }
                    "米游币 App 登录态被服务端拒绝（$probe$checked）"
                } else {
                    ""
                }
            val stateWarning =
                listOfNotNull(
                    beforeRead.detail.takeIf { it.isNotBlank() }?.let { "签到前余额查询失败：$it" },
                    afterRead.detail.takeIf { it.isNotBlank() }?.let { "签到后余额查询失败：$it" },
                    authWarning.takeIf { it.isNotBlank() },
                ).joinToString("；")
            val last = results.lastOrNull()
            val forumsDetail =
                results.joinToString(", ") { "${it.forum.id}/${it.forum.name}:retcode=${it.retcode}, http=${it.httpCode}" }
            val detailText =
                detail(
                    httpCode = last?.httpCode ?: DEFAULT_RETCODE,
                    retcode = last?.retcode ?: DEFAULT_RETCODE,
                    message = last?.message ?: "未执行任何社区",
                    cookieVariant = used.label,
                    gids = targets.joinToString("|") { it.id },
                ) + ", forums=[$forumsDetail], candidates=[${built.candidates.joinToString("/") { it.label }}]" +
                    "$appSkipDetail, $stateDetail"
            val succeeded = results.filter { it.success && !it.alreadyDone }
            val succeededNames = succeeded.joinToString("、") { it.forum.name }
            val alreadyNames = results.filter { it.alreadyDone }.joinToString("、") { it.forum.name }
            val failed = results.filterNot { it.success }
            val base =
                Outcome(
                    success = true,
                    alreadyDone = false,
                    message = "",
                    detail = detailText,
                    coinBalance = balance,
                    coinGained = gained,
                    signDay = after?.signDay ?: 0,
                    warning = stateWarning,
                )
            when {
                results.isEmpty() -> {
                    base.copy(success = false, message = "米游币打卡没有可执行的社区")
                }

                failed.isNotEmpty() -> {
                    // 失败原因沿用 retcode 文案，失败分类器仍能识别登录失效等不可重试原因。
                    val reason =
                        when {
                            failed.first().retcode == RETCODE_CAPTCHA_REQUIRED -> CAPTCHA_HINT
                            else -> ErrorText.fromRetcode(failed.first().retcode) ?: failed.first().message
                        }
                    base.copy(
                        success = false,
                        message = "${failed.joinToString("、") { it.forum.name }}打卡失败：$reason",
                    )
                }

                succeeded.isNotEmpty() -> {
                    base.copy(
                        message =
                            when {
                                balance >= 0 && gained > 0 -> {
                                    "${succeededNames}打卡成功，获得 $gained 米游币，余额 $balance"
                                }

                                // 打卡成功但没有增量：今日奖励已在其它设备或其它客户端领过。
                                balance >= 0 -> {
                                    "${succeededNames}今日已打卡，米游币余额 $balance"
                                }

                                else -> {
                                    "${succeededNames}打卡成功，米游币数量暂时无法读取"
                                }
                            },
                    )
                }

                else -> {
                    base.copy(
                        alreadyDone = true,
                        message = if (balance >= 0) "${alreadyNames}今日已打卡，米游币余额 $balance" else "${alreadyNames}今日已打卡",
                    )
                }
            }
        } catch (e: Exception) {
            e.throwIfCancellation()
            Outcome(
                success = false,
                alreadyDone = false,
                message = "米游币打卡${ErrorText.fromException(e)}",
                detail = ErrorText.detailOf(e),
            )
        }
    }

    /** 单个社区打卡，登录失效时按顺序换 Cookie 组合重试；返回结果与最终使用的组合。 */
    @Suppress("detekt:LongParameterList")
    private suspend fun signForum(
        forum: CoinForum,
        candidates: List<CookieCandidate>,
        initial: CookieCandidate,
        deviceId: String,
        appVersion: String,
        httpTransport: HttpTransport,
    ): Pair<ForumResult, CookieCandidate> {
        val body = JSONObject().put("gids", forum.id)
        val bodyText = body.toString()
        var used = initial
        var response = postSignIn(used.cookie, deviceId, appVersion, body, bodyText, httpTransport)
        var json = response.json()
        var retcode = json.optInt("retcode", DEFAULT_RETCODE)
        for (candidate in candidates.filterNot { it == used }) {
            if (!isLoginInvalid(retcode)) break
            used = candidate
            response = postSignIn(candidate.cookie, deviceId, appVersion, body, bodyText, httpTransport)
            json = response.json()
            retcode = json.optInt("retcode", DEFAULT_RETCODE)
        }
        val message = json.optString("message").ifBlank { "未知错误" }
        val httpSuccess = response.code in HTTP_SUCCESS_MIN..HTTP_SUCCESS_MAX
        // 文案兜底只接受成功码，避免限流文案（如“请勿重复打卡”）被误判为已打卡。
        val already =
            httpSuccess &&
                (retcode in ALREADY_DONE_RETCODES || (retcode == 0 && ALREADY_DONE.containsMatchIn(message)))
        val result =
            ForumResult(
                forum = forum,
                success = httpSuccess && (retcode == 0 || already),
                alreadyDone = already && retcode != 0,
                httpCode = response.code,
                retcode = retcode,
                message = message,
            )
        return result to used
    }

    private fun detail(
        httpCode: Int,
        retcode: Int,
        message: String,
        cookieVariant: String,
        gids: String,
    ): String =
        "miyoubi check-in http=$httpCode, retcode=$retcode, message=$message, " +
            "gids=$gids, cookieVariant=$cookieVariant"

    /** 单个社区的打卡结果，用于多社区汇总。 */
    private data class ForumResult(
        val forum: CoinForum,
        val success: Boolean,
        val alreadyDone: Boolean,
        val httpCode: Int,
        val retcode: Int,
        val message: String,
    )

    /** 发送社区打卡请求：DS 使用 App 写接口的 md5_v2(x6) 签名，body 需与签名时的字节一致。 */
    @Suppress("LongParameterList")
    private suspend fun postSignIn(
        cookie: String,
        deviceId: String,
        appVersion: String,
        body: JSONObject,
        bodyText: String,
        httpTransport: HttpTransport,
    ): HttpResponse =
        httpTransport.postJson(
            SIGN_URL,
            MysHeaders.coinCheckIn(cookie, deviceId, appVersion, Ds.generateX6(body = bodyText)),
            body,
        )

    /** 登录态类错误码：换下一个 Cookie 组合重试即可，不必整体失败。 */
    private fun isLoginInvalid(retcode: Int): Boolean = retcode in LOGIN_INVALID_RETCODES
}
