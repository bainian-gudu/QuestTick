package com.questtick.net

import java.io.IOException

/** [HttpTransport] 对外抛出的稳定异常；cause 保留原始错误供脱敏后的详细日志排查。 */
class HttpTransportException(
    val failure: HttpFailure,
    cause: Throwable? = null,
) : IOException(failure.errorCode, cause)

/** 与 OkHttp 异常类型解耦的稳定 HTTP 失败分类，供 API 客户端、日志和 Fake 共用。 */
enum class HttpFailureKind {
    DNS,
    CONNECT,
    CONNECT_TIMEOUT,
    WRITE_TIMEOUT,
    READ_TIMEOUT,
    CONNECTION_INTERRUPTED,
    RESPONSE_INTERRUPTED,
    RESPONSE_TOO_LARGE,
    TLS,
    SECURITY_REJECTED,
    OTHER_IO,
    INTERNAL,
}

/**
 * 结构化传输失败；不包含 URL、请求头、请求体或响应正文。
 *
 * [outcomeUnknown] 表示非幂等请求可能已发送但未获得完整响应，调用方不得自动重发。
 */
data class HttpFailure(
    val kind: HttpFailureKind,
    val errorCode: String,
    val retryable: Boolean,
    val outcomeUnknown: Boolean,
)

object HttpFailureMapper {
    fun from(error: Throwable): HttpFailure =
        when (error) {
            is HttpTransportException -> {
                error.failure
            }

            is TransportFailureException -> {
                HttpFailure(
                    kind = error.kind.toPublicKind(),
                    errorCode = "transport:${error.kind.name.lowercase()}:${error.phase.name.lowercase()}",
                    retryable = !error.outcomeUnknown && error.retryableForIdempotentRead,
                    outcomeUnknown = error.outcomeUnknown,
                )
            }

            is ResponseTooLargeException -> {
                HttpFailure(
                    kind = HttpFailureKind.RESPONSE_TOO_LARGE,
                    errorCode = "response-too-large",
                    retryable = false,
                    outcomeUnknown = false,
                )
            }

            is UntrustedRedirectException,
            is IllegalArgumentException,
            -> {
                HttpFailure(
                    kind = HttpFailureKind.SECURITY_REJECTED,
                    errorCode = "request-rejected",
                    retryable = false,
                    outcomeUnknown = false,
                )
            }

            is IOException -> {
                HttpFailure(
                    kind = HttpFailureKind.OTHER_IO,
                    errorCode = error.javaClass.simpleName.ifBlank { "IOException" },
                    retryable = false,
                    outcomeUnknown = false,
                )
            }

            else -> {
                HttpFailure(
                    kind = HttpFailureKind.INTERNAL,
                    errorCode = error.javaClass.simpleName.ifBlank { "InternalError" },
                    retryable = false,
                    outcomeUnknown = false,
                )
            }
        }

    private fun TransportFailureKind.toPublicKind(): HttpFailureKind =
        when (this) {
            TransportFailureKind.DNS -> HttpFailureKind.DNS
            TransportFailureKind.CONNECT -> HttpFailureKind.CONNECT
            TransportFailureKind.CONNECT_TIMEOUT -> HttpFailureKind.CONNECT_TIMEOUT
            TransportFailureKind.WRITE_TIMEOUT -> HttpFailureKind.WRITE_TIMEOUT
            TransportFailureKind.READ_TIMEOUT -> HttpFailureKind.READ_TIMEOUT
            TransportFailureKind.CONNECTION_INTERRUPTED -> HttpFailureKind.CONNECTION_INTERRUPTED
            TransportFailureKind.RESPONSE_INTERRUPTED -> HttpFailureKind.RESPONSE_INTERRUPTED
            TransportFailureKind.RESPONSE_TOO_LARGE -> HttpFailureKind.RESPONSE_TOO_LARGE
            TransportFailureKind.TLS -> HttpFailureKind.TLS
            TransportFailureKind.OTHER_IO -> HttpFailureKind.OTHER_IO
        }
}
