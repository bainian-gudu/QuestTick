@file:Suppress(
    "detekt:TooGenericExceptionCaught",
)

package com.questtick.sign

/*
 * 米游币打卡的 Cookie 组装与校验：把账号保存的 SToken / mid 与 Cookie 拼成 App 登录态候选，
 * 并给出每个候选的可用性诊断。
 */
import com.questtick.core.throwIfCancellation
import com.questtick.net.HttpTransport
import com.questtick.net.getIdempotent
import org.json.JSONObject

private const val DEFAULT_RETCODE = -999
private const val V2_STOKEN_PREFIX = "v2_"
private const val MAX_DIAGNOSTIC_KEYS = 8

/** 一个可用的 Cookie 组合；[label] 用于日志区分 app / v1 / v2 三种形态。 */
internal data class CookieCandidate(
    val label: String,
    val cookie: String,
)

/** 候选组合与拼装失败时的缺失/冲突说明。 */
internal data class CookieBuildResult(
    val candidates: List<CookieCandidate>,
    val detail: String,
)

internal object MysCoinCookies {
    const val LABEL_APP = "app"
    const val LABEL_V1 = "v1"
    const val LABEL_V2 = "v2"

    /** 判断一段文本是否像 Cookie（含 key=value 形式的已知字段）。 */
    private val COOKIE_LIKE = Regex("(^|;)\\s*(stoken|stoken_v2|ltoken|ltoken_v2|ltuid|account_id|mid|cookie_token)\\s*=")

