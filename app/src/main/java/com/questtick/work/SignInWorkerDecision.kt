package com.questtick.work

import com.questtick.data.FailureCategory
import com.questtick.data.RunRecord
import com.questtick.data.RunTerminationReason
import com.questtick.data.TaskResult
import com.questtick.repository.run.ExecutionStateRepository

/** Worker 对结构化运行结果的唯一决策，避免依赖中文文案。 */
internal data class SignInWorkerDecision(
    val retry: Boolean,
    val retryDelayMillis: Long? = null,
    val category: FailureCategory = FailureCategory.NONE,
)

internal fun decideSignInWorkerResult(
    record: RunRecord,
    runAttemptCount: Int,
    maxRetries: Int,
): SignInWorkerDecision {
    val failures = record.results.filter { !it.success && !it.skipped }
    val retryableFailures = failures.filter { it.retryable }
    return when {
        record.resultUnknown > 0 -> {
            SignInWorkerDecision(retry = false, category = FailureCategory.RESULT_UNKNOWN)
        }

        record.terminationReason == RunTerminationReason.ROOT_DETECTED ||
            record.terminationReason == RunTerminationReason.ROOT_CHECK_FAILED -> {
            SignInWorkerDecision(retry = false, category = FailureCategory.SECURITY_BLOCKED)
        }

        failures.isEmpty() -> {
            SignInWorkerDecision(retry = false)
        }

        runAttemptCount >= maxRetries || retryableFailures.isEmpty() -> {
            SignInWorkerDecision(
                retry = false,
                category = terminalFailureCategory(failures) ?: FailureCategory.NONE,
            )
        }

        else -> {
            retryDecision(retryableFailures)
        }
    }
}

private fun terminalFailureCategory(failures: List<TaskResult>): FailureCategory? =
    listOf(
        FailureCategory.CAPTCHA_REQUIRED,
        FailureCategory.SMS_REQUIRED,
        FailureCategory.AUTH_EXPIRED,
        FailureCategory.RATE_LIMITED,
        FailureCategory.NETWORK_TIMEOUT,
        FailureCategory.NETWORK_UNAVAILABLE,
        FailureCategory.SERVER_ERROR,
    ).firstOrNull { category -> failures.any { it.failureCategory == category } }
        ?: failures.firstOrNull()?.failureCategory

private fun retryDecision(retryableFailures: List<TaskResult>): SignInWorkerDecision {
    val retryCategory =
        listOf(
            FailureCategory.RATE_LIMITED,
            FailureCategory.NETWORK_TIMEOUT,
            FailureCategory.NETWORK_UNAVAILABLE,
            FailureCategory.SERVER_ERROR,
        ).firstOrNull { category -> retryableFailures.any { it.failureCategory == category } }
            ?: retryableFailures.firstOrNull()?.failureCategory
            ?: FailureCategory.NONE
    return when (retryCategory) {
        FailureCategory.RATE_LIMITED -> {
            SignInWorkerDecision(
                retry = true,
                retryDelayMillis = ExecutionStateRepository.RATE_LIMIT_RETRY_DELAY_MS,
                category = retryCategory,
            )
        }

        FailureCategory.NETWORK_TIMEOUT,
        FailureCategory.NETWORK_UNAVAILABLE,
        FailureCategory.SERVER_ERROR,
        -> {
            SignInWorkerDecision(
                retry = true,
                retryDelayMillis = ExecutionStateRepository.NETWORK_RETRY_DELAY_MS,
                category = retryCategory,
            )
        }

        else -> {
            SignInWorkerDecision(retry = false, category = retryCategory)
        }
    }
}
