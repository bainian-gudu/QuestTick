package com.questtick.net

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HttpTransportContractTest {
    @Test
    fun fakeReceivesImmutableRequestSnapshotAndExplicitConfig() = runTest {
        val sourceHeaders = linkedMapOf("Cookie" to "secret-cookie")
        val config =
            HttpRequestConfig(
                maxResponseBytes = 1_024,
                connectTimeoutMillis = 1_100,
                readTimeoutMillis = 1_200,
                writeTimeoutMillis = 1_300,
                callTimeoutMillis = 1_400,
            )
        val fake = FakeHttpTransport { request -> HttpResponse.text(200, "ok", request.url) }

        fake.get("https://api-takumi.mihoyo.com/test", sourceHeaders, config)
        sourceHeaders["Cookie"] = "changed"

        val recorded = fake.requests.single()
        assertEquals(HttpMethod.GET, recorded.method)
        assertEquals("secret-cookie", recorded.headers["Cookie"])
        assertEquals(1_024, recorded.config.maxResponseBytes)
        assertEquals(1_100L, recorded.config.connectTimeoutMillis)
        assertEquals(1_200L, recorded.config.readTimeoutMillis)
        assertEquals(1_300L, recorded.config.writeTimeoutMillis)
        assertEquals(1_400L, recorded.config.callTimeoutMillis)
        assertEquals(HttpRetryMode.NEVER, recorded.config.retryMode)
    }

    @Test
    fun defaultGetAndPostForceOneShotWhileIdempotentGetIsExplicit() = runTest {
        val fake = FakeHttpTransport { request -> HttpResponse.text(200, "ok", request.url) }
        val retryConfig = HttpRequestConfig(retryMode = HttpRetryMode.IDEMPOTENT_READ)

        fake.get("https://api-takumi.mihoyo.com/default", config = retryConfig)
        fake.getResult("https://api-takumi.mihoyo.com/default-result", config = retryConfig)
        fake.getIdempotent("https://api-takumi.mihoyo.com/read")
        fake.postJson(
            "https://api-takumi.mihoyo.com/write",
            json = JSONObject().put("action", "sign"),
            config = retryConfig,
        )

        assertEquals(HttpRetryMode.NEVER, fake.requests[0].config.retryMode)
        assertEquals(HttpRetryMode.NEVER, fake.requests[1].config.retryMode)
        assertEquals(HttpRetryMode.IDEMPOTENT_READ, fake.requests[2].config.retryMode)
        assertEquals(HttpRetryMode.NEVER, fake.requests[3].config.retryMode)
        assertEquals(HttpMethod.POST, fake.requests[3].method)
        assertTrue(fake.requests[3].body.orEmpty().contains("\"action\":\"sign\""))
    }

    @Test
    fun nonIdempotentMethodCannotUseReadRetryMode() {
        try {
            HttpRequest(
                method = HttpMethod.POST,
                url = "https://api-takumi.mihoyo.com/write",
                body = "{}",
                contentType = "application/json",
                config = HttpRequestConfig(retryMode = HttpRetryMode.IDEMPOTENT_READ),
            )
            fail("POST must reject idempotent read retry mode")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun requestConfigRejectsUnboundedResponseAndTimeoutValues() {
        assertIllegalArgument { HttpRequestConfig(maxResponseBytes = 0) }
        assertIllegalArgument { HttpRequestConfig(maxResponseBytes = HttpRequestConfig.MAX_RESPONSE_BYTES + 1) }
        assertIllegalArgument { HttpRequestConfig(connectTimeoutMillis = 0) }
        assertIllegalArgument { HttpRequestConfig(readTimeoutMillis = HttpRequestConfig.MAX_TIMEOUT_MILLIS + 1) }
        assertIllegalArgument { HttpRequestConfig(writeTimeoutMillis = 0) }
        assertIllegalArgument { HttpRequestConfig(callTimeoutMillis = 0) }
    }

    @Test
    fun httpErrorResultDoesNotExposeResponseBody() = runTest {
        val fake =
            FakeHttpTransport { request ->
                HttpResponse.text(401, "secret-server-response", finalUrl = request.url)
            }

        val result = fake.getResult("https://api-takumi.mihoyo.com/test")

        assertTrue(result is ApiResult.Error)
        result as ApiResult.Error
        assertEquals(401, result.code)
        assertEquals("http:401", result.detail)
        assertFalse(result.detail.contains("secret-server-response"))
    }

    @Test
    fun resultMappingKeepsStructuredFailureWithoutLeakingRawExceptionMessage() = runTest {
        val failure =
            HttpFailure(
                kind = HttpFailureKind.READ_TIMEOUT,
                errorCode = "transport:read_timeout:request_sent",
                retryable = true,
                outcomeUnknown = false,
            )
        val fake =
            FakeHttpTransport {
                throw HttpTransportException(failure)
            }

        val result = fake.getResult("https://api-takumi.mihoyo.com/test")

        assertTrue(result is ApiResult.NetworkError)
        result as ApiResult.NetworkError
        assertEquals(failure, result.failure)
        assertEquals(failure.errorCode, result.message)
        assertEquals(failure.errorCode, result.detail)
        assertFalse(result.detail.contains("secret-response-detail"))
    }

    @Test
    fun resultMappingPropagatesCancellation() = runTest {
        val fake = FakeHttpTransport { throw CancellationException("cancelled") }

        try {
            fake.getResult("https://api-takumi.mihoyo.com/test")
            fail("CancellationException must propagate")
        } catch (_: CancellationException) {
        }
    }

    @Test
    fun requestAndResponseDiagnosticsRedactSensitiveFields() {
        val request =
            HttpRequest(
                method = HttpMethod.POST,
                url = "https://api-takumi.mihoyo.com/path?ticket=secret-ticket",
                headers = mapOf("Cookie" to "secret-cookie", "Authorization" to "secret-token"),
                body = "{\"token\":\"secret-body\"}",
                contentType = "application/json",
            )
        val response =
            HttpResponse.text(
                code = 200,
                body = "secret-response",
                finalUrl = "https://api-takumi.mihoyo.com/path?ticket=secret-ticket",
                headers = mapOf("Set-Cookie" to listOf("secret-cookie")),
            )

        val requestText = request.toString()
        val responseText = response.toString()
        listOf("secret-ticket", "secret-cookie", "secret-token", "secret-body").forEach { secret ->
            assertFalse(requestText.contains(secret))
        }
        listOf("secret-ticket", "secret-cookie", "secret-response").forEach { secret ->
            assertFalse(responseText.contains(secret))
        }
    }

    @Test
    fun responseCopiesBinaryBodyAndHeaderCollections() {
        val sourceBody = byteArrayOf(1, 2, 3)
        val sourceHeaderValues = mutableListOf("a")
        val response =
            HttpResponse.bytes(
                code = 200,
                body = sourceBody,
                headers = mapOf("X-Test" to sourceHeaderValues),
            )
        sourceBody[0] = 9
        sourceHeaderValues[0] = "changed"

        val firstCopy = response.bodyBytes()
        val secondCopy = response.bodyBytes()
        firstCopy[0] = 8

        assertArrayEquals(byteArrayOf(1, 2, 3), secondCopy)
        assertNotSame(firstCopy, secondCopy)
        assertEquals(listOf("a"), response.headerValues("x-test"))
    }

    @Test
    fun fakeStreamingTransportSupportsProgressLimitAndIsolation() = runTest {
        val first =
            FakeStreamingHttpTransport { request ->
                FakeStreamingResponse(200, byteArrayOf(1, 2, 3, 4), finalUrl = request.url)
            }
        val second =
            FakeStreamingHttpTransport { request ->
                FakeStreamingResponse(200, byteArrayOf(9), finalUrl = request.url)
            }
        val copiedProgress = mutableListOf<Long>()
        val output = java.io.ByteArrayOutputStream()
        val copied =
            first.executeStreaming(
                HttpRequest(
                    method = HttpMethod.GET,
                    url = "https://github.com/moon02222/MYS_Signin_Android/releases/download/v1/app.apk",
                    config = HttpRequestConfig(maxResponseBytes = 4),
                ),
            ) { response ->
                response.copyTo(output, bufferSize = 2, onBytesCopied = copiedProgress::add)
            }
        second.executeStreaming(
            HttpRequest(
                method = HttpMethod.GET,
                url = "https://github.com/moon02222/MYS_Signin_Android/releases/download/v2/app.apk",
            ),
        ) { response -> response.read(ByteArray(1)) }

        assertEquals(4L, copied)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), output.toByteArray())
        assertEquals(listOf(2L, 4L), copiedProgress)
        assertEquals(1, first.requests.size)
        assertEquals(1, second.requests.size)
    }

    @Test
    fun fakeStreamingResponseEnforcesConfiguredLimit() = runTest {
        val fake = FakeStreamingHttpTransport { FakeStreamingResponse(200, byteArrayOf(1, 2, 3)) }

        try {
            fake.executeStreaming(
                HttpRequest(
                    method = HttpMethod.GET,
                    url = "https://github.com/moon02222/MYS_Signin_Android/releases/download/v1/app.apk",
                    config = HttpRequestConfig(maxResponseBytes = 2),
                ),
            ) { response -> response.copyTo(java.io.ByteArrayOutputStream()) }
            fail("streaming response limit must be enforced")
        } catch (error: ResponseTooLargeException) {
            assertEquals(2, error.limitBytes)
        }
    }

    @Test
    fun fakeInstancesKeepRequestsIsolated() = runTest {
        val first = FakeHttpTransport { request -> HttpResponse.text(200, "first", request.url) }
        val second = FakeHttpTransport { request -> HttpResponse.text(200, "second", request.url) }

        first.get("https://api-takumi.mihoyo.com/first")
        second.get("https://api-takumi.mihoyo.com/second")

        assertEquals(listOf("https://api-takumi.mihoyo.com/first"), first.requests.map { it.url })
        assertEquals(listOf("https://api-takumi.mihoyo.com/second"), second.requests.map { it.url })
    }

    private fun assertIllegalArgument(block: () -> Unit) {
        try {
            block()
            fail("IllegalArgumentException expected")
        } catch (_: IllegalArgumentException) {
        }
    }
}