    /**
     * 构造米游社 App 登录态 Cookie 候选，按可信度排序后交给上层逐个探测。
     *
     * 首选与米游社 App 实际请求一致：只有 stuid / stoken（`v2_` 前缀的 stoken 必须带 mid），
     * 不带 ltoken / account_id 等网页字段。历史实现只拼 V1/V2 浏览器字段，扫码登录后也可能拼出
     * 服务端不认的组合，表现为 retcode=-100「登录失效」。V1/V2 仅作为旧账号的兜底候选。
     */
    @Suppress("detekt:LongMethod", "detekt:CyclomaticComplexMethod")
    fun build(
        cookie: String,
        stoken: String,
        mid: String,
        uid: String,
        ltoken: String,
    ): CookieBuildResult {
        val source = linkedMapOf<String, String>()
        cookie.split(';').forEach { part ->
            val separator = part.indexOf('=')
            if (separator > 0) {
                source[part.substring(0, separator).trim().lowercase()] = part.substring(separator + 1).trim()
            }
        }

        fun value(vararg names: String): String = names.firstNotNullOfOrNull { source[it] }.orEmpty()

        fun sameIdentity(
            first: String,
            second: String,
        ): Boolean = first.isBlank() || second.isBlank() || first == second

        val account = uid.trim()
        // Cookie 里的 uid 只作兜底：stoken 属于账号记录，uid 也以账号保存值为准。
        // 此前这里要求两者相等，Cookie 里的旧 uid 与保存值一旦不一致就直接放弃 App 组合，
        // 结果只能退回 V1/V2 浏览器组合并被服务端判成登录失效（retcode=-100）。
        val cookieUid = value("stuid", "account_id", "ltuid", "ltuid_v2", "account_id_v2")
        val appStoken = value("stoken", "stoken_v2").ifBlank { stoken.trim() }
        val appStuid = account.ifBlank { cookieUid }
        val appMid = value("mid", "stmid", "stmid_v2", "account_mid_v2", "ltmid_v2").ifBlank { mid.trim() }
        val appNeedsMid = appStoken.startsWith(V2_STOKEN_PREFIX)
        val appComplete =
            appStoken.isNotBlank() &&
                appStuid.isNotBlank() &&
                (!appNeedsMid || appMid.isNotBlank())

        val v1Stoken = value("stoken").ifBlank { stoken.trim() }
        val v1Mid = value("mid", "stmid").ifBlank { mid.trim() }
        val v1Ltoken = value("ltoken").ifBlank { ltoken.trim() }
        val v1Stuid = value("stuid").ifBlank { account }
        val v1Account = value("account_id").ifBlank { account }
        val v1Complete =
            v1Stoken.isNotBlank() &&
                v1Mid.isNotBlank() &&
                v1Ltoken.isNotBlank() &&
                v1Stuid.isNotBlank() &&
                v1Account.isNotBlank() &&
                sameIdentity(v1Stuid, v1Account) &&
                sameIdentity(v1Account, account)

        val v2Stoken = value("stoken_v2").ifBlank { stoken.trim() }
        val v2Mid = value("mid", "stmid_v2", "account_mid_v2").ifBlank { mid.trim() }
        val v2Ltoken = value("ltoken_v2").ifBlank { ltoken.trim() }
        // V2 组合里 ltmid_v2 与 mid 是同一身份标识，缺字段时用 mid 补齐。
        val v2Ltmid = value("ltmid_v2", "account_mid_v2").ifBlank { v2Mid }
        val v2Account = value("account_id_v2").ifBlank { account }
        val v2Complete =
            v2Stoken.isNotBlank() &&
                v2Mid.isNotBlank() &&
                v2Ltoken.isNotBlank() &&
                v2Ltmid.isNotBlank() &&
                v2Account.isNotBlank() &&
                sameIdentity(v2Account, account)

        val candidates =
            buildList {
                if (appComplete) {
                    add(
                        CookieCandidate(
                            label = LABEL_APP,
                            cookie =
                                listOfNotNull(
                                    "stuid=$appStuid",
                                    "stoken=$appStoken",
                                    appMid.takeIf { it.isNotBlank() }?.let { "mid=$it" },
                                ).joinToString("; "),
                        ),
                    )
                }
                if (v1Complete) {
                    add(
                        CookieCandidate(
                            label = LABEL_V1,
                            cookie =
                                "stoken=$v1Stoken; mid=$v1Mid; stuid=$v1Stuid; " +
                                    "ltoken=$v1Ltoken; account_id=$v1Account",
                        ),
                    )
                }
                if (v2Complete) {
                    add(
                        CookieCandidate(
                            label = LABEL_V2,
                            cookie =
                                "stoken_v2=$v2Stoken; mid=$v2Mid; ltoken_v2=$v2Ltoken; " +
                                    "ltmid_v2=$v2Ltmid; account_id_v2=$v2Account",
                        ),
                    )
                }
            }
        if (candidates.isNotEmpty()) return CookieBuildResult(candidates = candidates, detail = "")

        val appMissing =
            buildList {
                if (appStoken.isBlank()) add("stoken")
                if (appStuid.isBlank()) add("stuid")
                if (appNeedsMid && appMid.isBlank()) add("mid（v2_ 前缀的 stoken 必须带 mid）")
            }
        val v1Missing =
            buildList {
                if (v1Stoken.isBlank()) add("stoken")
                if (v1Mid.isBlank()) add("mid")
                if (v1Ltoken.isBlank()) add("ltoken")
                if (v1Stuid.isBlank()) add("stuid")
                if (v1Account.isBlank()) add("account_id")
            }
        val v2Missing =
            buildList {
                if (v2Stoken.isBlank()) add("stoken_v2")
                if (v2Mid.isBlank()) add("mid")
                if (v2Ltoken.isBlank()) add("ltoken_v2")
                if (v2Ltmid.isBlank()) add("ltmid_v2")
                if (v2Account.isBlank()) add("account_id_v2")
            }
        val conflicts =
            buildList {
                if (account.isNotBlank() && cookieUid.isNotBlank() && cookieUid != account) {
                    add("Cookie UID=$cookieUid 与保存 UID=$account 不一致（已按保存值拼装）")
                }
                if (v1Stuid.isNotBlank() && v1Account.isNotBlank() && v1Stuid != v1Account) {
                    add("V1 stuid 与 account_id 不一致")
                }
                if (account.isNotBlank() && v2Account.isNotBlank() && v2Account != account) {
                    add("V2 account_id_v2 与保存 UID 不一致")
                }
            }
        return CookieBuildResult(
            candidates = emptyList(),
            detail =
                "App Cookie 不完整：missing=[${appMissing.joinToString(", ")}]；" +
                    "V1 Cookie 不完整：missing=[${v1Missing.joinToString(", ")}]；" +
                    "V2 Cookie 不完整：missing=[${v2Missing.joinToString(", ")}]" +
                    conflicts
                        .takeIf { it.isNotEmpty() }
                        ?.let { "；身份冲突：${it.joinToString("、")}" }
                        .orEmpty(),
        )
    }

