package com.questtick.data.db

import com.questtick.data.FailureCategory
import com.questtick.data.RunRecord
import com.questtick.data.SignInCalendarDay
import com.questtick.data.TaskResult
import com.questtick.data.dayKey

internal const val CALENDAR_TASK_STATUS_SIGNED = "SIGNED"
internal const val CALENDAR_TASK_STATUS_FAILED = "FAILED"
internal const val CALENDAR_TASK_STATUS_RESULT_UNKNOWN = "RESULT_UNKNOWN"

internal fun calendarTaskStatesFromRecord(record: RunRecord): List<CalendarTaskStateEntity> =
    record.results.mapNotNull { result ->
        calendarTaskStateFromResult(record, result)
    }

private fun calendarTaskStateFromResult(
    record: RunRecord,
    result: TaskResult,
): CalendarTaskStateEntity? {
    if (result.skipped && result.failureCategory != FailureCategory.RESULT_UNKNOWN) return null
    val status =
        when {
            result.failureCategory == FailureCategory.RESULT_UNKNOWN -> CALENDAR_TASK_STATUS_RESULT_UNKNOWN
            result.success -> CALENDAR_TASK_STATUS_SIGNED
            else -> CALENDAR_TASK_STATUS_FAILED
        }
    require(record.runId.isNotBlank()) { "Calendar record must have a runId" }
    require(result.taskId.isNotBlank()) { "Calendar task must have a taskId" }
    require(result.accountId.isNotBlank()) { "Calendar task must have an accountId" }
    return CalendarTaskStateEntity(
        dayKey = dayKey(record.timestamp),
        taskId = result.taskId,
        accountId = result.accountId,
        taskType = if (result.gameKey == "CloudYS" || result.gameKey == "CloudSR") "CLOUD" else "MYS",
        gameKey = result.gameKey,
        status = status,
        updatedAt = record.timestamp,
        sourceRunId = record.runId,
    )
}

/**
 * 同日同任务状态合并：明确成功可以覆盖失败或未知；失败和未知都不能覆盖已确认成功。
 * 未知结果比普通失败更保守，只有后续明确成功才能解除。
 */
internal fun mergeCalendarTaskState(
    old: CalendarTaskStateEntity?,
    new: CalendarTaskStateEntity,
): CalendarTaskStateEntity {
    if (old == null) return new
    return when {
        new.status == CALENDAR_TASK_STATUS_RESULT_UNKNOWN -> new
        new.status == CALENDAR_TASK_STATUS_SIGNED -> new
        old.status == CALENDAR_TASK_STATUS_SIGNED -> old
        old.status == CALENDAR_TASK_STATUS_RESULT_UNKNOWN && new.status == CALENDAR_TASK_STATUS_FAILED -> old
        new.updatedAt >= old.updatedAt -> new
        else -> old
    }
}

internal fun calendarDaysFromTaskStates(states: List<CalendarTaskStateEntity>): List<SignInCalendarDay> =
    states.groupBy { it.dayKey }
        .mapNotNull { (dayKey, items) ->
            if (items.isEmpty()) return@mapNotNull null
            val signed = items.count { it.status == CALENDAR_TASK_STATUS_SIGNED }
            val failed = items.count {
                it.status == CALENDAR_TASK_STATUS_FAILED || it.status == CALENDAR_TASK_STATUS_RESULT_UNKNOWN
            }
            val status =
                when {
                    signed > 0 && failed > 0 -> SignInCalendarDay.STATUS_PARTIAL
                    signed > 0 -> SignInCalendarDay.STATUS_SIGNED
                    failed > 0 -> SignInCalendarDay.STATUS_FAILED
                    else -> return@mapNotNull null
                }
            SignInCalendarDay(
                dayKey = dayKey,
                status = status,
                taskCount = items.size,
                updatedAt = items.maxOf { it.updatedAt },
            )
        }
        .sortedBy { it.dayKey }
