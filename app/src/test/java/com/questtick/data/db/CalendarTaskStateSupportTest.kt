package com.questtick.data.db

import com.questtick.data.FailureCategory
import com.questtick.data.RunRecord
import com.questtick.data.RunTerminationReason
import com.questtick.data.RunTrigger
import com.questtick.data.SignInCalendarDay
import com.questtick.data.TaskResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarTaskStateSupportTest {
    @Test
    fun `later success replaces failure for same task`() {
        val failed = state("FAILED", updatedAt = 10L)
        val signed = state("SIGNED", updatedAt = 20L)

        val merged = mergeCalendarTaskState(failed, signed)

        assertEquals(CALENDAR_TASK_STATUS_SIGNED, merged.status)
        assertEquals(20L, merged.updatedAt)
    }

    @Test
    fun `later failure cannot replace confirmed success`() {
        val signed = state("SIGNED", updatedAt = 10L)
        val failed = state("FAILED", updatedAt = 20L)

        val merged = mergeCalendarTaskState(signed, failed)

        assertEquals(CALENDAR_TASK_STATUS_SIGNED, merged.status)
        assertEquals(10L, merged.updatedAt)
    }

    @Test
    fun `unknown result is preserved until explicit success arrives`() {
        val unknown = state(CALENDAR_TASK_STATUS_RESULT_UNKNOWN, updatedAt = 20L)
        val failed = state(CALENDAR_TASK_STATUS_FAILED, updatedAt = 30L)
        val signed = state(CALENDAR_TASK_STATUS_SIGNED, updatedAt = 40L)

        assertEquals(CALENDAR_TASK_STATUS_RESULT_UNKNOWN, mergeCalendarTaskState(unknown, failed).status)
        assertEquals(CALENDAR_TASK_STATUS_SIGNED, mergeCalendarTaskState(unknown, signed).status)
        assertEquals(CALENDAR_TASK_STATUS_RESULT_UNKNOWN, mergeCalendarTaskState(signed, unknown).status)
    }

    @Test
    fun `day summary is derived from independent task states`() {
        val states =
            listOf(
                state(CALENDAR_TASK_STATUS_SIGNED, taskId = "account-a|MYS|Genshin"),
                state(CALENDAR_TASK_STATUS_FAILED, taskId = "account-b|MYS|Genshin"),
            )

        val day = calendarDaysFromTaskStates(states).single()

        assertEquals(SignInCalendarDay.STATUS_PARTIAL, day.status)
        assertEquals(2, day.taskCount)
    }

    @Test
    fun `skipped result unknown remains visible in task calendar`() {
        val unknown =
            result("account-a|MYS|Genshin", success = false, skipped = true).copy(
                failureCategory = FailureCategory.RESULT_UNKNOWN,
            )
        val record = RunRecord(timestamp = 1_700_000_000_000L, runId = "run-unknown", results = listOf(unknown))

        val state = calendarTaskStatesFromRecord(record).single()

        assertEquals(CALENDAR_TASK_STATUS_RESULT_UNKNOWN, state.status)
    }

    @Test
    fun `record conversion keeps stable task identity and ignores ordinary skipped results`() {
        val record =
            RunRecord(
                timestamp = 1_700_000_000_000L,
                runId = "run-1",
                trigger = RunTrigger.MANUAL,
                terminationReason = RunTerminationReason.COMPLETED,
                results =
                    listOf(
                        result("account-a|MYS|Genshin", success = true),
                        result("account-a|MYS|StarRail", success = false),
                        result("account-a|MYS|ZZZ", success = false, skipped = true),
                    ),
            )

        val states = calendarTaskStatesFromRecord(record)

        assertEquals(2, states.size)
        assertEquals(setOf("account-a|MYS|Genshin", "account-a|MYS|StarRail"), states.map { it.taskId }.toSet())
        assertTrue(states.all { it.sourceRunId == "run-1" })
    }

    private fun state(
        status: String,
        taskId: String = "account-a|MYS|Genshin",
        updatedAt: Long = 1L,
    ) = CalendarTaskStateEntity(
        dayKey = "20260710",
        taskId = taskId,
        accountId = taskId.substringBefore('|'),
        taskType = "MYS",
        gameKey = taskId.substringAfterLast('|'),
        status = status,
        updatedAt = updatedAt,
        sourceRunId = "run-1",
    )

    private fun result(
        taskId: String,
        success: Boolean,
        skipped: Boolean = false,
    ) = TaskResult(
        game = taskId.substringAfterLast('|'),
        gameKey = taskId.substringAfterLast('|'),
        accountLabel = "账号A",
        accountId = taskId.substringBefore('|'),
        taskId = taskId,
        success = success,
        skipped = skipped,
        message = if (success) "成功" else "失败",
    )
}
