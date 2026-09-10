package com.questtick.net

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class HttpMockWebServerTest {
    private lateinit var server: MockWebServer
    private lateinit var transport: OkHttpTransport

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        transport = OkHttpTransport.forSameOriginTest(OkHttpClient.Builder().build())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun sameOriginRedirectReturnsValidatedFinalUrl() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/final"))
            server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

            val response = transport.get(server.url("/start").toString(), emptyMap())

            assertEquals("ok", response.body)
            assertEquals(server.url("/final").toString(), response.finalUrl)
            assertEquals("/start", server.takeRequest().path)
            assertEquals("/final", server.takeRequest().path)
        }

    @Test
    fun defaultGetDoesNotRetryWithoutExplicitIdempotency() =
        runBlocking {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            server.enqueue(MockResponse().setResponseCode(200).setBody("must-not-run"))

            var failure: HttpFailure? = null
            try {
                transport.get(server.url("/one-shot-get").toString(), emptyMap())
            } catch (e: HttpTransportException) {
                failure = e.failure
            }

            assertTrue(failure != null)
            assertEquals(1, server.requestCount)
        }

    @Test
    fun idempotentGetRetriesOneTransportFailureOnly() =
        runBlocking {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            server.enqueue(MockResponse().setResponseCode(200).setBody("recovered"))

            val response = transport.getIdempotent(server.url("/retry-get").toString(), emptyMap())

            assertEquals("recovered", response.body)
            assertEquals(2, server.requestCount)
        }

    @Test
    fun idempotentGetStopsAfterSecondTransportFailure() =
        runBlocking {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            server.enqueue(MockResponse().setResponseCode(200).setBody("must-not-run"))

            var failure: HttpFailure? = null
            try {
                transport.getIdempotent(server.url("/retry-get-twice").toString(), emptyMap())
            } catch (e: HttpTransportException) {
                failure = e.failure
            }

            assertTrue(failure != null)
            assertEquals(2, server.requestCount)
        }

    @Test
    fun idempotentGetRetriesInterruptedResponseBodyOnce() =
        runBlocking {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("first-response-is-incomplete")
                    .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
            )
            server.enqueue(MockResponse().setResponseCode(200).setBody("complete"))

            val response = transport.getIdempotent(server.url("/interrupted-get").toString(), emptyMap())

            assertEquals("complete", response.body)
            assertEquals(2, server.requestCount)
        }

    @Test
    fun postAfterRequestDisconnectIsNeverResentAndOutcomeIsUnknown() =
        runBlocking {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            server.enqueue(MockResponse().setResponseCode(200).setBody("must-not-run"))

            var failure: HttpFailure? = null
            try {
                transport.postJson(
                    server.url("/unsafe-post").toString(),
                    emptyMap(),
                    JSONObject().put("action", "sign"),
                )
            } catch (e: HttpTransportException) {
                failure = e.failure
            }

            assertTrue(failure?.outcomeUnknown == true)
            assertEquals(1, server.requestCount)
        }

    @Test
    fun postInterruptedResponseBodyIsNeverResentAndOutcomeIsUnknown() =
        runBlocking {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("partial-response")
                    .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
            )
            server.enqueue(MockResponse().setResponseCode(200).setBody("must-not-run"))

            var failure: HttpFailure? = null
            try {
                transport.postJson(
                    server.url("/interrupted-post").toString(),
                    emptyMap(),
                    JSONObject().put("action", "sign"),
                )
            } catch (e: HttpTransportException) {
                failure = e.failure
            }

            assertTrue(failure?.outcomeUnknown == true)
            assertEquals(HttpFailureKind.RESPONSE_INTERRUPTED, failure?.kind)
            assertEquals(1, server.requestCount)
        }

    @Test
    fun oversizedPostResponseIsOutcomeUnknownAndNotResent() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(200).setBody("0123456789"))
            server.enqueue(MockResponse().setResponseCode(200).setBody("must-not-run"))

            var failure: HttpFailure? = null
            try {
                transport.execute(
                    HttpRequest(
                        method = HttpMethod.POST,
                        url = server.url("/large-post").toString(),
                        body = "payload",
                        contentType = "text/plain",
                        config = HttpRequestConfig(maxResponseBytes = 5),
                    ),
                )
            } catch (e: HttpTransportException) {
                failure = e.failure
            }

            assertTrue(failure?.outcomeUnknown == true)
            assertEquals(HttpFailureKind.RESPONSE_TOO_LARGE, failure?.kind)
            assertEquals(1, server.requestCount)
        }

    @Test
    fun completeHttpErrorResponseIsNotRetriedByTransport() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(503).setBody("busy"))
            server.enqueue(MockResponse().setResponseCode(200).setBody("must-not-run"))

            val response = transport.get(server.url("/http-error").toString(), emptyMap())

            assertEquals(503, response.code)
            assertEquals(1, server.requestCount)
        }

    @Test
    fun transportDisablesInjectedAutomaticRetry() {
        val injected = OkHttpClient.Builder().retryOnConnectionFailure(true).build()
        val safeTransport = OkHttpTransport.forSameOriginTest(injected)

        assertFalse(safeTransport.client.retryOnConnectionFailure)
    }

    @Test
    fun productionTransportRejectsUnknownInitialHostBeforeRequest() =
        runBlocking {
            val productionTransport = OkHttpTransport(OkHttpClient.Builder().build())
            var rejected = false
            try {
                productionTransport.get(server.url("/unknown").toString(), emptyMap())
            } catch (error: HttpTransportException) {
                rejected = error.failure.kind == HttpFailureKind.SECURITY_REJECTED
            }

            assertTrue(rejected)
            assertEquals(0, server.requestCount)
        }

    @Test
    fun loginApiSuccessCanBeParsed() =
        runBlocking {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("""{"retcode":0,"data":{"ticket":"ticket_1","url":"https://example.test/qr"}}"""),
            )

            val result = transport.getResult(server.url("/login").toString(), emptyMap())

            assertTrue(result is ApiResult.Success<*>)
            val json = (result as ApiResult.Success<HttpResponse>).data.json()
            assertEquals(0, json.optInt("retcode"))
            assertEquals("ticket_1", json.optJSONObject("data")?.optString("ticket"))
        }

    @Test
    fun riskControlApiErrorCanBeClassified() =
        runBlocking {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("""{"retcode":1034,"message":"需要完成验证码"}"""),
            )

            val response = transport.get(server.url("/risk").toString(), emptyMap())
            val json = response.json()
            val result =
                if (json.optInt("retcode") == 1034) {
                    ApiResult.RiskControlError(json.optString("message"), response.body)
                } else {
                    ApiResult.Success(response)
                }

            assertTrue(result is ApiResult.RiskControlError)
            assertEquals("需要完成验证码", (result as ApiResult.RiskControlError).message)
        }

    @Test
    fun signinHttpErrorReturnsApiError() =
        runBlocking {
            server.enqueue(
                MockResponse()
                    .setResponseCode(500)
                    .setBody("server busy"),
            )

            val result =
                transport.postJsonResult(
                    server.url("/signin").toString(),
                    emptyMap(),
                    JSONObject().put("act_id", "e202311201442471"),
                )

            assertTrue(result is ApiResult.Error)
            assertEquals(500, (result as ApiResult.Error).code)
        }

    @Test
    fun oversizedResponseFailsInsteadOfReturningTruncatedJson() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(200).setBody("0123456789"))

            var limit = 0
            try {
                transport.get(
                    server.url("/large").toString(),
                    emptyMap(),
                    config = HttpRequestConfig(maxResponseBytes = 5),
                )
            } catch (e: HttpTransportException) {
                if (e.failure.kind == HttpFailureKind.RESPONSE_TOO_LARGE) limit = 5
            }

            assertEquals(5, limit)
        }

    @Test
    fun requestSpecificCallTimeoutMapsToStructuredReadTimeout() =
        runBlocking {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("slow")
                    .setHeadersDelay(2, TimeUnit.SECONDS),
            )

            var failure: HttpFailure? = null
            try {
                transport.get(
                    server.url("/request-timeout").toString(),
                    config =
                        HttpRequestConfig(
                            connectTimeoutMillis = 1_000,
                            readTimeoutMillis = 1_000,
                            writeTimeoutMillis = 1_000,
                            callTimeoutMillis = 100,
                        ),
                )
            } catch (error: HttpTransportException) {
                failure = error.failure
            }

            assertEquals(HttpFailureKind.READ_TIMEOUT, failure?.kind)
            assertEquals(1, server.requestCount)
        }

    @Test
    fun bufferedTransportRejectsOversizedConfiguredLimitBeforeNetwork() =
        runBlocking {
            var failure: HttpFailure? = null
            try {
                transport.get(
                    server.url("/must-not-run").toString(),
                    config =
                        HttpRequestConfig(
                            maxResponseBytes = HttpRequestConfig.MAX_BUFFERED_RESPONSE_BYTES + 1,
                        ),
                )
            } catch (error: HttpTransportException) {
                failure = error.failure
            }

            assertEquals(HttpFailureKind.SECURITY_REJECTED, failure?.kind)
            assertEquals(0, server.requestCount)
        }

    @Test
    fun streamingResponseLimitIsEnforcedWithoutBufferingWholeBody() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(200).setBody("0123456789"))
            val request =
                HttpRequest(
                    method = HttpMethod.GET,
                    url = server.url("/stream-too-large").toString(),
                    config = HttpRequestConfig(maxResponseBytes = 5),
                )

            var failure: HttpFailure? = null
            try {
                transport.executeStreaming(request) { response ->
                    response.copyTo(java.io.ByteArrayOutputStream())
                }
            } catch (error: HttpTransportException) {
                failure = error.failure
            }

            assertEquals(HttpFailureKind.RESPONSE_TOO_LARGE, failure?.kind)
            assertEquals(1, server.requestCount)
        }

    @Test
    fun interruptedStreamingReadIsOneShot() =
        runBlocking {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("incomplete-stream")
                    .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
            )
            server.enqueue(MockResponse().setResponseCode(200).setBody("must-not-run"))
            val request =
                HttpRequest(
                    method = HttpMethod.GET,
                    url = server.url("/interrupted-stream").toString(),
                )

            var failure: HttpFailure? = null
            try {
                transport.executeStreaming(request) { response ->
                    response.copyTo(java.io.ByteArrayOutputStream())
                }
            } catch (error: HttpTransportException) {
                failure = error.failure
            }

            assertEquals(HttpFailureKind.RESPONSE_INTERRUPTED, failure?.kind)
            assertEquals(1, server.requestCount)
        }

    @Test
    fun streamingConsumerFailureEscapesWithoutTransportRemappingOrRetry() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(200).setBody("payload"))
            server.enqueue(MockResponse().setResponseCode(200).setBody("must-not-run"))
            val request =
                HttpRequest(
                    method = HttpMethod.GET,
                    url = server.url("/consumer-failure").toString(),
                )

            var observed: Throwable? = null
            try {
                transport.executeStreaming(request) {
                    throw IllegalStateException("local consumer failure")
                }
            } catch (error: Throwable) {
                observed = error
            }

            assertTrue(observed is IllegalStateException)
            assertEquals("local consumer failure", observed.message)
            assertEquals(1, server.requestCount)
        }

    @Test
    fun streamingTransportRejectsRetryModeBeforeNetwork() =
        runBlocking {
            val request =
                HttpRequest(
                    method = HttpMethod.GET,
                    url = server.url("/must-not-run").toString(),
                    config = HttpRequestConfig(retryMode = HttpRetryMode.IDEMPOTENT_READ),
                )

            var failure: HttpFailure? = null
            try {
                transport.executeStreaming(request) { response -> response.code }
            } catch (error: HttpTransportException) {
                failure = error.failure
            }

            assertEquals(HttpFailureKind.SECURITY_REJECTED, failure?.kind)
            assertEquals(0, server.requestCount)
        }

    @Test
    fun cancellingRequestPropagatesCancellationAndCancelsOkHttpCall() =
        runBlocking {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("""{"retcode":0}""")
                    .setHeadersDelay(5, TimeUnit.SECONDS),
            )

            var cancelled = false
            try {
                withTimeout(100L) {
                    transport.getResult(server.url("/slow").toString(), emptyMap())
                }
            } catch (_: TimeoutCancellationException) {
                cancelled = true
            }

            assertTrue(cancelled)
            repeat(20) {
                if (transport.client.dispatcher.runningCallsCount() == 0) return@repeat
                delay(25L)
            }
            assertEquals(0, transport.client.dispatcher.runningCallsCount())
        }
}
