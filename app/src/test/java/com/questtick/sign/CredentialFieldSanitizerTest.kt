package com.questtick.sign

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CredentialFieldSanitizerTest {
    @Test
    fun mysSigninCookieDropsRefreshCredentials() {
        val cookie =
            CredentialFieldSanitizer.mysSigninCookie(
                "account_id=10001; cookie_token=cookie; ltoken=ltoken; stoken=stoken; stuid=10001; mid=mid",
            )

        assertEquals("account_id=10001; cookie_token=cookie; ltoken=ltoken", cookie)
        assertFalse(cookie.contains("stoken"))
        assertFalse(cookie.contains("stuid"))
        assertFalse(cookie.contains("mid=mid"))
    }

    @Test
    fun cloudSigninTokenKeepsRawTokenWithoutKeyValueParts() {
        assertEquals("raw_combo_token", CredentialFieldSanitizer.cloudSigninToken("raw_combo_token"))
    }

    @Test
    fun cloudSigninTokenDropsWebLoginCookieFields() {
        val token =
            CredentialFieldSanitizer.cloudSigninToken(
                "ai=4;ci=1;oi=open;ct=combo;si=sign;bi=hk4e_cn;stoken=stoken;login_ticket=ticket",
            )

        assertEquals("ai=4;ci=1;oi=open;ct=combo;si=sign;bi=hk4e_cn", token)
        assertFalse(token.contains("stoken"))
        assertFalse(token.contains("login_ticket"))
    }
}
