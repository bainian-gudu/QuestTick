package com.questtick.sign

import com.questtick.data.Account
import com.questtick.repository.auth.AuthRepository
import com.questtick.net.HttpTransport
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthRepositoryTest {
    private val auth = AuthRepository(HttpTransport { error("network not expected") })

    @Test
    fun loginCookieParsingExtractsCredentials() {
        val cookies =
            auth.parseLoginCookies(
                setCookieHeaders =
                    listOf(
                        "stuid=123456789; Path=/",
                        "stoken_v2=stoken_value; Path=/; HttpOnly",
                        "ltoken_v2=ltoken_value; Path=/",
                        "account_mid_v2=mid_value; Path=/",
                        "cookie_token_v2=cookie_token_value; Path=/",
                    ),
            )

        assertEquals("123456789", cookies.uid)
        assertEquals("stoken_value", cookies.stoken)
        assertEquals("ltoken_value", cookies.ltoken)
        assertEquals("mid_value", cookies.mid)
        assertEquals("cookie_token_value", cookies.cookieToken)
    }

    @Test
    fun buildCookieContainsRequiredFields() {
        val cookie = auth.buildCookie("123456789", "cookie_token_value", "ltoken_value", "mid_value")

        assertTrue(cookie.contains("account_id=123456789"))
        assertTrue(cookie.contains("cookie_token=cookie_token_value"))
        assertTrue(cookie.contains("ltoken=ltoken_value"))
        assertTrue(cookie.contains("account_mid_v2=mid_value"))
    }

    @Test
    fun cookieHealthStateTransitions() {
        val expired = auth.checkCookieHealth(Account(id = "1", label = "test", mysCookie = ""))
        val expiring = auth.checkCookieHealth(Account(id = "1", label = "test", mysCookie = "foo=bar"))
        val validCookie = "account_id=123456789; cookie_token=${"a".repeat(48)}; ltoken=${"b".repeat(24)}"
        val valid = auth.checkCookieHealth(Account(id = "1", label = "test", mysCookie = validCookie))

        assertEquals(AuthRepository.CredentialState.EXPIRED, expired.state)
        assertEquals(AuthRepository.CredentialState.EXPIRING, expiring.state)
        assertEquals(AuthRepository.CredentialState.VALID, valid.state)
    }

    @Test
    fun cookieHealthDetectsEmptyCookieAsExpired() {
        val health = auth.checkCookieHealth(Account(id = "1", label = "test", mysCookie = ""))

        assertEquals(AuthRepository.CredentialState.EXPIRED, health.state)
        assertTrue(health.reason.contains("未填写"))
    }

    @Test
    fun cookieHealthDetectsMissingFields() {
        val health = auth.checkCookieHealth(Account(id = "1", label = "test", mysCookie = "foo=bar"))

        assertEquals(AuthRepository.CredentialState.EXPIRING, health.state)
        assertTrue(health.reason.contains("cookie_token"))
        assertTrue(health.reason.contains("account_id"))
    }

    @Test
    fun cookieHealthValidWhenRequiredFieldsExist() {
        val cookie = "account_id=123456789; cookie_token=${"a".repeat(48)}; ltoken=${"b".repeat(24)}"
        val health = auth.checkCookieHealth(Account(id = "1", label = "test", mysCookie = cookie))

        assertEquals(AuthRepository.CredentialState.VALID, health.state)
    }

    @Test
    fun refreshCookieReturnsNullWhenNotKeepLogin() =
        runTest {
            val result =
                auth.refreshCookie(
                    Account(
                        id = "1",
                        label = "test",
                        mysUid = "123456789",
                        stoken = "stoken",
                        qrLoginBound = false,
                    ),
                )

            assertNull(result)
        }

    @Test
    fun refreshCloudTokenFailsWhenNotKeepLogin() =
        runTest {
            val result = auth.refreshCloudToken("CloudYS", webCookie = "", deviceId = "device")

            assertTrue(result.isFailure)
        }

    @Test
    fun cookieExpiredTextDetection() {
        assertTrue(auth.isCookieExpired("登录状态已失效，请重新登录"))
        assertTrue(auth.isCookieExpired("retcode=-100"))
        assertFalse(auth.isCookieExpired("今日已签到"))
    }

    @Test
    fun cloudTokenExpiredTextDetection() {
        assertTrue(auth.isCloudTokenExpired("", "retcode=10001, combo_token invalid"))
        assertTrue(auth.isCloudTokenExpired("unauthorized"))
        assertFalse(auth.isCloudTokenExpired("领取完成"))
    }
}
