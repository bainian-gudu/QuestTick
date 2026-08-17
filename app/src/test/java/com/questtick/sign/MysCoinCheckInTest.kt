package com.questtick.sign

import com.questtick.net.HttpMethod
import com.questtick.net.HttpRequest
import com.questtick.net.HttpResponse
import com.questtick.net.HttpRetryMode
import com.questtick.net.HttpTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MysCoinCheckInTest {
    @Test
    fun `computes balance and gained coins from before and after state`() = runBlocking {
        var stateReads = 0
        val transport = HttpTransport { request ->
            when (request.method) {
                HttpMethod.GET -> {
                    stateReads++
                    if (stateReads == 1) {
                        HttpResponse.text(200, "{\"retcode\":0,\"message\":\"OK\",\"data\":{\"total_points\":100,\"already_received_points\":0}}")
                    } else {
                        HttpResponse.text(200, "{\"retcode\":0,\"message\":\"OK\",\"data\":{\"total_points\":130,\"already_received_points\":30}}")
                    }
                }
                else -> HttpResponse.text(200, "{\"retcode\":0,\"message\":\"OK\"}")
            }
        }

        val outcome = MysCoinCheckIn.run("cookie", "device", "2.109.0", transport)

        assertTrue(outcome.success)
        assertEquals(130, outcome.coinBalance)
        assertEquals(30, outcome.coinGained)
    }

    @Test
    fun `uses fixed genshin community check-in request without retry`() = runBlocking {
        var captured: HttpRequest? = null
        val transport = HttpTransport { request ->
            if (request.method == HttpMethod.POST) captured = request
            HttpResponse.text(200, "{\"retcode\":0,\"message\":\"OK\"}")
        }

        val outcome = MysCoinCheckIn.run("account_id=1; cookie_token=test", "DEVICE-ID", "2.109.0", transport)

        assertTrue(outcome.success)
        assertFalse(outcome.alreadyDone)
        assertEquals(HttpMethod.POST, captured?.method)
        assertEquals("https://bbs-api.miyoushe.com/apihub/app/api/signIn", captured?.url)
        assertEquals("{\"gids\":\"2\"}", captured?.body)
        assertEquals(HttpRetryMode.NEVER, captured?.config?.retryMode)
        assertEquals("bbs-api.miyoushe.com", captured?.headers?.get("Host"))
        assertEquals("DEVICE-ID", captured?.headers?.get("x-rpc-device_id"))
        assertEquals("2.109.0", captured?.headers?.get("x-rpc-app_version"))
        assertTrue(captured?.headers?.get("DS").orEmpty().isNotBlank())
    }

    @Test
    fun `already checked in is a completed outcome`() = runBlocking {
        val transport = HttpTransport {
            HttpResponse.text(200, "{\"retcode\":-5003,\"message\":\"今日已签到\"}")
        }

        val outcome = MysCoinCheckIn.run("cookie", "device", "2.109.0", transport)

        assertTrue(outcome.success)
        assertTrue(outcome.alreadyDone)
        assertEquals("原神社区今日已打卡", outcome.message)
    }

    @Test
    fun `server error preserves original retcode and message in detail`() = runBlocking {
        val transport = HttpTransport {
            HttpResponse.text(200, "{\"retcode\":1034,\"message\":\"Risk verification required\"}")
        }

        val outcome = MysCoinCheckIn.run("cookie", "device", "2.109.0", transport)

        assertFalse(outcome.success)
        assertTrue(outcome.detail.contains("retcode=1034"))
        assertTrue(outcome.detail.contains("Risk verification required"))
    }
}
