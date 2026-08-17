package com.questtick.sign

import com.questtick.data.FailureCategory
import com.questtick.data.RunRecord
import com.questtick.data.TaskResult
import org.junit.Assert.assertEquals
import org.junit.Test

class RiskStateTest {
    @Test
    fun structuredCaptchaAndSmsAndRateLimitAreClassified() {
        assertEquals(RiskState.CAPTCHA, RiskState.fromRecord(record(FailureCategory.CAPTCHA_REQUIRED)))
        assertEquals(RiskState.SMS, RiskState.fromRecord(record(FailureCategory.SMS_REQUIRED)))
        assertEquals(RiskState.BLOCKED, RiskState.fromRecord(record(FailureCategory.RATE_LIMITED)))
    }

    @Test
    fun localSecurityBlockIsNotTreatedAsRemoteRiskControl() {
        assertEquals(RiskState.NORMAL, RiskState.fromRecord(record(FailureCategory.SECURITY_BLOCKED)))
    }

    @Test
    fun displayTextNeverOverridesStructuredCategory() {
        val result =
            result(FailureCategory.INTERNAL_ERROR).copy(
                message = "此展示文案包含验证码、短信验证和操作过于频繁",
            )

        assertEquals(RiskState.NORMAL, RiskState.fromRecord(runRecord(listOf(result))))
    }

    @Test
    fun recordUsesHighestStructuredRiskPriority() {
        val record =
            runRecord(
                listOf(
                    result(FailureCategory.CAPTCHA_REQUIRED, "Genshin"),
                    result(FailureCategory.SMS_REQUIRED, "StarRail"),
                    result(FailureCategory.RATE_LIMITED, "ZZZ"),
                ),
            )

        assertEquals(RiskState.BLOCKED, RiskState.fromRecord(record))
    }

    private fun record(category: FailureCategory): RunRecord = runRecord(listOf(result(category)))

    private fun runRecord(results: List<TaskResult>): RunRecord =
        RunRecord(timestamp = 1L, runId = "run-1", results = results)

    private fun result(
        category: FailureCategory,
        gameKey: String = "Genshin",
    ): TaskResult =
        TaskResult(
            game = gameKey,
            gameKey = gameKey,
            accountLabel = "A",
            success = false,
            skipped = false,
            message = "结构化结果",
            taskId = "account-1|MYS|$gameKey",
            accountId = "account-1",
            failureCategory = category,
        )
}
