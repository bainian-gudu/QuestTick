package com.questtick.net

import java.io.ByteArrayInputStream
import java.util.Collections

/** 流式请求测试替身；每个响应都从独立字节流读取，避免测试之间共享游标。 */
class FakeStreamingHttpTransport(
    private val handler: suspend (HttpRequest) -> FakeStreamingResponse,
) : StreamingHttpTransport {
    private val recorded = Collections.synchronizedList(mutableListOf<HttpRequest>())

    val requests: List<HttpRequest>
        get() = synchronized(recorded) { recorded.toList() }

    override suspend fun <T : Any> executeStreaming(
        request: HttpRequest,
        consume: (HttpResponseStream) -> T,
    ): T {
        require(request.config.retryMode == HttpRetryMode.NEVER) { "streaming transport must be one-shot" }
        recorded += request
        val fake = handler(request)
        val contentLength = fake.contentLength ?: fake.body.size.toLong()
        if (contentLength > request.config.maxResponseBytes) {
            throw ResponseTooLargeException(request.config.maxResponseBytes)
        }
        val stream =
            HttpResponseStream(
                code = fake.code,
                finalUrl = fake.finalUrl.ifBlank { request.url },
                contentLength = contentLength,
                rawHeaders = fake.headers.mapValues { (_, values) -> values.toList() },
                input = ByteArrayInputStream(fake.body.copyOf()),
                maxResponseBytes = request.config.maxResponseBytes,
            )
        return try {
            consume(stream)
        } catch (error: HttpResponseConsumerException) {
            var current: Throwable = error
            val seen = mutableSetOf<Throwable>()
            while (current is HttpResponseConsumerException && seen.add(current)) {
                current = current.cause ?: break
            }
            throw current
        }
    }
}

data class FakeStreamingResponse(
    val code: Int,
    val body: ByteArray,
    val finalUrl: String = "",
    val headers: Map<String, List<String>> = emptyMap(),
    val contentLength: Long? = null,
)
