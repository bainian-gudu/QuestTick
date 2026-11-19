package com.questtick.sign

import com.questtick.net.HttpMethod
import com.questtick.net.HttpRequest
import com.questtick.net.HttpResponse
import com.questtick.net.HttpRetryMode
import com.questtick.net.HttpTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
                                HttpResponse.text(
                                    200,
                                    "{\"retcode\":0,\"message\":\"OK\",\"data\":{" +
                                        "\"total_points\":100,\"already_received_points\":0}}",
                                )
                            } else {
                                HttpResponse.text(
                                    200,
                                    "{\"retcode\":0,\"message\":\"OK\",\"data\":{" +
                                        "\"total_points\":130,\"already_received_points\":30}}",
                                )
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
    fun `balance lookup scans nested objects and arrays`() =
        runBlocking {
            val transport =
                HttpTransport { request ->
                    if (request.method == HttpMethod.GET) {
                        HttpResponse.text(
                            200,
                            """{"retcode":0,"message":"OK","data":{"items":[{"meta":{"total_points":321}}]}}""",
                        )
                    } else {
                        HttpResponse.text(200, "{\"retcode\":0,\"message\":\"OK\"}")
                    }
                }

            val outcome = MysCoinCheckIn.run("stoken=s; mid=m; stuid=1; ltoken=l; account_id=1", "device", "2.109.0", transport)

            assertEquals(321, outcome.coinBalance)
            assertEquals(0, outcome.coinGained)
        }

    @Test
    fun `state and check-in share the app cookie and differ only in ds algorithm`() =
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
            // App 组合只带 stuid / stoken / mid：网页字段（ltoken、cookie_token、*_v2）一律不混入。
            val cookie = stateRequest.headers["Cookie"].orEmpty()
            assertEquals("stuid=1; stoken=v2-old; mid=MID", cookie)
            assertFalse(cookie.contains("cookie_token"))
            assertFalse(cookie.contains("ltoken"))
            assertEquals(cookie, checkInRequest.headers["Cookie"])
            // 读接口与打卡共用一套 App 头。
            assertEquals(Endpoints.BBS_HOST, stateRequest.headers["Host"])
            assertEquals("DEVICE-ID", stateRequest.headers["x-rpc-device_id"])
            assertEquals(Endpoints.VERIFY_KEY, stateRequest.headers["x-rpc-verify_key"])
            assertEquals("application/json; charset=UTF-8", checkInRequest.headers["Content-Type"])
            assertNull(stateRequest.headers["Content-Type"])
            // 任务列表走移动端 md5_v1（6 位 base36 + salt），打卡走 x6 md5_v2（6 位数字 + body）。
            val stateDs = stateRequest.headers["DS"].orEmpty()
            val checkInDs = checkInRequest.headers["DS"].orEmpty()
            assertTrue(stateDs.matches(Regex("\\d+,[a-z0-9]{6},[0-9a-f]{32}")))
            assertTrue(checkInDs.matches(Regex("\\d+,\\d{6},[0-9a-f]{32}")))
        }

    @Test
    fun `app cookie keeps plain stoken field for v2 style token`() =
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
            // App 接口读的是 stoken（v2 令牌也只是值带 v2_ 前缀），浏览器里的 stoken_v2 / ltoken_v2 不混入。
            assertEquals("stuid=10001; stoken=opaque-token; mid=mid-v2", cookie)
        }

    @Test
    fun `browser style v2 cookie is the last fallback when app cookie is rejected`() =
        runBlocking {
            val postedCookies = mutableListOf<String>()
            val transport =
                HttpTransport { request ->
                    when (request.method) {
                        HttpMethod.GET -> {
                            HttpResponse.text(200, "{\"retcode\":0,\"message\":\"OK\",\"data\":{\"total_points\":100}}")
                        }

                        else -> {
                            postedCookies += request.headers["Cookie"].orEmpty()
                            if (postedCookies.size == 1) {
                                HttpResponse.text(200, "{\"retcode\":-100,\"message\":\"登录失效，请重新登录\"}")
                            } else {
                                HttpResponse.text(200, "{\"retcode\":0,\"message\":\"OK\"}")
                            }
                        }
                    }
                }

            val outcome =
                MysCoinCheckIn.run(
                    cookie = "stoken_v2=v2-token; mid=MID; ltoken_v2=lt; ltmid_v2=MID; account_id_v2=1",
                    deviceId = "device",
                    appVersion = "2.109.0",
                    httpTransport = transport,
                    uid = "1",
                )

            assertTrue(outcome.success)
            assertEquals(2, postedCookies.size)
            assertEquals("stuid=1; stoken=v2-token; mid=MID", postedCookies[0])
            assertTrue(postedCookies[1].startsWith("stoken_v2=v2-token"))
            assertTrue(outcome.detail.contains("cookieVariant=v2"))
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

            val outcome =
                MysCoinCheckIn.run(
                    "stoken=s; mid=m; stuid=1; ltoken=l; account_id=1; cookie_token=test",
                    "DEVICE-ID",
                    "2.109.0",
                    transport,
                )

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
            // 纯 JVM 单测里读不到真实 SDK 级别，回退到常量；真机上会用 Build.VERSION.SDK_INT。
            assertEquals(Endpoints.effectiveSysVersion(0), captured?.headers?.get("x-rpc-sys_version"))
            assertEquals("Mi 6", captured?.headers?.get("x-rpc-device_model"))
            assertEquals("Xiaomi MI 6", captured?.headers?.get("x-rpc-device_name"))
            assertEquals("okhttp/4.9.3", captured?.headers?.get("User-Agent"))
            // 与真实 App 抓包无关的头一律不发，避免把客户端指纹写成第三方脚本的形状。
            assertFalse(captured?.headers?.containsKey("x-rpc-csm_source") == true)
            assertFalse(captured?.headers?.containsKey("x-rpc-h265_supported") == true)
            assertFalse(captured?.headers?.containsKey("Accept-Encoding") == true)
            assertFalse(captured?.headers?.containsKey("Connection") == true)
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
            assertEquals("原神今日已打卡", outcome.message)
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
                    HttpResponse.text(200, "{\"retcode\":-1,\"message\":\"Risk verification required\"}")
                }

            val outcome = MysCoinCheckIn.run("stoken=s; mid=m; stuid=1; ltoken=l; account_id=1", "device", "2.109.0", transport)

            assertFalse(outcome.success)
            assertTrue(outcome.detail.contains("retcode=-1"))
            assertTrue(outcome.detail.contains("Risk verification required"))
        }

    @Test
    fun `captcha requirement surfaces actionable hint`() =
        runBlocking {
            val transport = stateThenSign("{\"retcode\":1034,\"message\":\"需要验证码\"}")

            val outcome = MysCoinCheckIn.run("stoken=s; mid=m; stuid=1; ltoken=l; account_id=1", "device", "2.109.0", transport)

            assertFalse(outcome.success)
            assertTrue(outcome.message.contains("验证码"))
            assertTrue(outcome.detail.contains("retcode=1034"))
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
            assertTrue(outcome.detail.contains("App Cookie 不完整"))
            assertTrue(outcome.detail.contains("V2 Cookie 不完整"))
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
            // 两个候选组合各探测一次读接口，都失败后再补一次签到后余额查询。
            assertEquals(3, getCount)
            assertTrue(outcome.warning.contains("签到前余额查询失败"))
            assertTrue(outcome.warning.contains("签到后余额查询失败"))
            assertTrue(outcome.detail.contains("retcode=-502"))
        }

    @Test
    fun `login invalid retries the next cookie candidate`() =
        runBlocking {
            val postedCookies = mutableListOf<String>()
            var signCalls = 0
            val transport =
                HttpTransport { request ->
                    when (request.method) {
                        HttpMethod.GET -> {
                            HttpResponse.text(200, "{\"retcode\":0,\"message\":\"OK\",\"data\":{\"total_points\":100}}")
                        }

                        else -> {
                            signCalls++
                            postedCookies += request.headers["Cookie"].orEmpty()
                            if (signCalls == 1) {
                                HttpResponse.text(200, "{\"retcode\":-100,\"message\":\"登录失效，请重新登录\"}")
                            } else {
                                HttpResponse.text(200, "{\"retcode\":0,\"message\":\"OK\"}")
                            }
                        }
                    }
                }

            val outcome =
                MysCoinCheckIn.run(
                    cookie = "account_id=1; ltoken=from-cookie",
                    deviceId = "device",
                    appVersion = "2.109.0",
                    httpTransport = transport,
                    stoken = "s",
                    mid = "m",
                    uid = "1",
                )

            assertTrue(outcome.success)
            assertEquals(2, signCalls)
            assertTrue(postedCookies[0].startsWith("stuid=1; stoken=s"))
            assertTrue(postedCookies[1].startsWith("stoken=s; mid=m"))
            assertTrue(outcome.detail.contains("cookieVariant=v1"))
        }

    @Test
    fun `state and sign requests use app endpoints with app cookie`() =
        runBlocking {
            val urls = mutableListOf<String>()
            val cookies = mutableListOf<String>()
            val transport =
                HttpTransport { request ->
                    urls += request.url
                    cookies += request.headers["Cookie"].orEmpty()
                    if (request.method == HttpMethod.GET) {
                        HttpResponse.text(200, "{\"retcode\":0,\"message\":\"OK\",\"data\":{\"total_points\":10}}")
                    } else {
                        HttpResponse.text(200, "{\"retcode\":0,\"message\":\"OK\"}")
                    }
                }

            MysCoinCheckIn.run("", "device", "2.109.0", transport, stoken = "v2_token", mid = "m", uid = "1", ltoken = "l")

            // 第一次是读接口（兼作登录态探测），签到请求紧随其后，最后再补一次读接口刷新余额。
            assertTrue(urls.first().contains("/apihub/sapi/getUserMissionsState"))
            val signIndex = urls.indexOfFirst { it.contains("/apihub/app/api/signIn") }
            assertTrue(signIndex > 0)
            assertEquals(urls.size - 1, urls.indexOfLast { it.contains("/apihub/sapi/getUserMissionsState") })
            // App 组合只带 stuid / stoken / mid，不混入 ltoken 等网页字段。
            assertEquals("stuid=1; stoken=v2_token; mid=m", cookies.first())
            assertEquals(cookies.first(), cookies[signIndex])
        }

    @Test
    fun `v2 prefixed stoken without mid reports missing mid`() =
        runBlocking {
            var requestCount = 0
            val transport =
                HttpTransport {
                    requestCount++
                    HttpResponse.text(200, "{\"retcode\":0,\"message\":\"OK\"}")
                }

            val outcome = MysCoinCheckIn.run("account_id=1; ltoken=l", "device", "2.109.0", transport, stoken = "v2_x", uid = "1")

            assertFalse(outcome.success)
            assertEquals(0, requestCount)
            assertTrue(outcome.detail.contains("mid"))
        }

    @Test
    fun `already done code 1008 marks task as done`() =
        runBlocking {
            val outcome =
                MysCoinCheckIn.run(
                    "stoken=s; mid=m; stuid=1; ltoken=l; account_id=1",
                    "device",
                    "2.109.0",
                    stateThenSign("{\"retcode\":1008,\"message\":\"今日已打卡\"}"),
                )

            assertTrue(outcome.success)
            assertTrue(outcome.alreadyDone)
        }

    @Test
    fun featureFollowsBuildTypeAndExperimentSwitch() {
        // Debug 版默认开放，正式版必须由用户在实验功能里显式开启。
        assertTrue(coinFeatureEnabled(debugBuild = true, enabledBySetting = false))
        assertTrue(coinFeatureEnabled(debugBuild = true, enabledBySetting = true))
        assertTrue(coinFeatureEnabled(debugBuild = false, enabledBySetting = true))
        assertFalse(coinFeatureEnabled(debugBuild = false, enabledBySetting = false))
    }

    /** 读接口返回正常余额，签到接口返回给定 JSON。 */
    private fun stateThenSign(signJson: String): HttpTransport =
        HttpTransport { request ->
            when (request.method) {
                HttpMethod.GET -> {
                    HttpResponse.text(
                        200,
                        "{\"retcode\":0,\"message\":\"OK\",\"data\":{\"total_points\":100,\"already_received_points\":0}}",
                    )
                }

                else -> {
                    HttpResponse.text(200, signJson)
                }
            }
        }
}
