package com.questtick.sign

import com.questtick.data.FailureCategory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SignInCredentialCoordinatorDecisionTest {
    @Test
    fun `mys cookie refresh requires keep login expired failure and not already refreshed`() {
        val failed = MysSignIn.Outcome(success = false, skipped = false, message = "登录状态已失效")
        val skipped = MysSignIn.Outcome(success = true, skipped = true, message = "跳过")
        val success = MysSignIn.Outcome(success = true, skipped = false, message = "签到成功")

        assertTrue(shouldRefreshMysCookieDecision(true, failed, false, true))
        assertFalse(shouldRefreshMysCookieDecision(false, failed, false, true))
        assertFalse(shouldRefreshMysCookieDecision(true, failed, true, true))
        assertFalse(shouldRefreshMysCookieDecision(true, skipped, false, true))
        assertFalse(shouldRefreshMysCookieDecision(true, success, false, true))
        assertFalse(shouldRefreshMysCookieDecision(true, failed, false, false))
    }

    @Test
    fun `cloud token refresh requires keep login expired failure and not already refreshed`() {
        val failed = CloudSignIn.Outcome(success = false, skipped = false, message = "token invalid")
        val skipped = CloudSignIn.Outcome(success = true, skipped = true, message = "跳过")
        val success = CloudSignIn.Outcome(success = true, skipped = false, message = "领取完成")

        assertTrue(shouldRefreshCloudTokenDecision(true, failed, false, true))
        assertFalse(shouldRefreshCloudTokenDecision(false, failed, false, true))
        assertFalse(shouldRefreshCloudTokenDecision(true, failed, true, true))
        assertFalse(shouldRefreshCloudTokenDecision(true, skipped, false, true))
        assertFalse(shouldRefreshCloudTokenDecision(true, success, false, true))
        assertFalse(shouldRefreshCloudTokenDecision(true, failed, false, false))
    }

    @Test
    fun `structured auth failure triggers refresh without relying on display text`() {
        val mys =
            MysSignIn.Outcome(
                success = false,
                skipped = false,
                message = "凭证需要处理",
                failure = TaskFailureDescriptor(FailureCategory.AUTH_EXPIRED, "retcode:-100"),
            )
        val cloud =
            CloudSignIn.Outcome(
                success = false,
                skipped = false,
                message = "凭证需要处理",
                failure = TaskFailureDescriptor(FailureCategory.AUTH_EXPIRED, "retcode:10001"),
            )

        assertTrue(shouldRefreshMysCookieDecision(true, mys, cookieRefreshed = false, expired = false))
        assertTrue(shouldRefreshCloudTokenDecision(true, cloud, tokenRefreshed = false, expired = false))
    }

    @Test
    fun `structured non auth failure is not overridden by legacy text flag`() {
        val outcome =
            MysSignIn.Outcome(
                success = false,
                skipped = false,
                message = "服务端失败",
                failure = TaskFailureDescriptor(FailureCategory.SERVER_ERROR, "retcode:-502", retryable = true),
            )

        assertFalse(shouldRefreshMysCookieDecision(true, outcome, cookieRefreshed = false, expired = true))
    }
}
