package com.questtick.sign

import com.questtick.data.TaskResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TaskResultAccumulatorTest {
    private val taskId = "account-1|MYS|Genshin"

    @Test
    fun `accepts only first terminal result for planned task`() {
        val accumulator = TaskResultAccumulator(listOf(taskId))
        val success = result(success = true, message = "签到成功")
        val failure = result(success = false, message = "异常失败")

        assertEquals(TaskResultAcceptance.ACCEPTED, accumulator.accept(taskId, success))
        assertEquals(TaskResultAcceptance.DUPLICATE, accumulator.accept(taskId, failure))

        val stored = accumulator.values().single()
        assertTrue(stored.success)
        assertEquals("签到成功", stored.message)
        assertEquals(taskId, stored.taskId)
        assertEquals("account-1", stored.accountId)
        assertEquals(1, accumulator.size)
    }

    @Test
    fun `rejects duplicate task ids in plan before execution`() {
        try {
            TaskResultAccumulator(listOf(taskId, taskId))
            fail("Duplicate task IDs should fail fast")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `rejects unplanned result without storing it`() {
        val accumulator = TaskResultAccumulator(listOf(taskId))

        val acceptance = accumulator.accept("account-2|MYS|Genshin", result(success = false, message = "失败"))

        assertEquals(TaskResultAcceptance.UNPLANNED, acceptance)
        assertFalse(accumulator.contains(taskId))
        assertTrue(accumulator.values().isEmpty())
    }

    private fun result(
        success: Boolean,
        message: String,
    ): TaskResult =
        TaskResult(
            game = "原神",
            gameKey = "Genshin",
            accountLabel = "主账号",
            success = success,
            skipped = false,
            message = message,
        )
}
