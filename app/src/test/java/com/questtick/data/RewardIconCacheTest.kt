package com.questtick.data

import android.content.Context
import com.questtick.net.FakeHttpTransport
import com.questtick.net.HttpResponse
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

class RewardIconCacheTest {
    @Test
    fun concurrentSameUrlDownloadsOnceAndLaterReusesLocalFile() = runBlocking {
        val root = Files.createTempDirectory("reward-icon-cache-test").toFile()
        try {
            val context = mockk<Context>()
            every { context.applicationContext } returns context
            every { context.filesDir } returns root
            val calls = AtomicInteger(0)
            val expectedBytes = byteArrayOf(1, 3, 5, 7, 9)
            val url = "https://upload-bbs.miyoushe.com/test/api-01-reward.png"
            val transport =
                FakeHttpTransport { request ->
                    calls.incrementAndGet()
                    delay(80L)
                    HttpResponse.bytes(200, expectedBytes, finalUrl = request.url)
                }

            val concurrent =
                List(8) {
                    async(Dispatchers.Default) {
                        RewardIconCache.getOrDownload(context, url, transport)
                    }
                }.awaitAll()

            val cached = RewardIconCache.getOrDownload(context, url, transport)

            assertEquals(1, calls.get())
            assertTrueFilesSharePath(concurrent.filterNotNull())
            assertNotNull(cached)
            assertEquals(concurrent.first()?.canonicalPath, cached?.canonicalPath)
            assertArrayEquals(expectedBytes, cached?.readBytes())
            assertEquals(1, transport.requests.size)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun prefersOriginalImageWhenCdnUrlContainsResizeParameter() = runBlocking {
        val root = Files.createTempDirectory("reward-icon-cache-original-test").toFile()
        val context = mockk<Context>()
        every { context.applicationContext } returns context
        every { context.filesDir } returns root
        val resized = "https://upload-bbs.miyoushe.com/test/coin.png?x-oss-process=image/resize,w_80"
        val original = "https://upload-bbs.miyoushe.com/test/coin.png"
        try {
            val transport = FakeHttpTransport { request ->
                if (request.url == original) HttpResponse.bytes(200, byteArrayOf(7, 8, 9), request.url)
                else HttpResponse.bytes(404, byteArrayOf(), request.url)
            }

            val file = RewardIconCache.getOrDownload(context, resized, transport)

            assertNotNull(file)
            assertEquals(listOf(original), transport.requests.map { it.url })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun existingUsableFileIsReturnedWithoutCallingTransport() = runBlocking {
        val root = Files.createTempDirectory("reward-icon-cache-hit-test").toFile()
        try {
            val context = mockk<Context>()
            every { context.applicationContext } returns context
            every { context.filesDir } returns root
            val url = "https://upload-bbs.miyoushe.com/test/api-01-existing.png"
            val firstTransport =
                FakeHttpTransport { request -> HttpResponse.bytes(200, byteArrayOf(2, 4, 6), request.url) }
            val file = RewardIconCache.getOrDownload(context, url, firstTransport)
            val rejectingTransport = FakeHttpTransport { error("cache hit must not call transport") }

            val reused = RewardIconCache.getOrDownload(context, url, rejectingTransport)

            assertNotNull(file)
            assertEquals(file?.canonicalPath, reused?.canonicalPath)
            assertEquals(1, firstTransport.requests.size)
            assertEquals(0, rejectingTransport.requests.size)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun assertTrueFilesSharePath(files: List<java.io.File>) {
        val paths = files.map { it.canonicalPath }.distinct()
        assertEquals(1, paths.size)
    }
}
