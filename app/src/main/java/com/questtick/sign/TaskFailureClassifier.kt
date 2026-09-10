package com.questtick.sign

import com.questtick.data.FailureCategory
import com.questtick.net.ResponseTooLargeException
import com.questtick.net.HttpFailureKind
import com.questtick.net.HttpTransportException
import com.questtick.net.TransportFailureException
import org.json.JSONException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** 可持久化的任务失败描述；用户文案仅用于展示，不参与后续业务判断。 */
data class TaskFailureDescriptor(
    val category: FailureCategory,
    val errorCode: String = "",
    val retryable: Boolean = false,
)

/** 将接口错误码、HTTP 状态和异常统一转换为稳定失败类别。 */
internal object TaskFailureClassifier {
    fun classify(
        success: Boolean,
        skipped: Boolean,
        message: String,
        detail: String = "",
    ): TaskFailureDescriptor {
        if (success && !skipped) return TaskFailureDescriptor(FailureCategory.NONE)
        val text = listOf(message, detail).joinToString("\n")
        val lower = text.lowercase()

        // 先处理明确语义，再读取技术错误码；部分风控响应会返回 retcode=0 但 data.success=1。
        when {
            containsAny(lower, "未绑定角色", "未注册该游戏", "no role") ->
                return TaskFailureDescriptor(FailureCategory.NO_ROLE)
            containsAny(lower, "短信", "sms", "mobile verify", "phone verify") ->
                return TaskFailureDescriptor(FailureCategory.SMS_REQUIRED)
            containsAny(lower, "验证码", "人机验证", "captcha", "geetest") ->
                return TaskFailureDescriptor(FailureCategory.CAPTCHA_REQUIRED)
            containsAny(lower, "操作过于频繁", "请求过于频繁", "too many", "rate limit") ->
                return TaskFailureDescriptor(FailureCategory.RATE_LIMITED, retryable = true)
            containsAny(
                lower,
                "root",
                "安全检测",
                "security blocked",
                "封禁",
                "冻结",
                "黑名单",
                "forbidden",
                "blocked by risk",
            ) -> return TaskFailureDescriptor(FailureCategory.SECURITY_BLOCKED)
            containsAny(
                lower,
                "登录状态已失效",
                "尚未登录",
                "请重新登录",
                "请重新扫码",
                "未能获取有效",
                "unauthorized",
                "invalid token",
                "token invalid",
                "token expired",
                "combo_token",
            ) -> return TaskFailureDescriptor(FailureCategory.AUTH_EXPIRED)
            containsAny(lower, "凭证不完整", "cookie 组合", "cookie组合", "认证字段", "身份冲突") ->
                return TaskFailureDescriptor(FailureCategory.AUTH_EXPIRED, "credential_incomplete")
            containsAny(lower, "请求超时", "连接超时", "timeout", "timed out") ->
                return TaskFailureDescriptor(FailureCategory.NETWORK_TIMEOUT, retryable = true)
            containsAny(lower, "无法解析服务器", "无法连接", "网络不可达", "network unavailable") ->
                return TaskFailureDescriptor(FailureCategory.NETWORK_UNAVAILABLE, retryable = true)
            containsAny(lower, "配置", "缺少游戏服务器", "version", "设备参数") ->
                return TaskFailureDescriptor(FailureCategory.CONFIGURATION_ERROR)
        }

        extractRetcode(text)?.takeIf { it != 0 }?.let { return fromRetcode(it) }
        extractHttpCode(text)?.let { return fromHttpCode(it) }
        return if (!success && !skipped) {
            TaskFailureDescriptor(FailureCategory.INTERNAL_ERROR)
        } else {
            TaskFailureDescriptor(FailureCategory.NONE)
        }
    }

    fun fromRetcode(retcode: Int): TaskFailureDescriptor =
        when (retcode) {
            -100, -101, 10001, 10103 ->
                TaskFailureDescriptor(FailureCategory.AUTH_EXPIRED, "retcode:$retcode")
            1008 -> TaskFailureDescriptor(FailureCategory.NO_ROLE, "retcode:$retcode")
            1034, 5003 -> TaskFailureDescriptor(FailureCategory.CAPTCHA_REQUIRED, "retcode:$retcode")
            -1004 -> TaskFailureDescriptor(FailureCategory.RATE_LIMITED, "retcode:$retcode", retryable = true)
            -502 -> TaskFailureDescriptor(FailureCategory.SERVER_ERROR, "retcode:$retcode", retryable = true)
            else -> TaskFailureDescriptor(FailureCategory.SERVER_ERROR, "retcode:$retcode")
        }

    fun fromNonIdempotentResponse(
        httpCode: Int,
        retcode: Int,
    ): TaskFailureDescriptor =
        (if (httpCode !in 200..299) fromHttpCode(httpCode) else fromRetcode(retcode))
            .copy(retryable = false)

    fun fromHttpCode(code: Int): TaskFailureDescriptor =
        when (code) {
            401, 403 -> TaskFailureDescriptor(FailureCategory.AUTH_EXPIRED, "http:$code")
            408 -> TaskFailureDescriptor(FailureCategory.NETWORK_TIMEOUT, "http:$code", retryable = true)
            429 -> TaskFailureDescriptor(FailureCategory.RATE_LIMITED, "http:$code", retryable = true)
            in 500..599 -> TaskFailureDescriptor(FailureCategory.SERVER_ERROR, "http:$code", retryable = true)
            else -> TaskFailureDescriptor(FailureCategory.HTTP_ERROR, "http:$code")
        }

