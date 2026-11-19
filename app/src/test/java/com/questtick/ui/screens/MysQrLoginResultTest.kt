package com.questtick.ui.screens

import com.questtick.sign.QRLoginManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MysQrLoginResultTest {
    @Test
    fun buildMysQrLoginResultSavesFullWebCookie() {
        val result = buildMysQrLoginResult(confirmed())

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
    fun buildMysQrLoginResultDropsWebOnlyRefreshCredentials() {
        // 扫码返回的是 web 族令牌，无法用于米游币打卡，因此不能回填 uid / stoken / stmid / ltoken。
        val result = buildMysQrLoginResult(confirmed())

        assertFalse(result.keepLogin)
        assertEquals("", result.uid)
        assertEquals("", result.stoken)
        assertEquals("", result.stmid)
        assertEquals("", result.ltoken)
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