    /**
     * 取出真正的 SToken 值。
     *
     * 账号里这一项有可能被存成整段 Cookie（例如早期手动粘贴），直接当 SToken 发给服务端
     * 会得到 retcode=-100 登录失效；这里先按 Cookie 解析出 stoken / stoken_v2 的值。
     */
    fun extractStoken(raw: String): String {
        val value = raw.trim()
        if (value.isEmpty() || !COOKIE_LIKE.containsMatchIn(value)) return value
        val parsed = linkedMapOf<String, String>()
        value.split(';').forEach { part ->
            val index = part.indexOf('=')
            if (index > 0) {
                parsed[part.substring(0, index).trim().lowercase()] = part.substring(index + 1).trim()
            }
        }
        // 只有确实含 stoken 字段才提取；否则返回空串，让上层跳过 App 组合，
        // 避免把整段浏览器 Cookie 当成 SToken 发给服务端（只会得到 -100）。
        return (parsed["stoken_v2"] ?: parsed["stoken"]).orEmpty()
    }

    /** 列出看起来像 Cookie 的 SToken 里有哪些字段名（只列键，不含值），用于诊断。 */
    fun stokenCookieKeys(raw: String): List<String> {
        val value = raw.trim()
        if (!COOKIE_LIKE.containsMatchIn(value)) return emptyList()
        return value
            .split(';')
            .mapNotNull { part ->
                val index = part.indexOf('=')
                if (index > 0) part.substring(0, index).trim().lowercase() else null
            }.take(MAX_DIAGNOSTIC_KEYS)
    }

    /**
     * 用官方 SToken 校验接口确认 App 登录态是否有效。
     *
     * 网页 Cookie 有效不代表 SToken 可用；这里返回服务端的 retcode，便于在日志里定论。
     */
    suspend fun probeStoken(
        uid: String,
        stoken: String,
        mid: String,
        httpTransport: HttpTransport,
    ): String {
        val midValue = mid.trim()
        // V1 令牌用 stoken=，V2 令牌必须用 stoken_v2= 并配套 mid；两种形态都试，
        // 避免把有效的 V2 令牌按 V1 校验从而误判登录失效。
        val forms = mutableListOf("stuid=$uid; stoken=$stoken" to "v1")
        if (midValue.isNotBlank()) {
            forms += "stoken_v2=$stoken; mid=$midValue" to "v2"
            forms += "stoken_v2=$stoken; ltuid_v2=$uid; mid=$midValue" to "v2+ltuid"
        }
        var last = ""
        for ((cookie, label) in forms) {
            last = probeWithCookie(uid, cookie, httpTransport)
            if (last.startsWith("retcode=0")) return "$last, form=$label"
        }
        return "$last, form=all（v1 与 v2 形态均未通过）"
    }

    private suspend fun probeWithCookie(
        uid: String,
        cookie: String,
        httpTransport: HttpTransport,
    ): String =
        try {
            val response =
                httpTransport.getIdempotent(
                    "https://api-takumi.mihoyo.com/auth/api/getCookieAccountInfoBySToken?uid=$uid",
                    mapOf("Cookie" to cookie),
                )
            val json = response.json()
            "retcode=${json.optInt("retcode", DEFAULT_RETCODE)}, message=${json.optString("message").ifBlank { "-" }}"
        } catch (e: Exception) {
            e.throwIfCancellation()
            "exception=${e.javaClass.simpleName}"
        }
}
