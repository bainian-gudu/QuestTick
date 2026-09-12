package com.questtick.sign

/*
 * 凭证刷新：使用 SToken 换取新的 cookie_token 与 ltoken，供保持登录态下的自动续期使用。
 */

import com.questtick.log.AppLog
import com.questtick.core.throwIfCancellation
import com.questtick.net.HttpTransport
import com.questtick.net.get
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * 使用 SToken 刷新 cookie_token 与 ltoken。
 *
 * 接口参考 UIGF-org/mihoyo-api-collect：getCookieAccountInfoBySToken 与 getLTokenBySToken。
 */
object CookieRefresher {
    private const val TAG = "CookieRefresher"

    data class RefreshedCookie(
        val cookieToken: String,
        val ltoken: String,
        val fullCookie: String,
    )

    /**
     * 使用 SToken 刷新 cookie_token 和 ltoken。
     * @return 成功返回可用于签到的新 Cookie，失败返回 null。
     */
    suspend fun refresh(
        uid: String,
        stoken: String,
        mid: String,
        httpTransport: HttpTransport,
        onDiagnostic: (message: String, detail: String) -> Unit = { _, _ -> },
    ): RefreshedCookie? {
        if (uid.isBlank() || stoken.isBlank()) return null

        val stokenCookie =
            buildString {
                append("stoken=$stoken; stuid=$uid")
                if (mid.isNotBlank()) {
                    append("; stmid=$mid; mid=$mid")
                }
            }

        val cookieToken =
            try {
                val headers = mapOf("Cookie" to stokenCookie)
                val resp =
                    httpTransport.get(
                        getCookieAccountInfoUrl(uid),
                        headers,
                    )
                val json = resp.json()
                val retcode = json.optInt("retcode", -999)
                if (retcode != 0) {
                    AppLog.w(TAG, "getCookieAccountInfoBySToken failed: retcode=$retcode, uid=${Mask.uid(uid)}")
                    onDiagnostic(
                        "Cookie Token 刷新接口返回错误",
                        "getCookieAccountInfoBySToken http=${resp.code}, retcode=$retcode, " +
                            "message=${json.optString("message")}",
                    )
                    return null
                }
                json.optJSONObject("data")?.optString("cookie_token").orEmpty()
            } catch (e: Exception) {
                e.throwIfCancellation()
                AppLog.w(TAG, "getCookieAccountInfoBySToken exception, uid=${Mask.uid(uid)}", e)
                onDiagnostic("Cookie Token 刷新请求异常", ErrorText.detailOf(e))
                return null
            }

        if (cookieToken.isBlank()) return null

        val ltoken =
            try {
                val headers = mapOf("Cookie" to stokenCookie)
                val resp =
                    httpTransport.get(
                        "https://api-takumi.mihoyo.com/auth/api/getLTokenBySToken",
                        headers,
                    )
                val json = resp.json()
                val retcode = json.optInt("retcode", -999)
                if (retcode != 0) {
                    AppLog.w(TAG, "getLTokenBySToken failed: retcode=$retcode, uid=${Mask.uid(uid)}")
                    onDiagnostic(
                        "LToken 刷新接口返回错误",
                        "getLTokenBySToken http=${resp.code}, retcode=$retcode, message=${json.optString("message")}",
                    )
                    ""
                } else {
                    json.optJSONObject("data")?.optString("ltoken").orEmpty()
                }
            } catch (e: Exception) {
                e.throwIfCancellation()
                AppLog.w(TAG, "getLTokenBySToken exception, uid=${Mask.uid(uid)}", e)
                onDiagnostic("LToken 刷新请求异常", ErrorText.detailOf(e))
                ""
            }

        val fullCookie = buildCookie(uid, cookieToken, ltoken, mid)
        return RefreshedCookie(cookieToken, ltoken, fullCookie)
    }

    /** 拼接签到接口需要的完整 Cookie 字符串。 */
    fun buildCookie(
        uid: String,
        cookieToken: String,
        ltoken: String,
        mid: String,
    ): String {
        val parts = linkedMapOf<String, String>()

        fun putIfNotBlank(
            key: String,
            value: String,
        ) {
            if (value.isNotBlank()) parts[key] = value
        }

        putIfNotBlank("account_id", uid)
        putIfNotBlank("account_id_v2", uid)
        putIfNotBlank("ltuid", uid)
        putIfNotBlank("ltuid_v2", uid)
        putIfNotBlank("cookie_token", cookieToken)
        putIfNotBlank("cookie_token_v2", cookieToken)
        putIfNotBlank("account_mid_v2", mid)
        putIfNotBlank("ltmid_v2", mid)
        if (ltoken.isNotBlank()) {
            putIfNotBlank("ltoken", ltoken)
            putIfNotBlank("ltoken_v2", ltoken)
        }

        return parts.entries.joinToString("; ") { (k, v) -> "$k=$v" }
    }

    private fun getCookieAccountInfoUrl(uid: String): String {
        val encodedUid = urlEncode(uid)
        return "https://api-takumi.mihoyo.com/auth/api/getCookieAccountInfoBySToken?uid=$encodedUid"
    }

    private fun urlEncode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    /** 判断签到结果是否表示 Cookie 已失效。 */
    fun isCookieExpired(message: String): Boolean {
        val expiredKeywords =
            listOf(
                "登录状态已失效",
                "尚未登录",
                "请先登录",
                "请重新获取",
                "login",
                "cookie",
            )
        val expiredCodes = listOf("-100", "-101", "10001", "10103")
        val lower = message.lowercase()
        return expiredKeywords.any { lower.contains(it.lowercase()) } ||
            expiredCodes.any { lower.contains(it) }
    }
}
