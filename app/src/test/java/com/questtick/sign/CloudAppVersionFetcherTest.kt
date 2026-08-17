package com.questtick.sign

import com.questtick.net.HttpRequest
import com.questtick.net.HttpResponse
import com.questtick.net.HttpTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudAppVersionFetcherTest {
    @Test
    fun `cloud genshin reads version from its App Store lookup`() = runBlocking {
        var requestedUrl = ""
        val transport = HttpTransport { request: HttpRequest ->
            requestedUrl = request.url
            HttpResponse.text(200, "{\"resultCount\":1,\"results\":[{\"version\":\"7.0.0\"}]}")
        }

        assertEquals("7.0.0", CloudAppVersionFetcher.fetchCloudYs(transport).getOrThrow())
        assertEquals("https://itunes.apple.com/cn/lookup?id=1569029742", requestedUrl)
    }

    @Test
    fun `cloud star rail reads version from its App Store lookup`() = runBlocking {
        var requestedUrl = ""
        val transport = HttpTransport { request: HttpRequest ->
            requestedUrl = request.url
            HttpResponse.text(200, "{\"results\":[{\"version\":\"4.4.0\"}]}")
        }

        assertEquals("4.4.0", CloudAppVersionFetcher.fetchCloudSr(transport).getOrThrow())
        assertEquals("https://itunes.apple.com/cn/lookup?id=6475038985", requestedUrl)
    }

    @Test
    fun `invalid App Store response is reported as failure`() = runBlocking {
        val transport = HttpTransport { HttpResponse.text(200, "{\"resultCount\":0,\"results\":[]}") }

        val result = CloudAppVersionFetcher.fetchCloudYs(transport)
        assertTrue(result.isFailure)
    }
}
