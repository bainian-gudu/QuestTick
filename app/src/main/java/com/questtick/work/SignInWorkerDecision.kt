package com.questtick.work

import com.questtick.data.FailureCategory
import com.questtick.data.RunRecord
import com.questtick.data.RunTerminationReason
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
    if (record.resultUnknown > 0) {
        return SignInWorkerDecision(retry = false, category = FailureCategory.RESULT_UNKNOWN)
    }
    if (
        record.terminationReason == RunTerminationReason.ROOT_DETECTED ||
        record.terminationReason == RunTerminationReason.ROOT_CHECK_FAILED
    ) {
        return SignInWorkerDecision(retry = false, category = FailureCategory.SECURITY_BLOCKED)
    }
    val failures = record.results.filter { !it.success && !it.skipped }
    if (failures.isEmpty()) return SignInWorkerDecision(retry = false)

    val terminalCategory =
        listOf(
            FailureCategory.CAPTCHA_REQUIRED,
            FailureCategory.SMS_REQUIRED,
            FailureCategory.AUTH_EXPIRED,
            FailureCategory.RATE_LIMITED,
            FailureCategory.NETWORK_TIMEOUT,
            FailureCategory.NETWORK_UNAVAILABLE,
            FailureCategory.SERVER_ERROR,
        ).firstOrNull { category -> failures.any { it.failureCategory == category } }
            ?: failures.first().failureCategory

    val retryableFailures = failures.filter { it.retryable }
    if (runAttemptCount >= maxRetries || retryableFailures.isEmpty()) {
        return SignInWorkerDecision(retry = false, category = terminalCategory)
    }
    val retryCategory =
        listOf(
            FailureCategory.RATE_LIMITED,
            FailureCategory.NETWORK_TIMEOUT,
            FailureCategory.NETWORK_UNAVAILABLE,
            FailureCategory.SERVER_ERROR,
        ).firstOrNull { category -> retryableFailures.any { it.failureCategory == category } }
            ?: retryableFailures.first().failureCategory
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
