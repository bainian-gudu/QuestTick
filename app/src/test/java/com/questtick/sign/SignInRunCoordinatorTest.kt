package com.questtick.sign

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun busyLockRejectsSecondRunAndExposesHolder() =
        runTest {
            val coordinator = SignInRunCoordinator { 1_000L }
            val holding =
                async {
                    coordinator.tryRunAs(owner = "定时签到") { _ -> awaitCancellation() }
                }
            runCurrent()

            assertNull(coordinator.tryRunAs(owner = "手动签到") { _ -> "不应执行" })
            assertEquals("定时签到", coordinator.lockHolder.value?.owner)
            assertTrue(coordinator.running.value)

            holding.cancelAndJoin()
            assertNull(coordinator.lockHolder.value)
            assertFalse(coordinator.running.value)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun staleLockIsTakenOverAndPreviousReleaseIsIgnored() =
        runTest {
            var now = 0L
            val coordinator = SignInRunCoordinator { now }
            val holding =
                async {
                    coordinator.tryRunAs(owner = "定时签到", staleAfterMillis = 1_000L) { _ -> awaitCancellation() }
                }
            runCurrent()

            // 未到阈值：仍按「已有任务在运行」处理
            now = 900L
            assertNull(coordinator.tryRunAs(owner = "手动签到", staleAfterMillis = 1_000L) { _ -> "不应执行" })

            // 超过阈值：接管过期锁，且旧持锁者的释放不能清掉接管方现场
            now = 1_200L
            val outcome =
                coordinator.tryRunAs(owner = "手动签到", staleAfterMillis = 1_000L) { _ ->
                    holding.cancelAndJoin()
                    assertEquals("手动签到", coordinator.lockHolder.value?.owner)
                    assertTrue(coordinator.running.value)
                    assertNull(coordinator.tryRunAs(owner = "第三次", staleAfterMillis = 1_000L) { _ -> "不应执行" })
                    "接管后照常签到"
                }

            assertEquals("接管后照常签到", outcome?.value)
            assertEquals("定时签到", outcome?.staleTakeover?.owner)
            assertEquals(0L, outcome?.staleTakeover?.acquiredAtMillis)
            assertNull(coordinator.lockHolder.value)
            assertFalse(coordinator.running.value)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun progressUpdatesKeepALongRunningLockFresh() =
        runTest {
            var now = 0L
            val coordinator = SignInRunCoordinator { now }
            val holding =
                async {
                    coordinator.tryRunAs(owner = "定时签到", staleAfterMillis = 1_000L) { _ ->
                        // 运行持续超过接管阈值，但期间一直在推进进度。
                        repeat(5) {
                            now += 400L
                            coordinator.updateProgress { it }
                        }
                        awaitCancellation()
                    }
                }
            runCurrent()

            // 距取得锁已 2 000ms（超过 1 000ms 阈值），但最近一次进展只过了 400ms。
            now = 2_000L
            assertNull(coordinator.tryRunAs(owner = "手动签到", staleAfterMillis = 1_000L) { _ -> "不应执行" })

            holding.cancelAndJoin()
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun leaseStopsBeingHeldAfterTakeover() =
        runTest {
            var now = 0L
            val coordinator = SignInRunCoordinator { now }
            val staleLeases = mutableListOf<RunLease>()
            val holding =
                async {
                    coordinator.tryRunAs(owner = "定时签到", staleAfterMillis = 1_000L) { lease ->
                        staleLeases += lease
                        awaitCancellation()
                    }
                }
            runCurrent()

            now = 5_000L
            val takeover =
                coordinator.tryRunAs(owner = "手动签到", staleAfterMillis = 1_000L) { lease ->
                    // 接管方自己的凭据有效，被接管方的凭据必须立刻失效。
                    assertTrue(coordinator.isLeaseHeld(lease))
                    assertFalse(coordinator.isLeaseHeld(staleLeases.single()))
                }

            assertNotNull(takeover)
            holding.cancelAndJoin()
            assertNull(coordinator.lockHolder.value)
        }
}
