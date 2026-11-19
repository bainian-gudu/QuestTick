package com.questtick.net

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustedRedirectsTest {
    private val servers = mutableListOf<MockWebServer>()

    @After
    fun tearDown() {
        servers.forEach { runCatching { it.shutdown() } }
    }

    @Test
    fun `follows every same-origin hop and returns final response`() =
        runBlocking {
            val server = server()
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/second"))
            server.enqueue(MockResponse().setResponseCode(307).addHeader("Location", "final"))
            server.enqueue(MockResponse().setResponseCode(200).setBody("done"))
            val client = OkHttpClient.Builder().followRedirects(true).build()
            val request =
                Request
                    .Builder()
                    .url(server.url("/first"))
                    .get()
                    .build()

            val result =
                TrustedRedirects.executeSnapshotCancellable(
                    client,
                    request,
                    TrustedRedirects.sameOriginForTest,
                )

            assertEquals(200, result.code)
            assertEquals("done", result.body.toString(Charsets.UTF_8))
            assertEquals(server.url("/final").toString(), result.finalUrl)
            assertEquals(listOf("/first", "/second", "/final"), takePaths(server, 3))
        }

    @Test
    fun `rejects cross-origin target before sending next request`() {
        val source = server()
        val target = server()
        source.enqueue(
            MockResponse()
                .setResponseCode(302)
                .addHeader("Location", target.url("/stolen")),
        )
        val request =
            Request
                .Builder()
                .url(source.url("/start"))
                .get()
                .build()

        assertThrows(UntrustedRedirectException::class.java) {
            TrustedRedirects.execute(
                OkHttpClient.Builder().build(),
                request,
                TrustedRedirects.sameOriginForTest,
            )
        }

        assertEquals(1, source.requestCount)
        assertEquals(0, target.requestCount)
    }

    @Test
    fun `rejects redirect loop before repeating a request`() {
        val server = server()
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/b"))
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/a"))
        val request =
            Request
                .Builder()
                .url(server.url("/a"))
                .get()
                .build()

        assertThrows(UntrustedRedirectException::class.java) {
            TrustedRedirects.execute(
                OkHttpClient.Builder().build(),
                request,
                TrustedRedirects.sameOriginForTest,
            )
        }

        assertEquals(listOf("/a", "/b"), takePaths(server, 2))
    }

    @Test
    fun `rejects redirect beyond maximum hop count`() {
        val server = server()
        repeat(TrustedRedirects.MAX_REDIRECTS + 1) { index ->
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/hop-${index + 1}"))
        }
        val request =
            Request
                .Builder()
                .url(server.url("/start"))
                .get()
                .build()

        assertThrows(UntrustedRedirectException::class.java) {
            TrustedRedirects.execute(
                OkHttpClient.Builder().build(),
                request,
                TrustedRedirects.sameOriginForTest,
            )
        }

        assertEquals(TrustedRedirects.MAX_REDIRECTS + 1, server.requestCount)
    }

    @Test
    fun `does not transparently resend POST after redirect`() {
        val server = server()
        server.enqueue(MockResponse().setResponseCode(307).addHeader("Location", "/again"))
        val request =
            Request
                .Builder()
                .url(server.url("/submit"))
                .post("payload".toRequestBody())
                .build()

        assertThrows(UntrustedRedirectException::class.java) {
            TrustedRedirects.execute(
                OkHttpClient.Builder().build(),
                request,
                TrustedRedirects.sameOriginForTest,
            )
        }

        assertEquals(1, server.requestCount)
    }

    @Test
    fun `rejects missing and ambiguous redirect locations`() {
        val missing = server()
        missing.enqueue(MockResponse().setResponseCode(302))
        val missingRequest =
            Request
                .Builder()
                .url(missing.url("/missing"))
                .get()
                .build()
        assertThrows(UntrustedRedirectException::class.java) {
            TrustedRedirects.execute(
                OkHttpClient.Builder().build(),
                missingRequest,
                TrustedRedirects.sameOriginForTest,
            )
        }

        val ambiguous = server()
        ambiguous.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/safe\\evil"))
        val ambiguousRequest =
            Request
                .Builder()
                .url(ambiguous.url("/ambiguous"))
                .get()
                .build()
        assertThrows(UntrustedRedirectException::class.java) {
            TrustedRedirects.execute(
                OkHttpClient.Builder().build(),
                ambiguousRequest,
                TrustedRedirects.sameOriginForTest,
            )
        }
    }

    @Test
    fun `cross-origin request drops sensitive headers`() {
        val source = server().url("/from")
        val target = server().url("/to")
        val request =
            Request
                .Builder()
                .url(source)
                .header("Authorization", "Bearer secret")
                .header("Cookie", "token=secret")
                .header("Host", source.host)
                .header("DS", "signed-secret")
                .header("x-rpc-combo_token", "combo-secret")
                .header("User-Agent", "test-agent")
                .build()

        val redirected = TrustedRedirects.redirectedRequest(request, target)

        assertFalse(redirected.headers.names().any { it.equals("Authorization", true) })
        assertFalse(redirected.headers.names().any { it.equals("Cookie", true) })
        assertFalse(redirected.headers.names().any { it.equals("Host", true) })
        assertFalse(redirected.headers.names().any { it.equals("DS", true) })
        assertFalse(redirected.headers.names().any { it.equals("x-rpc-combo_token", true) })
        assertEquals("test-agent", redirected.header("User-Agent"))
    }

    @Test
    fun `production client factory disables automatic redirects and retries`() {
        val client = OkHttpClientFactory.provideOkHttpClient()

        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertFalse(client.retryOnConnectionFailure)
    }

    @Test
    fun `non idempotent method cannot opt into read retry policy`() {
        val server = server()
        val request =
            Request
                .Builder()
                .url(server.url("/post"))
                .post("payload".toRequestBody())
                .build()

        assertThrows(IllegalArgumentException::class.java) {
            TrustedRedirects.execute(
                OkHttpClient.Builder().build(),
                request,
                TrustedRedirects.sameOriginForTest,
                TransportRetryPolicy.IDEMPOTENT_READ,
            )
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `same-origin production policy rejects downgrade and host changes`() {
        val initial = "https://api-takumi.mihoyo.com/path"

        assertTrue(TrustedUrlPolicy.isSameOriginHttps(initial, "https://api-takumi.mihoyo.com/next"))
        assertFalse(TrustedUrlPolicy.isSameOriginHttps(initial, "http://api-takumi.mihoyo.com/next"))
        assertFalse(TrustedUrlPolicy.isSameOriginHttps(initial, "https://evil.example/next"))
        assertFalse(TrustedUrlPolicy.isSameOriginHttps(initial, "https://api-takumi.mihoyo.com:444/next"))
    }

    private fun server(): MockWebServer =
        MockWebServer().also {
            it.start()
            servers += it
        }

    private fun takePaths(
        server: MockWebServer,
        count: Int,
    ): List<String> = List(count) { server.takeRequest().path.orEmpty() }
}
