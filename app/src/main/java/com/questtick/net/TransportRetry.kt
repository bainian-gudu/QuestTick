package com.questtick.net

import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Request
import okhttp3.Response
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.ProtocolException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLException

/** 单次 HTTP 传输的显式重试边界。 */
internal enum class TransportRetryPolicy(
    val maxAttempts: Int,
) {
    /** 不允许传输层重发，所有 POST 固定使用此策略。 */
    NEVER(1),

    /** 仅供业务确认无副作用的 GET/HEAD 使用；失败时最多补发一次。 */
    IDEMPOTENT_READ(2),
    ;

    fun requireCompatible(request: Request) {
        if (this == IDEMPOTENT_READ && request.method !in IDEMPOTENT_METHODS) {
            throw IllegalArgumentException("IDEMPOTENT_READ only supports GET or HEAD")
        }
    }

    companion object {
        private val IDEMPOTENT_METHODS = setOf("GET", "HEAD")
    }
}

/** 请求在底层连接中的进展，用于判断非幂等请求结果是否仍可安全重试。 */
internal enum class RequestTransmissionPhase {
    BEFORE_SEND,
    REQUEST_HEADERS_SENDING,
    REQUEST_BODY_SENDING,
    REQUEST_SENT,
    RESPONSE_HEADERS_RECEIVED,
    RESPONSE_BODY_READING,
    ;

    val requestMayHaveBeenSent: Boolean
        get() = ordinal >= REQUEST_HEADERS_SENDING.ordinal
}

internal enum class TransportFailureKind {
    DNS,
    CONNECT,
    CONNECT_TIMEOUT,
    WRITE_TIMEOUT,
    READ_TIMEOUT,
    CONNECTION_INTERRUPTED,
    RESPONSE_INTERRUPTED,
    RESPONSE_TOO_LARGE,
    TLS,
    OTHER_IO,
}

/**
 * 携带传输阶段的稳定异常。
 *
 * [outcomeUnknown] 仅在非 GET/HEAD 请求已经可能发出后成立；调用方必须停止自动重试并向用户报告待确认。
 */
internal class TransportFailureException(
    val kind: TransportFailureKind,
    val phase: RequestTransmissionPhase,
    val requestMethod: String,
    cause: IOException,
) : IOException("transport ${kind.name.lowercase()} at ${phase.name.lowercase()}", cause) {
    val outcomeUnknown: Boolean =
        requestMethod !in setOf("GET", "HEAD") && phase.requestMayHaveBeenSent

    val retryableForIdempotentRead: Boolean =
        kind in
            setOf(
                TransportFailureKind.DNS,
                TransportFailureKind.CONNECT,
                TransportFailureKind.CONNECT_TIMEOUT,
                TransportFailureKind.WRITE_TIMEOUT,
                TransportFailureKind.READ_TIMEOUT,
                TransportFailureKind.CONNECTION_INTERRUPTED,
                TransportFailureKind.RESPONSE_INTERRUPTED,
            )
}

/** 每个底层 Call 独立使用，不能跨重试尝试复用。 */
internal class RequestTransmissionTracker : EventListener() {
    private val current = AtomicReference(RequestTransmissionPhase.BEFORE_SEND)

    val phase: RequestTransmissionPhase
        get() = current.get()

    override fun requestHeadersStart(call: Call) {
        current.set(RequestTransmissionPhase.REQUEST_HEADERS_SENDING)
    }

    override fun requestBodyStart(call: Call) {
        current.set(RequestTransmissionPhase.REQUEST_BODY_SENDING)
    }

    override fun requestHeadersEnd(
        call: Call,
        request: Request,
    ) {
        if (request.body == null) current.set(RequestTransmissionPhase.REQUEST_SENT)
    }

    override fun requestBodyEnd(
        call: Call,
        byteCount: Long,
    ) {
        current.set(RequestTransmissionPhase.REQUEST_SENT)
    }

    override fun responseHeadersEnd(
        call: Call,
        response: Response,
    ) {
        current.set(RequestTransmissionPhase.RESPONSE_HEADERS_RECEIVED)
    }

