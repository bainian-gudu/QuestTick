package com.questtick.sign

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CookieParserTest {
    @Test
    fun parseCookieHeaderExtractsKeyValues() {
        val parsed = CookieParser.parseCookieHeader(
            "stoken=stoken_value; ltoken=ltoken_value; mid=mid_value; cookie_token=cookie_value",
        )

        assertEquals("stoken_value", parsed["stoken"])
        assertEquals("ltoken_value", parsed["ltoken"])
        assertEquals("mid_value", parsed["mid"])
        assertEquals("cookie_value", parsed["cookie_token"])
    }

    @Test
    fun parseCookieHeaderIgnoresMalformedParts() {
        val parsed = CookieParser.parseCookieHeader("invalid; stoken=ok; empty=; another")

        assertEquals("ok", parsed["stoken"])
        assertEquals("", parsed["empty"])
        assertTrue("invalid" !in parsed)
        assertTrue("another" !in parsed)
    }

    @Test
    fun parseSetCookieHeadersIgnoresAttributes() {
        val parsed = CookieParser.parseSetCookieHeaders(
            listOf(
                "stoken_v2=stoken_v2_value; Path=/; HttpOnly; Secure",
                "ltoken_v2=ltoken_v2_value; Path=/; SameSite=None",
                "account_mid_v2=mid_v2_value; Domain=.miyoushe.com",
                "cookie_token_v2=cookie_v2_value; Max-Age=3600",
            ),
        )

        assertEquals("stoken_v2_value", parsed["stoken_v2"])
        assertEquals("ltoken_v2_value", parsed["ltoken_v2"])
        assertEquals("mid_v2_value", parsed["account_mid_v2"])
        assertEquals("cookie_v2_value", parsed["cookie_token_v2"])
        assertTrue("Path attribute should be ignored", "Path" !in parsed && "path" !in parsed)
        assertTrue("HttpOnly attribute should be ignored", "HttpOnly" !in parsed && "httponly" !in parsed)
    }

    @Test
    fun parseLoginCookiesPrefersV2ValuesAndUserInfo() {
        val parsed = CookieParser.parseLoginCookies(
            setCookieHeaders = listOf(
                "stuid=old_uid; Path=/",
                "cookie_token=old_cookie; Path=/",
                "cookie_token_v2=new_cookie; Path=/",
                "ltoken=old_ltoken; Path=/",
                "ltoken_v2=new_ltoken; Path=/",
                "stoken=old_stoken; Path=/",
                "stoken_v2=new_stoken; Path=/",
                "account_mid_v2=cookie_mid; Path=/",
            ),
            userUid = "user_uid",
            userMid = "user_mid",
        )

        assertEquals("user_uid", parsed.uid)
        assertEquals("user_mid", parsed.mid)
        assertEquals("new_cookie", parsed.cookieToken)
        assertEquals("new_ltoken", parsed.ltoken)
        assertEquals("new_stoken", parsed.stoken)
    }

    @Test
    fun parseLoginCookiesExtractsLoginTicketForStokenFallback() {
        val parsed = CookieParser.parseLoginCookies(
            setCookieHeaders = listOf(
                "account_id=123456789; Path=/",
                "cookie_token=cookie; Path=/",
                "login_ticket=login_ticket_value; Path=/; HttpOnly",
            ),
        )

        assertEquals("123456789", parsed.uid)
        assertEquals("cookie", parsed.cookieToken)
        assertEquals("login_ticket_value", parsed.loginTicket)
    }

    @Test
    fun parseLoginCookiesFallsBackToLegacyFields() {
        val parsed = CookieParser.parseLoginCookies(
            setCookieHeaders = listOf(
                "account_id=123456789; Path=/",
                "cookie_token=legacy_cookie; Path=/",
                "ltoken=legacy_ltoken; Path=/",
                "stoken=legacy_stoken; Path=/",
                "mid=legacy_mid; Path=/",
            ),
        )

        assertEquals("123456789", parsed.uid)
        assertEquals("legacy_mid", parsed.mid)
        assertEquals("legacy_cookie", parsed.cookieToken)
        assertEquals("legacy_ltoken", parsed.ltoken)
        assertEquals("legacy_stoken", parsed.stoken)
    }
}
