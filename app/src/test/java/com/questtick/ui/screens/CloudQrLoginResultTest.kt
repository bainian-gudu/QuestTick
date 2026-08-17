package com.questtick.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudQrLoginResultTest {
    @Test
    fun buildCloudQrLoginResultKeepsCookieHeaderWhenRequested() {
        val result = buildCloudQrLoginResult(
            comboToken = "combo_token",
            cookieHeader = "cookie=value",
            keepLogin = true,
        )

        assertTrue(result.keepLogin)
        assertEquals("combo_token", result.comboToken)
        assertEquals("cookie=value", result.cookieHeader)
    }

    @Test
    fun buildCloudQrLoginResultClearsCookieHeaderForOneTimeLogin() {
        val result = buildCloudQrLoginResult(
            comboToken = "combo_token",
            cookieHeader = "cookie=value",
            keepLogin = false,
        )

        assertFalse(result.keepLogin)
        assertEquals("combo_token", result.comboToken)
        assertEquals("", result.cookieHeader)
    }
}
