package com.questtick.net

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class ResponseTooLargeException(
    val limitBytes: Int,
) : IOException("Response body exceeds $limitBytes bytes")

/** 完成网络读取后的不可变响应快照，不再持有需要关闭的 OkHttp Response/ResponseBody。 */
internal data class OkHttpResponseSnapshot(
    val code: Int,
    val body: ByteArray,
    val finalUrl: String,
    val headers: Headers,
)

/**
 * 在 OkHttp 回调尚未完成时映射响应。
 *
 * 响应头、响应体读取和映射全部完成后才恢复协程；在此之前取消协程都会取消底层 Call，从而中断
 * 慢响应体读取。响应由本函数统一关闭，映射结果不得继续持有 Response 或 ResponseBody。
 */
internal suspend fun <T> Call.mapResponseCancellable(transform: (Response) -> T): T =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(
            object : Callback {
                override fun onFailure(
                    call: Call,
                    e: IOException,
                ) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(
                    call: Call,
                    response: Response,
                ) {
                    try {
                        val mapped = response.use(transform)
                        if (continuation.isActive) continuation.resume(mapped)
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                }
            },
        )
    }

internal fun Response.readSnapshot(maxBodyBytes: Int = 0): OkHttpResponseSnapshot {
    val bytes =
        if (maxBodyBytes > 0) {
            val source = body.source()
            val probeSize = maxBodyBytes.toLong() + 1L
            source.request(probeSize)
            if (source.buffer.size > maxBodyBytes) {
                throw ResponseTooLargeException(maxBodyBytes)
            }
            source.buffer.readByteArray(source.buffer.size)
        } else {
            body.bytes()
        }
    return OkHttpResponseSnapshot(
        code = code,
        body = bytes,
        finalUrl = request.url.toString(),
        headers = headers,
    )
}
