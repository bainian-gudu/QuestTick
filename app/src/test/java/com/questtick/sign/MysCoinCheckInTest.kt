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
    fun `computes balance and gained coins from before and after state`() =
        runBlocking {
            var stateReads = 0
            val transport =
                HttpTransport { request ->
                    when (request.method) {
                        HttpMethod.GET -> {
                            stateReads++
                            if (stateReads == 1) {
                                HttpResponse.text(200, "{\"retcode\":0,\"message\":\"OK\",\"data\":{\"total_points\":100,\"already_received_points\":0}}")
                            } else {
                                HttpResponse.text(200, "{\"retcode\":0,\"message\":\"OK\",\"data\":{\"total_points\":130,\"already_received_points\":30}}")
                            }
                        }

                        else -> {
                            HttpResponse.text(200, "{\"retcode\":0,\"message\":\"OK\"}")
                        }
                    }
                }

            val outcome = MysCoinCheckIn.run("stoken=s; mid=m; stuid=1; ltoken=l; account_id=1", "device", "2.109.0", transport)

            assertTrue(outcome.success)
            assertEquals(130, outcome.coinBalance)
            assertEquals(30, outcome.coinGained)
        }

    @Test
    fun `balance query keeps legacy web parameters separate from check-in`() =
        runBlocking {
            val requests = mutableListOf<HttpRequest>()
            val originalCookie = "account_id_v2=1; stoken_v2=v2-old; mid=MID; ltoken_v2=lt; ltmid_v2=MID; cookie_token=balance-token"
            val transport =
                HttpTransport { request ->
                    requests += request
                    if (request.method == HttpMethod.GET) {
                        HttpResponse.text(200, "{\"retcode\":0,\"message\":\"OK\",\"data\":{\"total_points\":100}}")
                    } else {
                        HttpResponse.text(200, "{\"retcode\":0,\"message\":\"OK\"}")
                    }
                }

            MysCoinCheckIn.run(
                cookie = originalCookie,
                deviceId = "DEVICE-ID",
                appVersion = "2.109.0",
                httpTransport = transport,
                stoken = "v2_check-in",
                mid = "MID",
                uid = "1",
            )

            val stateRequest = requests.first { it.method == HttpMethod.GET }
            val checkInRequest = requests.first { it.method == HttpMethod.POST }
            assertEquals(
                "stoken_v2=v2-old; mid=MID; ltoken_v2=lt; ltmid_v2=MID; account_id_v2=1",
                stateRequest.headers["Cookie"],
            )
            assertFalse(stateRequest.headers["Cookie"].orEmpty().contains("cookie_token"))
            assertEquals("https://webstatic.mihoyo.com", stateRequest.headers["Origin"])
            assertFalse(stateRequest.headers.containsKey("x-rpc-device_id"))
            assertFalse(stateRequest.headers.containsKey("x-rpc-device_fp"))
            assertTrue(checkInRequest.headers["Cookie"].orEmpty().contains("stoken_v2=v2-old"))
            assertEquals("DEVICE-ID", checkInRequest.headers["x-rpc-device_id"])
        }

    @Test
    fun `check-in preserves stoken_v2 field even when value has no v2 prefix`() =
        runBlocking {
            var captured: HttpRequest? = null
            val transport =
                HttpTransport { request ->
                    if (request.method == HttpMethod.POST) captured = request
                    HttpResponse.text(200, "{\"retcode\":0,\"message\":\"OK\"}")
                }

            MysCoinCheckIn.run(
                cookie = "stoken_v2=opaque-token; ltoken_v2=ltoken-v2; ltmid_v2=mid-v2; account_id_v2=10001",
                deviceId = "DEVICE-ID",
                appVersion = "2.109.0",
                httpTransport = transport,
                stoken = "opaque-token",
                mid = "mid-v2",
                uid = "10001",
            )

            val cookie = captured?.headers?.get("Cookie").orEmpty()
            assertTrue(cookie.contains("stoken_v2=opaque-token"))
            assertFalse(cookie.contains("stoken=opaque-token"))
            assertTrue(cookie.contains("ltoken_v2=ltoken-v2"))
            assertTrue(cookie.contains("account_id_v2=10001"))
        }

    @Test
    fun `check-in detects v2 from sanitized companion cookie fields`() =
        runBlocking {
            var captured: HttpRequest? = null
            val transport =
                HttpTransport { request ->
                    if (request.method == HttpMethod.POST) captured = request
                    HttpResponse.text(200, "{\"retcode\":0,\"message\":\"OK\"}")
                }

            MysCoinCheckIn.run(
                cookie = "ltoken_v2=ltoken-v2; ltmid_v2=mid-v2; account_id_v2=10001",
                deviceId = "DEVICE-ID",
                appVersion = "2.109.0",
                httpTransport = transport,
                stoken = "opaque-token",
                mid = "mid-v2",
                uid = "10001",
            )

            val cookie = captured?.headers?.get("Cookie").orEmpty()
            assertTrue(cookie.contains("stoken_v2=opaque-token"))
            assertFalse(cookie.contains("stoken=opaque-token"))
            assertTrue(cookie.contains("ltoken_v2=ltoken-v2"))
        }

    @Test
    fun `uses fixed genshin community check-in request without retry`() =
        runBlocking {
            var captured: HttpRequest? = null
            val transport =
                HttpTransport { request ->
                    if (request.method == HttpMethod.POST) captured = request
                    HttpResponse.text(200, "{\"retcode\":0,\"message\":\"OK\"}")
                }

            val outcome = MysCoinCheckIn.run("stoken=s; mid=m; stuid=1; ltoken=l; account_id=1; cookie_token=test", "DEVICE-ID", "2.109.0", transport)

            assertTrue(outcome.success)
            assertFalse(outcome.alreadyDone)
            assertEquals(HttpMethod.POST, captured?.method)
            assertEquals("https://bbs-api.miyoushe.com/apihub/app/api/signIn", captured?.url)
            assertEquals("{\"gids\":\"2\"}", captured?.body)
            assertEquals(HttpRetryMode.NEVER, captured?.config?.retryMode)
            assertEquals("bbs-api.miyoushe.com", captured?.headers?.get("Host"))
            assertEquals("DEVICE-ID", captured?.headers?.get("x-rpc-device_id"))
            assertEquals("2", captured?.headers?.get("x-rpc-client_type"))
            assertEquals("2.109.0", captured?.headers?.get("x-rpc-app_version"))
            assertEquals("12", captured?.headers?.get("x-rpc-sys_version"))
            assertEquals("Mi 6", captured?.headers?.get("x-rpc-device_model"))
            assertEquals("Xiaomi MI 6", captured?.headers?.get("x-rpc-device_name"))
            assertEquals("okhttp/4.9.3", captured?.headers?.get("User-Agent"))
            assertEquals("discussion", captured?.headers?.get("x-rpc-csm_source"))
            assertFalse(captured?.headers?.containsKey("x-rpc-app_id") == true)
            assertFalse(captured?.headers?.containsKey("Origin") == true)
            assertFalse(captured?.headers?.containsKey("X-Requested-With") == true)
            assertTrue(
                captured
                    ?.headers
                    ?.get("DS")
                    .orEmpty()
                    .isNotBlank(),
            )
        }

    @Test
    fun `already checked in is a completed outcome`() =
        runBlocking {
            val transport =
                HttpTransport {
                    HttpResponse.text(200, "{\"retcode\":-5003,\"message\":\"今日已签到\"}")
                }

            val outcome = MysCoinCheckIn.run("stoken=s; mid=m; stuid=1; ltoken=l; account_id=1", "device", "2.109.0", transport)

            assertTrue(outcome.success)
            assertTrue(outcome.alreadyDone)
            assertEquals("原神社区今日已打卡", outcome.message)
        }

    @Test
    fun `rate limited duplicate wording is not treated as already done`() =
        runBlocking {
            // “请勿重复打卡”曾命中旧正则的“重复”关键词，retcode 非 -5003 时必须按失败处理，
            // 否则限流会被误判为已打卡并写入日历，导致当天后续定时打卡被跳过。
            val transport =
                HttpTransport {
                    HttpResponse.text(200, "{\"retcode\":-1001,\"message\":\"操作过于频繁，请勿重复打卡\"}")
                }

            val outcome = MysCoinCheckIn.run("stoken=s; mid=m; stuid=1; ltoken=l; account_id=1", "device", "2.109.0", transport)

            assertFalse(outcome.success)
            assertFalse(outcome.alreadyDone)
        }

    @Test
    fun `server error preserves original retcode and message in detail`() =
        runBlocking {
            val transport =
                HttpTransport {
                    HttpResponse.text(200, "{\"retcode\":1034,\"message\":\"Risk verification required\"}")
                }

            val outcome = MysCoinCheckIn.run("stoken=s; mid=m; stuid=1; ltoken=l; account_id=1", "device", "2.109.0", transport)

            assertFalse(outcome.success)
            assertTrue(outcome.detail.contains("retcode=1034"))
            assertTrue(outcome.detail.contains("Risk verification required"))
        }

    @Test
    fun `incomplete credentials report missing fields without sending request`() =
        runBlocking {
            var requestCount = 0
            val transport =
                HttpTransport {
                    requestCount++
                    HttpResponse.text(200, "{\"retcode\":0}")
                }

            val outcome = MysCoinCheckIn.run("account_id=2; cookie_token=web", "device", "2.109.0", transport, uid = "1")

            assertFalse(outcome.success)
            assertEquals(0, requestCount)
            assertTrue(outcome.detail.contains("V2 Cookie 不完整"))
            assertTrue(outcome.detail.contains("尝试回退 V1"))
            assertTrue(outcome.detail.contains("身份冲突"))
        }

    @Test
    fun `successful check-in preserves result when balance reads fail`() =
        runBlocking {
            var getCount = 0
            val transport =
                HttpTransport { request ->
                    if (request.method == HttpMethod.GET) {
                        getCount++
                        HttpResponse.text(503, "{\"retcode\":-502,\"message\":\"service unavailable\"}")
                    } else {
                        HttpResponse.text(200, "{\"retcode\":0,\"message\":\"OK\"}")
                    }
                }

            val outcome = MysCoinCheckIn.run("stoken=s; mid=m; stuid=1; ltoken=l; account_id=1", "device", "2.109.0", transport)

            assertTrue(outcome.success)
            assertEquals(2, getCount)
            assertTrue(outcome.warning.contains("签到前余额查询失败"))
            assertTrue(outcome.warning.contains("签到后余额查询失败"))
            assertTrue(outcome.detail.contains("retcode=-502"))
        }
}
