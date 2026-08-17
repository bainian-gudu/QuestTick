package com.questtick.work

import com.questtick.data.FailureCategory
import com.questtick.data.RunRecord
import com.questtick.data.RunTerminationReason
import com.questtick.data.TaskResult
import com.questtick.repository.run.ExecutionStateRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SignInWorkerPolicyTest {
    @Test
    fun resultUnknownNeverRetries() {
        val decision = decideSignInWorkerResult(record(FailureCategory.RESULT_UNKNOWN, retryable = false, skipped = true), 0, 3)

        assertFalse(decision.retry)
        assertEquals(FailureCategory.RESULT_UNKNOWN, decision.category)
    }

    @Test
    fun rootCheckFailureNeverRetries() {
        val decision =
            decideSignInWorkerResult(
                record(FailureCategory.SECURITY_BLOCKED).copy(
                    terminationReason = RunTerminationReason.ROOT_CHECK_FAILED,
                ),
                0,
                3,
            )

        assertFalse(decision.retry)
        assertEquals(FailureCategory.SECURITY_BLOCKED, decision.category)
    }

    @Test
    fun rateLimitUsesLongRetryDelay() {
        val decision = decideSignInWorkerResult(record(FailureCategory.RATE_LIMITED, retryable = true), 0, 3)

        assertTrue(decision.retry)
        assertEquals(ExecutionStateRepository.RATE_LIMIT_RETRY_DELAY_MS, decision.retryDelayMillis)
    }

    @Test
    fun temporaryNetworkFailureUsesNetworkDelay() {
        val decision = decideSignInWorkerResult(record(FailureCategory.NETWORK_TIMEOUT, retryable = true), 1, 3)

        assertTrue(decision.retry)
        assertEquals(ExecutionStateRepository.NETWORK_RETRY_DELAY_MS, decision.retryDelayMillis)
    }

    @Test
    fun authFailureDoesNotSuppressRetryableFailureFromAnotherTask() {
        val record =
            RunRecord(
                timestamp = 1L,
                runId = "run-mixed",
                results =
                    listOf(
                        result(FailureCategory.AUTH_EXPIRED, retryable = false, gameKey = "Genshin"),
                        result(FailureCategory.NETWORK_UNAVAILABLE, retryable = true, gameKey = "StarRail"),
                    ),
            )

        val decision = decideSignInWorkerResult(record, 0, 3)

        assertTrue(decision.retry)
        assertEquals(FailureCategory.NETWORK_UNAVAILABLE, decision.category)
    }

    @Test
    fun retryLimitStopsFurtherAttempts() {
        val decision = decideSignInWorkerResult(record(FailureCategory.SERVER_ERROR, retryable = true), 3, 3)

        assertFalse(decision.retry)
        assertEquals(FailureCategory.SERVER_ERROR, decision.category)
    }

    private fun record(
        category: FailureCategory,
        retryable: Boolean = false,
        skipped: Boolean = false,
    ): RunRecord =
        RunRecord(timestamp = 1L, runId = "run-1", results = listOf(result(category, retryable, skipped)))

    private fun result(
        category: FailureCategory,
        retryable: Boolean,
        skipped: Boolean = false,
        gameKey: String = "Genshin",
    ): TaskResult =
        TaskResult(
            game = gameKey,
            gameKey = gameKey,
            accountLabel = "A",
            accountId = "account-$gameKey",
            taskId = "account-$gameKey|MYS|$gameKey",
            success = false,
            skipped = skipped,
            message = "structured failure",
            failureCategory = category,
            retryable = retryable,
        )
}
