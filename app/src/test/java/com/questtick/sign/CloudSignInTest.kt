package com.questtick.sign

import com.questtick.data.FailureCategory
import com.questtick.net.OkHttpTransport
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudSignInTest {
    private var server: MockWebServer? = null

    @After
    fun tearDown() {
        server?.shutdown()
    }

    @Test
    fun `claimed free time is calculated from before and after wallet free time`() {
        val claimed =
            CloudSignIn.calculateClaimedFreeTime(
                beforeFreeTime = 120,
                afterFreeTime = 135,
            )

        assertEquals(15, claimed)
    }

    @Test
    fun `claimed free time never becomes negative when after time decreases`() {
        val claimed =
            CloudSignIn.calculateClaimedFreeTime(
                beforeFreeTime = 135,
                afterFreeTime = 120,
            )

        assertEquals(0, claimed)
    }

    @Test
    fun `cloud sign message uses free time delta instead of send freetime`() =
        runBlocking {
            server =
                MockWebServer().apply {
                    enqueue(walletResponse(freeTime = 120, sendFreeTime = 999))
                    enqueue(
                        MockResponse().setResponseCode(200).setBody(
                            """{"retcode":0,"message":"OK","data":{"list":[{"id":"popup_1"}]}}""",
                        ),
                    )
                    enqueue(MockResponse().setResponseCode(200).setBody("""{"retcode":0,"message":"OK"}"""))
                    enqueue(walletResponse(freeTime = 135, sendFreeTime = 999))
                    start()
                }
            val transport = OkHttpTransport.forSameOriginTest(OkHttpClient.Builder().build())
            val game =
                CloudSignIn.GameConfig(
                    key = "TestCloud",
                    name = "测试云游戏",
                    baseURL = server!!.url("/cg").toString().removeSuffix("/"),
                    gameHeaders =
                        mapOf(
                            "Host" to server!!.hostName,
                            "x-rpc-app_version" to "1.0.0",
                        ),
                )

            val outcome = CloudSignIn("device-id", transport).runForToken("token", game)

            assertTrue(outcome.success)
            assertTrue(outcome.message.contains("本次+15分钟"))
            assertFalse(outcome.message.contains("999"))
            assertTrue(outcome.detail.contains("claimed=15"))
        }

    @Test
    fun `http auth failure cannot be disguised by successful json body`() =
        runBlocking {
            server =
                MockWebServer().apply {
                    enqueue(
                        MockResponse()
                            .setResponseCode(401)
                            .setBody(
                                """{"retcode":0,"message":"OK","data":{"free_time":{"free_time":120}}}""",
                            ),
                    )
                    start()
                }
            val transport = OkHttpTransport.forSameOriginTest(OkHttpClient.Builder().build())
            val game =
                CloudSignIn.GameConfig(
                    key = "TestCloud",
                    name = "测试云游戏",
                    baseURL = server!!.url("/cg").toString().removeSuffix("/"),
                    gameHeaders = mapOf("Host" to server!!.hostName, "x-rpc-app_version" to "1.0.0"),
                )

            val outcome = CloudSignIn("device-id", transport).runForToken("expired-token", game)

            assertFalse(outcome.success)
            assertEquals(FailureCategory.AUTH_EXPIRED, outcome.failure.category)
            assertEquals("http:401", outcome.failure.errorCode)
            assertEquals(1, server!!.requestCount)
        }

    @Test
    fun `auth failure does not probe fallback client type`() =
        runBlocking {
            server =
                MockWebServer().apply {
                    enqueue(
                        MockResponse()
                            .setResponseCode(200)
                            .setBody("""{"retcode":-100,"message":"登录状态已失效"}"""),
                    )
                    start()
                }
            val transport = OkHttpTransport.forSameOriginTest(OkHttpClient.Builder().build())
            val game =
                CloudSignIn.GameConfig(
                    key = "TestCloud",
                    name = "测试云游戏",
                    baseURL = server!!.url("/cg").toString().removeSuffix("/"),
                    gameHeaders = mapOf("Host" to server!!.hostName, "x-rpc-app_version" to "1.0.0"),
                )

            val outcome = CloudSignIn("device-id", transport).runForToken("expired-token", game)

            assertFalse(outcome.success)
            assertEquals(FailureCategory.AUTH_EXPIRED, outcome.failure.category)
            assertEquals("retcode:-100", outcome.failure.errorCode)
            assertEquals(1, server!!.requestCount)
        }

    @Test
    fun `ack response interruption becomes result unknown without resending post`() =
        runBlocking {
            server =
                MockWebServer().apply {
                    enqueue(walletResponse(freeTime = 120, sendFreeTime = 0))
                    enqueue(
                        MockResponse().setResponseCode(200).setBody(
                            """{"retcode":0,"message":"OK","data":{"list":[{"id":"popup_1"}]}}""",
                        ),
                    )
                    enqueue(
                        MockResponse()
                            .setResponseCode(200)
                            .setBody("partial")
                            .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
                    )
                    enqueue(MockResponse().setResponseCode(200).setBody("must-not-run"))
                    start()
                }
            val transport = OkHttpTransport.forSameOriginTest(OkHttpClient.Builder().build())
            val game =
                CloudSignIn.GameConfig(
                    key = "TestCloud",
                    name = "测试云游戏",
                    baseURL = server!!.url("/cg").toString().removeSuffix("/"),
                    gameHeaders = mapOf("Host" to server!!.hostName, "x-rpc-app_version" to "1.0.0"),
                )

            val outcome = CloudSignIn("device-id", transport).runForToken("token", game)

            assertFalse(outcome.success)
            assertEquals(FailureCategory.RESULT_UNKNOWN, outcome.failure.category)
            assertFalse(outcome.failure.retryable)
            assertEquals(3, server!!.requestCount)
        }

    private fun walletResponse(
        freeTime: Int,
        sendFreeTime: Int,
    ): MockResponse =
        MockResponse().setResponseCode(200).setBody(
            """
            {
              "retcode": 0,
              "message": "OK",
              "data": {
                "free_time": {
                  "free_time": $freeTime,
                  "send_freetime": $sendFreeTime
                },
                "total_time": $freeTime
              }
            }
            """.trimIndent(),
        )
}
