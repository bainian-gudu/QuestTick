package com.questtick.sign

/*
 * 异常与业务 retcode 到中文提示文案的映射，同时提供用于日志的技术细节摘要。
 */

import com.questtick.net.ResponseTooLargeException
import com.questtick.net.HttpFailureKind
import com.questtick.net.HttpTransportException
import com.questtick.net.TransportFailureException
import com.questtick.net.TransportFailureKind
import org.json.JSONException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** 将底层异常与米游社 retcode 转换为中文提示，同时保留详细日志所需的技术细节。 */
object ErrorText {
    /** 将异常转换为用户可读的中文提示。 */
    fun fromException(e: Throwable?): String =
        when (e) {
            null -> {
                "发生未知错误"
            }

            is HttpTransportException -> {
                fromHttpFailure(e)
            }

            is TransportFailureException -> {
                fromTransportFailure(e)
            }

            is UnknownHostException -> {
                "网络连接失败，无法解析服务器地址，请检查网络"
            }

            is SocketTimeoutException -> {
                "网络请求超时，请稍后重试"
            }

            is ConnectException -> {
                "无法连接到服务器，请检查网络或稍后重试"
            }

            is NoRouteToHostException -> {
                "网络不可达，请检查网络设置"
            }

            is SSLException -> {
                "安全连接（SSL）建立失败，请检查网络环境"
            }

            is InterruptedIOException -> {
                "网络请求被中断或超时"
            }

            is SocketException -> {
                "网络连接异常中断，请重试"
            }

            is JSONException -> {
                "服务器返回的数据格式异常"
            }

            is ResponseTooLargeException -> {
                "服务器返回的数据过大，已停止读取，请稍后重试"
            }

            is jakarta.mail.AuthenticationFailedException -> {
                "邮箱认证失败，请检查发件邮箱和授权码是否正确"
            }

            is jakarta.mail.internet.AddressException -> {
                "收件邮箱地址格式不正确"
            }

            is jakarta.mail.MessagingException -> {
                e.cause?.takeIf { it !== e }?.let { fromException(it) } ?: "邮件发送失败，请检查 SMTP 服务器和端口配置"
            }

            else -> {
                e.cause?.takeIf { it !== e }?.let { fromException(it) } ?: "发生未知错误"
            }
        }

    private fun fromHttpFailure(error: HttpTransportException): String {
        if (error.failure.outcomeUnknown) {
            return "请求可能已送达服务器，但未完整收到响应，请确认结果后再操作"
        }
        return when (error.failure.kind) {
            HttpFailureKind.DNS -> "网络连接失败，无法解析服务器地址，请检查网络"

            HttpFailureKind.CONNECT -> "无法连接到服务器，请检查网络或稍后重试"

            HttpFailureKind.CONNECT_TIMEOUT,
            HttpFailureKind.WRITE_TIMEOUT,
            HttpFailureKind.READ_TIMEOUT,
            -> "网络请求超时，请稍后重试"

            HttpFailureKind.CONNECTION_INTERRUPTED,
            HttpFailureKind.RESPONSE_INTERRUPTED,
            -> "网络连接异常中断，请重试"

            HttpFailureKind.RESPONSE_TOO_LARGE -> "服务器返回的数据过大，已停止读取，请稍后重试"

            HttpFailureKind.TLS -> "安全连接（SSL）建立失败，请检查网络环境"

            HttpFailureKind.SECURITY_REJECTED -> "请求地址未通过安全校验，已停止访问"

            HttpFailureKind.OTHER_IO -> "网络传输失败，请稍后重试"

            HttpFailureKind.INTERNAL -> "发生未知错误"
        }
    }

    private fun fromTransportFailure(error: TransportFailureException): String {
        if (error.outcomeUnknown) {
            return "请求可能已送达服务器，但未完整收到响应，请确认结果后再操作"
        }
        return when (error.kind) {
            TransportFailureKind.DNS -> "网络连接失败，无法解析服务器地址，请检查网络"

            TransportFailureKind.CONNECT -> "无法连接到服务器，请检查网络或稍后重试"

            TransportFailureKind.CONNECT_TIMEOUT,
            TransportFailureKind.WRITE_TIMEOUT,
            TransportFailureKind.READ_TIMEOUT,
            -> "网络请求超时，请稍后重试"

            TransportFailureKind.CONNECTION_INTERRUPTED,
            TransportFailureKind.RESPONSE_INTERRUPTED,
            -> "网络连接异常中断，请重试"

            TransportFailureKind.RESPONSE_TOO_LARGE -> "服务器返回的数据过大，已停止读取，请稍后重试"

            TransportFailureKind.TLS -> "安全连接（SSL）建立失败，请检查网络环境"

            TransportFailureKind.OTHER_IO -> "网络传输失败，请稍后重试"
        }
    }

    /** 提取未经业务文案替换的异常因果链；存储和展示前仍会统一脱敏。 */
    fun detailOf(
        e: Throwable?,
        includeStackTrace: Boolean = false,
    ): String {
        if (e == null) return ""
        val causes = ArrayList<String>(4)
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        var current: Throwable? = e
        while (current != null && seen.add(current) && causes.size < 8) {
            val name = current.javaClass.name
            val message = current.message.orEmpty()
            causes += if (message.isBlank()) name else "$name: $message"
            current = current.cause?.takeIf { it !== current }
        }
        if (!includeStackTrace) return causes.joinToString("\nCaused by: ")

        val stack = e.stackTraceToString()
        return (causes.joinToString("\nCaused by: ") + "\nStack trace:\n" + stack).trim()
    }

    /**
     * 将米游社接口 retcode 转换为中文提示；没有专门映射时返回 null。
     */
    fun fromRetcode(retcode: Int): String? =
        when (retcode) {
            -100, -101, 10001, 10103 -> "登录状态已失效，请重新获取并填写 Cookie"
            1008 -> "该账号未绑定此游戏角色"
            1034, 5003 -> "触发风控校验，需要手动完成验证码，请前往米游社 App 手动签到一次"
            -1004 -> "操作过于频繁，请稍后重试"
            -502 -> "米游社服务器繁忙，请稍后重试"
            else -> null
        }
}
