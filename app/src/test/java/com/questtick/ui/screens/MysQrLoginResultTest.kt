package com.questtick.ui.screens

import com.questtick.sign.QRLoginManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MysQrLoginResultTest {
    @Test
    fun buildMysQrLoginResultKeepsRefreshCredentialsWhenRequested() {
        val result = buildMysQrLoginResult(confirmed(), keepLogin = true)

        assertTrue(result.keepLogin)
        assertEquals("10001", result.uid)
        assertEquals("stoken", result.stoken)
        assertEquals("mid", result.stmid)
        assertEquals("ltoken", result.ltoken)
        assertEquals("旅行者", result.nickname)
        assertTrue(result.cookie.contains("account_id=10001"))
        assertTrue(result.cookie.contains("account_id_v2=10001"))
        assertTrue(result.cookie.contains("cookie_token=cookie_token"))
        assertTrue(result.cookie.contains("cookie_token_v2=cookie_token"))
        assertTrue(result.cookie.contains("ltoken=ltoken"))
        assertTrue(result.cookie.contains("ltoken_v2=ltoken"))
        assertTrue(result.cookie.contains("account_mid_v2=mid"))
    }

    @Test
    fun buildMysQrLoginResultFallsBackToOneTimeLoginWhenStokenIsMissing() {
        val result = buildMysQrLoginResult(confirmed(stoken = ""), keepLogin = true)

        assertFalse(result.keepLogin)
        assertEquals("", result.uid)
        assertEquals("", result.stoken)
        assertEquals("", result.stmid)
        assertEquals("", result.ltoken)
        assertEquals("旅行者", result.nickname)
        assertTrue(result.cookie.contains("account_id=10001"))
        assertTrue(result.cookie.contains("cookie_token=cookie_token"))
    }

    @Test
    fun buildMysQrLoginResultClearsRefreshCredentialsForOneTimeLogin() {
        val result = buildMysQrLoginResult(confirmed(), keepLogin = false)

        assertFalse(result.keepLogin)
        assertEquals("", result.uid)
        assertEquals("", result.stoken)
        assertEquals("", result.stmid)
        assertEquals("", result.ltoken)
        assertEquals("旅行者", result.nickname)
        assertTrue(result.cookie.contains("account_id=10001"))
        assertTrue(result.cookie.contains("cookie_token=cookie_token"))
    }

    private fun confirmed(stoken: String = "stoken"): QRLoginManager.ScanStatus.Confirmed =
        QRLoginManager.ScanStatus.Confirmed(
            uid = "10001",
            cookieToken = "cookie_token",
            ltoken = "ltoken",
            stoken = stoken,
            mid = "mid",
            nickname = "旅行者",
        )
}
