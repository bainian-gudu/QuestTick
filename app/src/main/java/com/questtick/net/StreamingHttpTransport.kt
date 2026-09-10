package com.questtick.net

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

internal class HttpResponseConsumerException(
    cause: Throwable,
) : RuntimeException(cause)

/** 仅包装远端响应流读取失败，使实现可与调用方文件 I/O 明确区分。 */
internal class HttpResponseReadException(
    cause: IOException,
) : IOException("response stream read failed", cause)

/** 只在 [StreamingHttpTransport.executeStreaming] 回调期间有效的响应流。 */
class HttpResponseStream internal constructor(
    val code: Int,
    val finalUrl: String,
    val contentLength: Long,
    internal val rawHeaders: Map<String, List<String>>,
    internal val input: InputStream,
    internal val maxResponseBytes: Int,
) {
    var bytesRead: Long = 0L
        private set

    fun headerValues(name: String): List<String> =
        rawHeaders.entries
            .firstOrNull { (key, _) -> key.equals(name, ignoreCase = true) }
            ?.value
            .orEmpty()

    fun read(buffer: ByteArray): Int = read(buffer, 0, buffer.size)

    fun read(
        buffer: ByteArray,
        offset: Int,
        byteCount: Int,
    ): Int {
        require(offset >= 0 && byteCount >= 0 && offset + byteCount <= buffer.size) { "invalid buffer range" }
        if (byteCount == 0) return 0
        val remaining = maxResponseBytes.toLong() - bytesRead
        if (remaining <= 0L) {
            val probe = input.read()
            if (probe >= 0) throw ResponseTooLargeException(maxResponseBytes)
            return -1
        }
        val allowed = minOf(byteCount.toLong(), remaining).toInt()
        val read =
            try {
                input.read(buffer, offset, allowed)
            } catch (error: IOException) {
                throw HttpResponseReadException(error)
            }
        if (read > 0) bytesRead += read
        return read
    }

    fun copyTo(
        output: OutputStream,
        bufferSize: Int = DEFAULT_BUFFER_SIZE,
        onBytesCopied: (Long) -> Unit = {},
    ): Long {
        require(bufferSize > 0) { "bufferSize must be positive" }
        val buffer = ByteArray(bufferSize)
        while (true) {
            val read = read(buffer)
            if (read <= 0) break
            try {
                output.write(buffer, 0, read)
            } catch (error: IOException) {
                throw HttpResponseConsumerException(error)
            }
            onBytesCopied(bytesRead)
        }
        return bytesRead
    }

    override fun toString(): String = "HttpResponseStream(code=$code, contentLength=$contentLength, bytesRead=$bytesRead, sensitiveFields=redacted)"
}

/** 大响应的可注入流式传输边界；响应流不会逃逸出回调作用域。 */
interface StreamingHttpTransport {
    suspend fun <T : Any> executeStreaming(
        request: HttpRequest,
        consume: (HttpResponseStream) -> T,
    ): T
}
