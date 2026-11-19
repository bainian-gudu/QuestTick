package com.questtick.ui.vm

import com.questtick.data.FailureCategory
import com.questtick.data.TaskResult
import com.questtick.sign.RunTaskProgress
import com.questtick.sign.RunTaskStatus
import com.questtick.sign.RunTaskType
import com.questtick.sign.runProgressTaskPreview
import com.questtick.sign.taskStatusFromResult
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeProgressLogicTest {
    @Test
    fun taskStatusFromResultMapsKnownResultStates() {
        assertEquals(RunTaskStatus.SKIPPED, taskStatusFromResult(result("跳过", success = false, skipped = true)))
        assertEquals(RunTaskStatus.ALREADY_SIGNED, taskStatusFromResult(result("已签", success = true, alreadySigned = true)))
        assertEquals(RunTaskStatus.SUCCESS, taskStatusFromResult(result("成功", success = true)))
        assertEquals(RunTaskStatus.FAILED, taskStatusFromResult(result("失败", success = false)))
        assertEquals(
            RunTaskStatus.RESULT_UNKNOWN,
            taskStatusFromResult(
                result("结果待确认", success = false, skipped = true).copy(
                    failureCategory = FailureCategory.RESULT_UNKNOWN,
                ),
            ),
        )
    }

    @Test
    fun runProgressTaskPreviewKeepsOriginalTaskOrderAroundActiveTask() {
        val tasks =
            listOf(
                task("pending-old", RunTaskStatus.PENDING),
                task("done-1", RunTaskStatus.SUCCESS),
                task("running", RunTaskStatus.RUNNING),
                task("done-2", RunTaskStatus.FAILED),
                task("pending-new", RunTaskStatus.PENDING),
            )

        val preview = runProgressTaskPreview(tasks, maxItems = 4)

        assertEquals(
            listOf("pending-old", "done-1", "running", "done-2"),
            preview.map { it.id },
        )
    }

    @Test
    fun runProgressTaskPreviewCapsMinimumSize() {
        val preview =
            runProgressTaskPreview(
                listOf(
                    task("running", RunTaskStatus.RUNNING),
                    task("pending", RunTaskStatus.PENDING),
                ),
                maxItems = 0,
            )

        assertEquals(listOf("running"), preview.map { it.id })
    }

    private fun task(
        id: String,
        status: RunTaskStatus,
    ): RunTaskProgress =
        RunTaskProgress(
            id = id,
            accountLabel = "账号A",
            targetName = id,
            type = RunTaskType.MYS,
            status = status,
        )

    private fun result(
        label: String,
        success: Boolean,
        skipped: Boolean = false,
        alreadySigned: Boolean = false,
    ): TaskResult =
        TaskResult(
            game = "原神",
            gameKey = "Genshin",
            accountLabel = label,
            success = success,
            skipped = skipped,
            alreadySigned = alreadySigned,
            message = label,
        )
}