    override fun responseBodyStart(call: Call) {
        current.set(RequestTransmissionPhase.RESPONSE_BODY_READING)
    }
}

internal object TransportFailures {
    fun classify(
        error: IOException,
        requestMethod: String,
        phase: RequestTransmissionPhase,
    ): IOException {
        if (
            error is TransportFailureException || error is UntrustedRedirectException
        ) {
            return error
        }
        val relevant = relevantCause(error)
        if (relevant is ResponseTooLargeException && requestMethod in setOf("GET", "HEAD")) {
            return relevant
        }
        val kind =
            when (relevant) {
                is ResponseTooLargeException -> TransportFailureKind.RESPONSE_TOO_LARGE
                is UnknownHostException -> TransportFailureKind.DNS
                is ConnectException,
                is NoRouteToHostException,
                -> TransportFailureKind.CONNECT
                is SocketTimeoutException -> timeoutKind(phase)
                is InterruptedIOException ->
                    if (relevant.message.orEmpty().contains("timeout", ignoreCase = true)) {
                        timeoutKind(phase)
                    } else if (phase >= RequestTransmissionPhase.RESPONSE_HEADERS_RECEIVED) {
                        TransportFailureKind.RESPONSE_INTERRUPTED
                    } else {
                        TransportFailureKind.CONNECTION_INTERRUPTED
                    }
                is SSLException -> TransportFailureKind.TLS
                is EOFException,
                is ProtocolException,
                -> if (phase >= RequestTransmissionPhase.RESPONSE_HEADERS_RECEIVED) {
                    TransportFailureKind.RESPONSE_INTERRUPTED
                } else {
                    TransportFailureKind.CONNECTION_INTERRUPTED
                }
                is SocketException -> if (phase >= RequestTransmissionPhase.RESPONSE_HEADERS_RECEIVED) {
                    TransportFailureKind.RESPONSE_INTERRUPTED
                } else {
                    TransportFailureKind.CONNECTION_INTERRUPTED
                }
                else -> TransportFailureKind.OTHER_IO
            }
        return TransportFailureException(kind, phase, requestMethod, error)
    }

    fun classify(
        error: IOException,
        request: Request,
    ): IOException {
        val tracker = request.tag(RequestTransmissionTracker::class.java)
        return classify(
            error = error,
            requestMethod = request.method,
            phase = tracker?.phase ?: RequestTransmissionPhase.BEFORE_SEND,
        )
    }

    private fun timeoutKind(phase: RequestTransmissionPhase): TransportFailureKind =
        when (phase) {
            RequestTransmissionPhase.BEFORE_SEND -> TransportFailureKind.CONNECT_TIMEOUT
            RequestTransmissionPhase.REQUEST_HEADERS_SENDING,
            RequestTransmissionPhase.REQUEST_BODY_SENDING,
            -> TransportFailureKind.WRITE_TIMEOUT
            RequestTransmissionPhase.REQUEST_SENT,
            RequestTransmissionPhase.RESPONSE_HEADERS_RECEIVED,
            RequestTransmissionPhase.RESPONSE_BODY_READING,
            -> TransportFailureKind.READ_TIMEOUT
        }

    private fun relevantCause(error: IOException): Throwable {
        var current: Throwable = error
        val seen = mutableSetOf<Throwable>()
        while (seen.add(current)) {
            if (
                current is ResponseTooLargeException || current is UnknownHostException ||
                current is ConnectException || current is NoRouteToHostException ||
                current is SocketTimeoutException || current is SSLException || current is EOFException ||
                current is ProtocolException || current is SocketException || current is InterruptedIOException
            ) {
                return current
            }
            current = current.cause ?: break
        }
        return error
    }

    fun shouldRetry(
        failure: IOException,
        request: Request,
        policy: TransportRetryPolicy,
        attemptsUsed: Int,
    ): Boolean {
        if (policy != TransportRetryPolicy.IDEMPOTENT_READ || attemptsUsed >= policy.maxAttempts) return false
        if (request.method !in setOf("GET", "HEAD")) return false
        return failure is TransportFailureException && failure.retryableForIdempotentRead
    }
}
