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

/**
 * 运行锁忙到上限时放弃本业务日重试。
 *
 * WorkManager 的退避是指数增长（5 分钟起步、上限 5 小时），锁一直被占会让「今天」无限重试；
 * 到达上限后结束本业务日，下一业务日调度由 Worker 在取锁前独立补齐，不会因此断链。
 */
internal fun shouldGiveUpWhenRunLockBusy(
    runAttemptCount: Int,
    maxAttempts: Int,
): Boolean = maxAttempts > 0 && runAttemptCount >= maxAttempts

/**
 * 触发时间是否明显早于计划时刻。
 *
 * WorkManager 的 initialDelay 按真实经过时间倒计时，计划时刻却可能来自可信时钟（网络时间）；
 * 设备时钟或时区被调整后，任务会在计划时刻之前被放行，日志里就会出现
 * 「05:00 的任务在 02:10 触发」这种偏移。提前触发必须按当前时间重排，而不是照常签到。
 */
internal fun firedTooEarly(
    nowMillis: Long,
    scheduledTargetAt: Long,
    toleranceMillis: Long,
): Boolean = toleranceMillis > 0L && scheduledTargetAt - nowMillis > toleranceMillis
