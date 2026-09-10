package com.questtick.sign

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SignInRunCoordinatorTest {
    @Test
    fun coordinatorUpdatesTaskQueueState() =
        runTest {
            val coordinator = SignInRunCoordinator()
            val task =
                RunTaskProgress(
                    id = "account|MYS|Genshin",
                    accountLabel = "账号A",
                    targetName = "原神",
                    type = RunTaskType.MYS,
                )

            coordinator.tryRun {
                coordinator.setTaskPlan(listOf(task))
                coordinator.markTaskRunning(task.id, "账号A · 原神")
                assertTrue(coordinator.progress.value.running)
                assertEquals(RunProgressPhase.SIGNING, coordinator.progress.value.phase)
                assertEquals(
                    RunTaskStatus.RUNNING,
                    coordinator.progress.value.tasks
                        .single()
                        .status,
                )

                coordinator.markTaskFinished(task.id, RunTaskStatus.SUCCESS, "签到成功")
                assertEquals(1, coordinator.progress.value.done)
                assertEquals(1, coordinator.progress.value.success)
                coordinator.finishProgress(RunProgressPhase.FINISHED, "签到完成")
            }

            assertFalse(coordinator.progress.value.running)
            assertEquals(RunProgressPhase.FINISHED, coordinator.progress.value.phase)
            assertEquals("签到完成", coordinator.progress.value.message)
        }

    @Test
    fun terminalTaskCannotBeReopenedOrOverwritten() =
        runTest {
            val coordinator = SignInRunCoordinator()
            val task = RunTaskProgress("task", "账号A", "原神", RunTaskType.MYS)

            coordinator.tryRun {
                coordinator.setTaskPlan(listOf(task))
                coordinator.markTaskFinished(task.id, RunTaskStatus.SUCCESS, "签到成功")
                coordinator.markTaskRunning(task.id, "迟到的运行回调")
                coordinator.markTaskFinished(task.id, RunTaskStatus.FAILED, "迟到的失败回调")
                coordinator.markTaskRunning("unknown", "未知任务")
            }

            val progress = coordinator.progress.value
            assertEquals(RunTaskStatus.SUCCESS, progress.tasks.single().status)
            assertEquals("签到成功", progress.tasks.single().message)
            assertEquals("", progress.currentTaskId)
        }

    @Test
    fun cancellationKeepsFinishedTasksAndCancelsUnfinishedTasks() =
        runTest {
            val coordinator = SignInRunCoordinator()
            val completed = RunTaskProgress("done", "账号A", "原神", RunTaskType.MYS)
            val running = RunTaskProgress("running", "账号A", "星铁", RunTaskType.MYS)
            val pending = RunTaskProgress("pending", "账号A", "绝区零", RunTaskType.MYS)

            try {
                coordinator.tryRun {
                    coordinator.setTaskPlan(listOf(completed, running, pending))
                    coordinator.markTaskFinished(completed.id, RunTaskStatus.SUCCESS, "签到成功")
                    coordinator.markTaskRunning(running.id, "正在签到")
                    throw CancellationException("cancel")
                }
            } catch (_: CancellationException) {
                // expected
            }

            val progress = coordinator.progress.value
            assertFalse(progress.running)
            assertEquals(RunProgressPhase.CANCELLED, progress.phase)
            assertEquals(RunTaskStatus.SUCCESS, progress.tasks[0].status)
            assertEquals(RunTaskStatus.CANCELLED, progress.tasks[1].status)
            assertEquals(RunTaskStatus.CANCELLED, progress.tasks[2].status)
            assertEquals(3, progress.done)
            assertEquals(2, progress.cancelled)
        }
}