    fun fromException(error: Throwable?): TaskFailureDescriptor =
        when (error) {
            is HttpTransportException -> fromHttpFailure(error)
            is TransportFailureException -> fromTransportFailure(error)
            is SocketTimeoutException,
            is InterruptedIOException,
            ->
                TaskFailureDescriptor(
                    FailureCategory.NETWORK_TIMEOUT,
                    errorCode = error.javaClass.simpleName,
                    retryable = true,
                )
            is UnknownHostException,
            is ConnectException,
            is NoRouteToHostException,
            is SocketException,
            ->
                TaskFailureDescriptor(
                    FailureCategory.NETWORK_UNAVAILABLE,
                    errorCode = error.javaClass.simpleName,
                    retryable = true,
                )
            is SSLException -> TaskFailureDescriptor(FailureCategory.NETWORK_UNAVAILABLE, "SSLException")
            is JSONException -> TaskFailureDescriptor(FailureCategory.SERVER_ERROR, "JSONException", retryable = true)
            is ResponseTooLargeException ->
                TaskFailureDescriptor(FailureCategory.SERVER_ERROR, "response-too-large", retryable = true)
            else ->
                TaskFailureDescriptor(
                    FailureCategory.INTERNAL_ERROR,
                    errorCode = error?.javaClass?.simpleName.orEmpty(),
                )
        }

    private fun fromHttpFailure(error: HttpTransportException): TaskFailureDescriptor {
        val failure = error.failure
        if (failure.outcomeUnknown) {
            return TaskFailureDescriptor(
                FailureCategory.RESULT_UNKNOWN,
                errorCode = failure.errorCode,
                retryable = false,
            )
        }
        val category =
            when (failure.kind) {
                HttpFailureKind.CONNECT_TIMEOUT,
                HttpFailureKind.WRITE_TIMEOUT,
                HttpFailureKind.READ_TIMEOUT,
                -> FailureCategory.NETWORK_TIMEOUT
                HttpFailureKind.DNS,
                HttpFailureKind.CONNECT,
                HttpFailureKind.CONNECTION_INTERRUPTED,
                HttpFailureKind.RESPONSE_INTERRUPTED,
                HttpFailureKind.TLS,
                HttpFailureKind.OTHER_IO,
                -> FailureCategory.NETWORK_UNAVAILABLE
                HttpFailureKind.RESPONSE_TOO_LARGE -> FailureCategory.SERVER_ERROR
                HttpFailureKind.SECURITY_REJECTED -> FailureCategory.SECURITY_BLOCKED
                HttpFailureKind.INTERNAL -> FailureCategory.INTERNAL_ERROR
            }
        return TaskFailureDescriptor(category, failure.errorCode, failure.retryable)
    }

    private fun fromTransportFailure(error: TransportFailureException): TaskFailureDescriptor {
        if (error.outcomeUnknown) {
            return TaskFailureDescriptor(
                FailureCategory.RESULT_UNKNOWN,
                errorCode = "transport:${error.kind.name.lowercase()}:${error.phase.name.lowercase()}",
                retryable = false,
            )
        }
        val category =
            when (error.kind) {
                com.questtick.net.TransportFailureKind.CONNECT_TIMEOUT,
                com.questtick.net.TransportFailureKind.WRITE_TIMEOUT,
                com.questtick.net.TransportFailureKind.READ_TIMEOUT,
                -> FailureCategory.NETWORK_TIMEOUT
                com.questtick.net.TransportFailureKind.DNS,
                com.questtick.net.TransportFailureKind.CONNECT,
                com.questtick.net.TransportFailureKind.CONNECTION_INTERRUPTED,
                com.questtick.net.TransportFailureKind.RESPONSE_INTERRUPTED,
                com.questtick.net.TransportFailureKind.TLS,
                com.questtick.net.TransportFailureKind.OTHER_IO,
                -> FailureCategory.NETWORK_UNAVAILABLE
                com.questtick.net.TransportFailureKind.RESPONSE_TOO_LARGE -> FailureCategory.SERVER_ERROR
            }
        val retryable =
            error.retryableForIdempotentRead ||
                (
                    !error.phase.requestMayHaveBeenSent &&
                        error.kind in
                        setOf(
                            com.questtick.net.TransportFailureKind.DNS,
                            com.questtick.net.TransportFailureKind.CONNECT,
                            com.questtick.net.TransportFailureKind.CONNECT_TIMEOUT,
                        )
                )
        return TaskFailureDescriptor(
            category,
            errorCode = "transport:${error.kind.name.lowercase()}:${error.phase.name.lowercase()}",
            retryable = retryable,
        )
    }

    private fun extractRetcode(text: String): Int? =
        RETCODE_PATTERN
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()

    private fun extractHttpCode(text: String): Int? =
        HTTP_PATTERN
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()

    private fun containsAny(
        text: String,
        vararg needles: String,
    ): Boolean = needles.any(text::contains)

    private val RETCODE_PATTERN = Regex("""retcode\s*[:=]\s*(-?\d+)""", RegexOption.IGNORE_CASE)
    private val HTTP_PATTERN = Regex("""http\s*[:= ]\s*(\d{3})""", RegexOption.IGNORE_CASE)
}
