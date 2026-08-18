package com.questtick.sign

import com.questtick.data.FailureCategory
import com.questtick.net.HttpFailure
import com.questtick.net.HttpFailureKind
import com.questtick.net.HttpTransportException
import com.questtick.net.RequestTransmissionPhase
import com.questtick.net.TransportFailureException
import com.questtick.net.TransportFailureKind
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskFailureClassifierTest {
    @Test
    fun `successful result is never classified from diagnostic retcode`() {
        val result =
            TaskFailureClassifier.classify(
                success = true,
                skipped = false,
                message = "签到成功",
                detail = "retcode=0",
            )

        assertEquals(FailureCategory.NONE, result.category)
        assertFalse(result.retryable)
    }

    @Test
    fun `captcha semantics take priority over zero retcode`() {
        val result =
            TaskFailureClassifier.classify(
                success = false,
                skipped = false,
                message = "触发风控验证码",
                detail = "data.success=1, retcode=0",
            )

        assertEquals(FailureCategory.CAPTCHA_REQUIRED, result.category)
    }

    @Test
    fun `retcodes and http status produce stable error metadata`() {
        val auth = TaskFailureClassifier.classify(false, false, "登录失败", "retcode=-100")
        val busy = TaskFailureClassifier.classify(false, false, "服务繁忙", "HTTP 503")
        val rateLimit = TaskFailureClassifier.classify(false, false, "请求失败", "http=429")

        assertEquals(FailureCategory.AUTH_EXPIRED, auth.category)
        assertEquals("retcode:-100", auth.errorCode)
        assertEquals(FailureCategory.SERVER_ERROR, busy.category)
        assertTrue(busy.retryable)
        assertEquals(FailureCategory.RATE_LIMITED, rateLimit.category)
        assertTrue(rateLimit.retryable)
    }

    @Test
    fun `complete non idempotent error responses never retry`() {
        val serverBusy = TaskFailureClassifier.fromNonIdempotentResponse(503, -999)
        val businessBusy = TaskFailureClassifier.fromNonIdempotentResponse(200, -502)

        assertEquals(FailureCategory.SERVER_ERROR, serverBusy.category)
        assertFalse(serverBusy.retryable)
        assertEquals(FailureCategory.SERVER_ERROR, businessBusy.category)
        assertFalse(businessBusy.retryable)
    }

    @Test
    fun `post failure after send is result unknown and never retryable`() {
        val result =
            TaskFailureClassifier.fromException(
                TransportFailureException(
                    TransportFailureKind.READ_TIMEOUT,
                    RequestTransmissionPhase.REQUEST_SENT,
                    "POST",
                    IOException("timeout"),
                ),
            )

        assertEquals(FailureCategory.RESULT_UNKNOWN, result.category)
        assertFalse(result.retryable)
        assertTrue(result.errorCode.contains("request_sent"))
    }

    @Test
    fun `public transport exception preserves unknown outcome and retry boundary`() {
        val unknown =
            TaskFailureClassifier.fromException(
                HttpTransportException(
                    HttpFailure(
                        kind = HttpFailureKind.READ_TIMEOUT,
                        errorCode = "transport:read_timeout:request_sent",
                        retryable = false,
                        outcomeUnknown = true,
                    ),
                ),
            )
        val retryableRead =
            TaskFailureClassifier.fromException(
                HttpTransportException(
                    HttpFailure(
                        kind = HttpFailureKind.CONNECT_TIMEOUT,
                        errorCode = "transport:connect_timeout:before_send",
                        retryable = true,
                        outcomeUnknown = false,
                    ),
                ),
            )

        assertEquals(FailureCategory.RESULT_UNKNOWN, unknown.category)
        assertFalse(unknown.retryable)
        assertEquals(FailureCategory.NETWORK_TIMEOUT, retryableRead.category)
        assertTrue(retryableRead.retryable)
    }

    @Test
    fun `security rejected transport request is terminal`() {
        val result =
            TaskFailureClassifier.fromException(
                HttpTransportException(
                    HttpFailure(
                        kind = HttpFailureKind.SECURITY_REJECTED,
                        errorCode = "request-rejected",
                        retryable = false,
                        outcomeUnknown = false,
                    ),
                ),
            )

        assertEquals(FailureCategory.SECURITY_BLOCKED, result.category)
        assertFalse(result.retryable)
    }

    @Test
    fun `post connection failure before send remains retryable`() {
        val result =
            TaskFailureClassifier.fromException(
                TransportFailureException(
                    TransportFailureKind.CONNECT,
                    RequestTransmissionPhase.BEFORE_SEND,
                    "POST",
                    IOException("connect"),
                ),
            )

        assertEquals(FailureCategory.NETWORK_UNAVAILABLE, result.category)
        assertTrue(result.retryable)
    }

    @Test
    fun `tls and oversized response are terminal transport failures`() {
        val tls =
            TaskFailureClassifier.fromException(
                TransportFailureException(
                    TransportFailureKind.TLS,
                    RequestTransmissionPhase.BEFORE_SEND,
                    "GET",
                    IOException("tls"),
                ),
            )
        val oversizedPost =
            TaskFailureClassifier.fromException(
                TransportFailureException(
                    TransportFailureKind.RESPONSE_TOO_LARGE,
                    RequestTransmissionPhase.RESPONSE_BODY_READING,
                    "POST",
                    IOException("large"),
                ),
            )

        assertEquals(FailureCategory.NETWORK_UNAVAILABLE, tls.category)
        assertFalse(tls.retryable)
        assertEquals(FailureCategory.RESULT_UNKNOWN, oversizedPost.category)
        assertFalse(oversizedPost.retryable)
    }

    @Test
    fun `root block and no role use explicit categories`() {
        val root = TaskFailureClassifier.classify(false, true, "检测到Root，已阻断本次签到。")
        val noRole = TaskFailureClassifier.classify(true, true, "未绑定角色，已跳过")
        val unregistered = TaskFailureClassifier.classify(true, true, "未注册该游戏，已跳过签到")

        assertEquals(FailureCategory.SECURITY_BLOCKED, root.category)
        assertEquals(FailureCategory.NO_ROLE, noRole.category)
        assertEquals(FailureCategory.NO_ROLE, unregistered.category)
    }
}
