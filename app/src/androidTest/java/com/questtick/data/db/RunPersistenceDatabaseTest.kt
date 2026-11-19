package com.questtick.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.questtick.data.FailureCategory
import com.questtick.data.LogEntry
import com.questtick.data.RunRecord
import com.questtick.data.RunTerminationReason
import com.questtick.data.RunTrigger
import com.questtick.data.TaskResult
import com.questtick.repository.run.PostRunActionRequest
import com.questtick.repository.run.PostRunActionType
import com.questtick.repository.run.RunPersistenceRepository
import com.questtick.sign.RunTaskProgress
import com.questtick.sign.RunTaskType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RunPersistenceDatabaseTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: RunPersistenceRepository

    @Before
    fun setUp() {
        database =
            Room
                .inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    AppDatabase::class.java,
                ).allowMainThreadQueries()
                .build()
        repository = RunPersistenceRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun runLifecyclePersistsFirstTerminalResultAndCommitsAllViews() {
        val startedAt = System.currentTimeMillis() - 1_000L
        val tasks = taskPlan()
        repository.startRun("run-success", RunTrigger.MANUAL, startedAt, tasks)

        assertTrue(repository.markTaskRunning("run-success", tasks[0].id, "开始原神", startedAt + 1))
        assertFalse(repository.markTaskRunning("run-success", tasks[0].id, "重复开始", startedAt + 2))
        assertTrue(
            repository.persistTaskResult(
                "run-success",
                tasks[0].id,
                result(tasks[0], success = false, message = "首次失败"),
                startedAt + 3,
            ),
        )
        assertFalse(
            repository.persistTaskResult(
                "run-success",
                tasks[0].id,
                result(tasks[0], success = true, message = "迟到成功"),
                startedAt + 4,
            ),
        )

        val record =
            RunRecord(
                timestamp = startedAt + 10,
                runId = "run-success",
                trigger = RunTrigger.MANUAL,
                terminationReason = RunTerminationReason.COMPLETED,
                results =
                    listOf(
                        result(tasks[0], success = false, message = "首次失败"),
                        result(tasks[1], success = true, message = "签到成功"),
                    ),
            )
        repository.finishRun(
            record = record,
            logs = listOf(LogEntry(startedAt + 9, "INFO", "运行日志", "runId=run-success")),
            postRunActions =
                listOf(
                    PostRunActionRequest(PostRunActionType.NOTIFICATION),
                    PostRunActionRequest(PostRunActionType.EMAIL),
                ),
        )

        val run = requireNotNull(database.runDao().getRun("run-success"))
        assertEquals(RunPersistenceRepository.RUN_STATUS_COMPLETED, run.status)
        assertEquals(2, run.completedTasks)
        assertNotNull(database.historyDao().getByRunId("run-success"))
        assertEquals(1, database.logDao().count())
        val persistedTasks = database.runDao().getTasks("run-success")
        assertEquals("FAILED", persistedTasks.first { it.taskId == tasks[0].id }.status)
        assertEquals("SUCCESS", persistedTasks.first { it.taskId == tasks[1].id }.status)
        val attempts = database.runDao().getTaskAttempts("run-success")
        assertEquals(2, attempts.size)
        assertEquals("FAILED", attempts.first { it.taskId == tasks[0].id }.status)
        assertEquals("SUCCESS", attempts.first { it.taskId == tasks[1].id }.status)
        val postRunActions = database.postRunActionDao().getByRunId("run-success")
        assertEquals(2, postRunActions.size)
        assertTrue(postRunActions.all { it.status == RunPersistenceRepository.POST_ACTION_STATUS_PENDING })
        assertEquals(setOf("EMAIL", "NOTIFICATION"), postRunActions.mapTo(mutableSetOf()) { it.type })
        val calendar = database.calendarDao().getTaskStatesForDay(com.questtick.data.dayKey(startedAt))
        assertEquals(2, calendar.size)
        assertEquals(CALENDAR_TASK_STATUS_FAILED, calendar.first { it.taskId == tasks[0].id }.status)
        assertEquals(CALENDAR_TASK_STATUS_SIGNED, calendar.first { it.taskId == tasks[1].id }.status)
    }

    @Test
    fun signedTaskIdsForDayDoesNotReuseTheSameTaskFromAnotherCalendarDate() {
        val firstDay = 1_752_710_400_000L // 2025-07-15T00:00:00Z
        val secondDay = firstDay + 24L * 60 * 60 * 1000
        val task = taskPlan().first()
        repository.startRun("run-day-one", RunTrigger.MANUAL, firstDay, listOf(task))
        repository.finishRun(
            record =
                RunRecord(
                    timestamp = firstDay + 1,
                    runId = "run-day-one",
                    trigger = RunTrigger.MANUAL,
                    results = listOf(result(task, success = true, message = "签到成功")),
                ),
            logs = emptyList(),
            postRunActions = emptyList(),
        )

        assertEquals(setOf(task.id), repository.signedTaskIdsForDay(firstDay))
        assertEquals(emptySet<String>(), repository.signedTaskIdsForDay(secondDay))
    }

    @Test
    fun invalidFinalTaskSetRollsBackHistoryCalendarAndLogs() {
        val startedAt = System.currentTimeMillis() - 1_000L
        val tasks = taskPlan()
        repository.startRun("run-rollback", RunTrigger.SCHEDULED, startedAt, tasks)
        val incomplete =
            RunRecord(
                timestamp = startedAt + 10,
                runId = "run-rollback",
                trigger = RunTrigger.SCHEDULED,
                results = listOf(result(tasks[0], success = true, message = "成功")),
            )

        assertThrows(IllegalStateException::class.java) {
            repository.finishRun(incomplete, listOf(LogEntry(startedAt + 9, "INFO", "不应提交", "")))
        }

        assertEquals(RunPersistenceRepository.RUN_STATUS_RUNNING, database.runDao().getRun("run-rollback")?.status)
        assertEquals(0, database.historyDao().count())
        assertEquals(0, database.calendarDao().getAllTaskStates().size)
        assertEquals(0, database.logDao().count())
        assertEquals(0, database.postRunActionDao().getByRunId("run-rollback").size)
    }

    @Test
    fun outboxConflictRollsBackFinalRunTransaction() {
        val startedAt = System.currentTimeMillis() - 1_000L
        val tasks = taskPlan()
        repository.startRun("run-outbox-rollback", RunTrigger.MANUAL, startedAt, tasks)
        val conflicting =
            PostRunActionEntity(
                actionId = "run-outbox-rollback:NOTIFICATION",
                runId = "run-outbox-rollback",
                type = "NOTIFICATION",
                status = RunPersistenceRepository.POST_ACTION_STATUS_PENDING,
                payloadJson = "{}",
                attemptCount = 0,
                nextAttemptAt = startedAt,
                leaseUntil = 0L,
                lastErrorCategory = "",
                lastErrorCode = "",
                createdAt = startedAt,
                updatedAt = startedAt,
                deliveredAt = 0L,
            )
        database.postRunActionDao().insertAll(listOf(conflicting))
        val record =
            RunRecord(
                timestamp = startedAt + 10,
                runId = "run-outbox-rollback",
                trigger = RunTrigger.MANUAL,
                results = tasks.map { result(it, success = true, message = "成功") },
            )

        assertThrows(Exception::class.java) {
            repository.finishRun(
                record = record,
                logs = listOf(LogEntry(startedAt + 9, "INFO", "不应提交", "")),
                postRunActions = listOf(PostRunActionRequest(PostRunActionType.NOTIFICATION)),
            )
        }

        assertEquals(RunPersistenceRepository.RUN_STATUS_RUNNING, database.runDao().getRun(record.runId)?.status)
        assertEquals(0, database.historyDao().count())
        assertEquals(0, database.calendarDao().getAllTaskStates().size)
        assertEquals(0, database.logDao().count())
        assertEquals(listOf(conflicting), database.postRunActionDao().getByRunId(record.runId))
    }

    @Test
    fun cancellationKeepsConfirmedResultAndSeparatesUnknownFromNeverStarted() {
        val startedAt = System.currentTimeMillis() - 1_000L
        val tasks =
            taskPlan() +
                RunTaskProgress(
                    id = "account-a|MYS|ZZZ",
                    accountLabel = "账号A",
                    targetName = "绝区零",
                    type = RunTaskType.MYS,
                )
        repository.startRun("run-cancelled", RunTrigger.MANUAL, startedAt, tasks)
        assertTrue(
            repository.persistTaskResult(
                "run-cancelled",
                tasks[0].id,
                result(tasks[0], success = true, message = "已确认成功"),
                startedAt + 1,
            ),
        )
        assertTrue(repository.markTaskRunning("run-cancelled", tasks[1].id, "请求已开始", startedAt + 2))

        repository.cancelRun(
            runId = "run-cancelled",
            reason = RunTerminationReason.USER_CANCELLED,
            logs = listOf(LogEntry(startedAt + 3, "INFO", "用户取消", "")),
            now = startedAt + 4,
        )

        val run = requireNotNull(database.runDao().getRun("run-cancelled"))
        assertEquals(RunPersistenceRepository.RUN_STATUS_CANCELLED, run.status)
        assertEquals(RunTerminationReason.USER_CANCELLED.name, run.terminationReason)
        val persistedTasks = database.runDao().getTasks("run-cancelled")
        assertEquals("SUCCESS", persistedTasks.first { it.taskId == tasks[0].id }.status)
        assertEquals(
            RunPersistenceRepository.TASK_STATUS_RESULT_UNKNOWN,
            persistedTasks.first { it.taskId == tasks[1].id }.status,
        )
        assertEquals("CANCELLED", persistedTasks.first { it.taskId == tasks[2].id }.status)
        val attempts = database.runDao().getTaskAttempts("run-cancelled")
        assertEquals("SUCCESS", attempts.first { it.taskId == tasks[0].id }.status)
        assertEquals(RunPersistenceRepository.TASK_STATUS_RESULT_UNKNOWN, attempts.first { it.taskId == tasks[1].id }.status)
        assertEquals("CANCELLED", attempts.first { it.taskId == tasks[2].id }.status)
        assertEquals(FailureCategory.RESULT_UNKNOWN.name, attempts.first { it.taskId == tasks[1].id }.failureCategory)
        assertFalse(attempts.first { it.taskId == tasks[1].id }.retryable)
        assertEquals(0, database.historyDao().count())
        assertEquals(1, database.logDao().count())
        val calendar = database.calendarDao().getTaskStatesForDay(com.questtick.data.dayKey(startedAt))
        assertEquals(CALENDAR_TASK_STATUS_SIGNED, calendar.first { it.taskId == tasks[0].id }.status)
        assertEquals(CALENDAR_TASK_STATUS_RESULT_UNKNOWN, calendar.first { it.taskId == tasks[1].id }.status)
        assertFalse(calendar.any { it.taskId == tasks[2].id })
    }

    @Test
    fun currentRunFailureUsesInternalErrorInsteadOfProcessInterrupted() {
        val startedAt = System.currentTimeMillis() - 1_000L
        val tasks = taskPlan()
        repository.startRun("run-failed", RunTrigger.MANUAL, startedAt, tasks)
        assertTrue(repository.markTaskRunning("run-failed", tasks[0].id, "请求已开始", startedAt + 1))

        val summary =
            repository.failRun(
                runId = "run-failed",
                logs = listOf(LogEntry(startedAt + 2, "ERROR", "数据库异常", "")),
                now = startedAt + 3,
            )

        assertEquals(1, summary.recoveredRuns)
        val run = requireNotNull(database.runDao().getRun("run-failed"))
        assertEquals(RunPersistenceRepository.RUN_STATUS_FAILED, run.status)
        assertEquals(RunTerminationReason.INTERNAL_ERROR.name, run.terminationReason)
        val history = requireNotNull(database.historyDao().getByRunId("run-failed")).toModel()
        assertEquals(RunTerminationReason.INTERNAL_ERROR, history.terminationReason)
        assertEquals(FailureCategory.RESULT_UNKNOWN, history.results.first { it.taskId == tasks[0].id }.failureCategory)
        assertEquals("internal-error-result-unknown", history.results.first { it.taskId == tasks[0].id }.errorCode)
        assertEquals("internal-error-before-start", history.results.first { it.taskId == tasks[1].id }.errorCode)
    }

    @Test
    fun interruptedRunSeparatesUnknownRequestFromTaskNeverStarted() {
        val startedAt = 1L
        val tasks = taskPlan()
        repository.startRun("run-interrupted", RunTrigger.SCHEDULED, startedAt, tasks)
        assertTrue(repository.markTaskRunning("run-interrupted", tasks[0].id, "请求已开始", 2L))

        val sameProcessSummary = repository.recoverInterruptedRuns(now = 9L)
        assertEquals(0, sameProcessSummary.recoveredRuns)
        assertEquals(RunPersistenceRepository.RUN_STATUS_RUNNING, database.runDao().getRun("run-interrupted")?.status)

        // 新仓储实例代表应用重启后的新进程；它没有上一进程的活跃运行集合。
        val nextProcessRepository = RunPersistenceRepository(database)
        val summary = nextProcessRepository.recoverInterruptedRuns(now = 10L)

        assertEquals(1, summary.recoveredRuns)
        assertEquals(1, summary.uncertainTasks)
        assertEquals(1, summary.notStartedTasks)
        val run = requireNotNull(database.runDao().getRun("run-interrupted"))
        assertEquals(RunPersistenceRepository.RUN_STATUS_INTERRUPTED, run.status)
        assertEquals(RunTerminationReason.PROCESS_INTERRUPTED.name, run.terminationReason)
        val persistedTasks = database.runDao().getTasks("run-interrupted")
        assertEquals(
            RunPersistenceRepository.TASK_STATUS_RESULT_UNKNOWN,
            persistedTasks.first { it.taskId == tasks[0].id }.status,
        )
        assertEquals(
            RunPersistenceRepository.TASK_STATUS_INTERRUPTED,
            persistedTasks.first { it.taskId == tasks[1].id }.status,
        )
        val attempts = database.runDao().getTaskAttempts("run-interrupted")
        assertEquals(RunPersistenceRepository.TASK_STATUS_RESULT_UNKNOWN, attempts.first { it.taskId == tasks[0].id }.status)
        assertEquals(RunPersistenceRepository.TASK_STATUS_INTERRUPTED, attempts.first { it.taskId == tasks[1].id }.status)
        assertFalse(attempts.first { it.taskId == tasks[0].id }.retryable)
        assertTrue(attempts.first { it.taskId == tasks[1].id }.retryable)
        assertTrue(repository.uncertainTaskIdsForDay(startedAt).contains(tasks[0].id))
        assertFalse(repository.uncertainTaskIdsForDay(startedAt).contains(tasks[1].id))
        val history = requireNotNull(database.historyDao().getByRunId("run-interrupted")).toModel()
        assertEquals(RunTerminationReason.PROCESS_INTERRUPTED, history.terminationReason)
        assertEquals(2, history.total)
    }

    private fun taskPlan(): List<RunTaskProgress> =
        listOf(
            RunTaskProgress(
                id = "account-a|MYS|Genshin",
                accountLabel = "账号A",
                targetName = "原神",
                type = RunTaskType.MYS,
            ),
            RunTaskProgress(
                id = "account-a|MYS|StarRail",
                accountLabel = "账号A",
                targetName = "崩坏：星穹铁道",
                type = RunTaskType.MYS,
            ),
        )

    private fun result(
        task: RunTaskProgress,
        success: Boolean,
        message: String,
    ): TaskResult =
        TaskResult(
            game = task.targetName,
            gameKey = task.id.substringAfterLast('|'),
            accountLabel = task.accountLabel,
            accountId = task.id.substringBefore('|'),
            taskId = task.id,
            success = success,
            skipped = false,
            message = message,
        )
}
