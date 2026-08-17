package com.questtick.repository.run

import com.questtick.data.FailureCategory
import com.questtick.data.LogEntry
import com.questtick.data.LogLevel
import com.questtick.data.RunRecord
import com.questtick.data.RunTerminationReason
import com.questtick.data.RunTrigger
import com.questtick.data.TaskResult
import com.questtick.data.dayKey
import com.questtick.data.db.AppDatabase
import com.questtick.data.db.CALENDAR_TASK_STATUS_RESULT_UNKNOWN
import com.questtick.data.db.CalendarTaskStateEntity
import com.questtick.data.db.LogEntity
import com.questtick.data.db.SignHistoryEntity
import com.questtick.data.db.SignRunEntity
import com.questtick.data.db.SignRunTaskEntity
import com.questtick.data.db.SignTaskAttemptEntity
import com.questtick.data.db.PostRunActionEntity
import com.questtick.data.db.calendarTaskStatesFromRecord
import com.questtick.data.db.mergeCalendarTaskState
import com.questtick.data.db.CALENDAR_TASK_STATUS_FAILED
import com.questtick.data.db.CALENDAR_TASK_STATUS_SIGNED
import com.questtick.sign.Mask
import com.questtick.sign.RunTaskProgress
import com.questtick.sign.RunTaskStatus
import com.questtick.sign.taskStatusFromResult
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 签到运行的唯一持久化入口。
 *
 * 运行会话和任务终态会增量落盘，最终历史、任务级日历与本次日志在同一个 Room 事务中提交。
 * 进程重启时可据此区分“从未开始”和“请求后结果不确定”的任务，避免盲目重放签到 POST。
 */
@Singleton
class RunPersistenceRepository
    @Inject
    constructor(
        private val db: AppDatabase,
    ) {
        private val lifecycleLock = Any()
        private val activeRunIds = ConcurrentHashMap.newKeySet<String>()
        private val runDao = db.runDao()
        private val historyDao = db.historyDao()
        private val calendarDao = db.calendarDao()
        private val logDao = db.logDao()
        private val postRunActionDao = db.postRunActionDao()

        fun startRun(
            runId: String,
            trigger: RunTrigger,
            startedAt: Long,
            tasks: List<RunTaskProgress>,
        ) = synchronized(lifecycleLock) {
            require(runId.isNotBlank()) { "runId must not be blank" }
            require(tasks.map { it.id }.distinct().size == tasks.size) { "Task plan contains duplicate task IDs" }
            db.runInTransaction {
                runDao.insertRun(
                    SignRunEntity(
                        runId = runId,
                        trigger = trigger.name,
                        status = RUN_STATUS_RUNNING,
                        terminationReason = "",
                        startedAt = startedAt,
                        finishedAt = 0L,
                        totalTasks = tasks.size,
                        completedTasks = 0,
                        recordJson = "",
                        updatedAt = startedAt,
                    ),
                )
                if (tasks.isNotEmpty()) {
                    val taskEntities =
                        tasks.map { task ->
                            val parts = task.id.split('|', limit = 3)
                            SignRunTaskEntity(
                                runId = runId,
                                taskId = task.id,
                                accountId = parts.getOrNull(0).orEmpty(),
                                accountLabel = task.accountLabel,
                                taskType = task.type.name,
                                gameKey = parts.getOrNull(2).orEmpty(),
                                targetName = task.targetName,
                                status = TASK_STATUS_PENDING,
                                message = "",
                                resultJson = "",
                                startedAt = 0L,
                                finishedAt = 0L,
                                updatedAt = startedAt,
                            )
                        }
                    runDao.insertTasks(taskEntities)
                    runDao.insertTaskAttempts(
                        taskEntities.map { task ->
                            SignTaskAttemptEntity(
                                runId = task.runId,
                                taskId = task.taskId,
                                accountId = task.accountId,
                                taskType = task.taskType,
                                gameKey = task.gameKey,
                                status = ATTEMPT_STATUS_PENDING,
                                failureCategory = "",
                                errorCode = "",
                                retryable = false,
                                startedAt = 0L,
                                finishedAt = 0L,
                                updatedAt = startedAt,
                            )
                        },
                    )
                }
            }
            check(activeRunIds.add(runId)) { "Run is already active in this process: $runId" }
        }

        fun uncertainTaskIdsForDay(timestamp: Long): Set<String> =
            calendarDao.getTaskIdsByStatus(dayKey(timestamp), CALENDAR_TASK_STATUS_RESULT_UNKNOWN).toSet()

        fun isTaskRunning(
            runId: String,
            taskId: String,
        ): Boolean = runDao.getTask(runId, taskId)?.status == TASK_STATUS_RUNNING

        /** 只有 PENDING 能进入 RUNNING；返回 false 表示任务已被恢复或已有终态，不得再发请求。 */
        fun markTaskRunning(
            runId: String,
            taskId: String,
            message: String,
            now: Long = System.currentTimeMillis(),
        ): Boolean =
            db.runInTransaction<Boolean> {
                val changed =
                    runDao.transitionTaskToRunning(
                        runId = runId,
                        taskId = taskId,
                        expectedStatus = TASK_STATUS_PENDING,
                        newStatus = TASK_STATUS_RUNNING,
                        message = Mask.sensitive(message),
                        startedAt = now,
                    ) == 1
                if (changed) {
                    check(
                        runDao.transitionAttemptToRunning(
                            runId = runId,
                            taskId = taskId,
                            expectedStatus = ATTEMPT_STATUS_PENDING,
                            newStatus = ATTEMPT_STATUS_RUNNING,
                            startedAt = now,
                        ) == 1,
                    ) { "Unable to start task attempt $taskId" }
                }
                changed
            }

        /** 将首个终态增量落盘；数据库条件更新与内存收集器共同防止重复终态。 */
        fun persistTaskResult(
            runId: String,
            taskId: String,
            result: TaskResult,
            now: Long = System.currentTimeMillis(),
        ): Boolean =
            db.runInTransaction<Boolean> {
                val normalized = result.copy(taskId = taskId)
                val changed =
                    runDao.transitionTaskToTerminal(
                        runId = runId,
                        taskId = taskId,
                        expectedStatuses = listOf(TASK_STATUS_PENDING, TASK_STATUS_RUNNING),
                        newStatus = persistedTaskStatus(normalized),
                        message = Mask.sensitive(normalized.message),
                        resultJson = normalized.toJson().toString(),
                        finishedAt = now,
                    ) == 1
                if (changed) {
                    check(
                        runDao.transitionAttemptToTerminal(
                            runId = runId,
                            taskId = taskId,
                            expectedStatuses = listOf(ATTEMPT_STATUS_PENDING, ATTEMPT_STATUS_RUNNING),
                            newStatus = persistedTaskStatus(normalized),
                            failureCategory = normalized.failureCategory.name,
                            errorCode = Mask.sensitive(normalized.errorCode),
                            retryable = normalized.retryable,
                            finishedAt = now,
                        ) == 1,
                    ) { "Unable to finish task attempt $taskId" }
                    runDao.updateCompletedTaskCount(
                        runId,
                        runDao.countTerminalTasks(runId, TERMINAL_TASK_STATUSES),
                        now,
                    )
                }
                changed
            }

        /** 历史、会话终态、任务结果、任务级日历和运行日志必须一起成功或一起回滚。 */
        fun finishRun(
            record: RunRecord,
            logs: List<LogEntry>,
            postRunActions: List<PostRunActionRequest> = emptyList(),
            maxHistory: Int = DEFAULT_MAX_HISTORY,
            maxLogs: Int = DEFAULT_MAX_LOGS,
        ) = synchronized(lifecycleLock) {
            require(record.runId.isNotBlank()) { "Finished run must have a runId" }
            db.runInTransaction {
                val run = requireNotNull(runDao.getRun(record.runId)) { "Run session not found: ${record.runId}" }
                check(run.status == RUN_STATUS_RUNNING) { "Run is not active: ${record.runId} (${run.status})" }
                val plannedTaskIds = runDao.getTasks(record.runId).map { it.taskId }.toSet()
                val resultTaskIds = record.results.map { it.taskId }.toSet()
                check(record.results.all { it.taskId.isNotBlank() }) { "Finished task result has blank taskId" }
                check(resultTaskIds.size == record.results.size) { "Finished run contains duplicate task IDs" }
                check(plannedTaskIds == resultTaskIds) {
                    "Finished task set differs from plan: planned=${plannedTaskIds.size}, actual=${resultTaskIds.size}"
                }

                record.results.forEach { result ->
                    val existing = requireNotNull(runDao.getTask(record.runId, result.taskId))
                    if (existing.status in TERMINAL_TASK_STATUSES) {
                        check(existing.resultJson.isNotBlank()) { "Terminal task has no result snapshot: ${result.taskId}" }
                        val persisted = TaskResult.fromJson(JSONObject(existing.resultJson))
                        check(persisted == result) { "Terminal task result changed after first completion: ${result.taskId}" }
                    } else {
                        val changed =
                            runDao.transitionTaskToTerminal(
                                runId = record.runId,
                                taskId = result.taskId,
                                expectedStatuses = listOf(TASK_STATUS_PENDING, TASK_STATUS_RUNNING),
                                newStatus = persistedTaskStatus(result),
                                message = Mask.sensitive(result.message),
                                resultJson = result.toJson().toString(),
                                finishedAt = record.timestamp,
                            )
                        check(changed == 1) { "Unable to persist terminal task ${result.taskId}" }
                        check(
                            runDao.transitionAttemptToTerminal(
                                runId = record.runId,
                                taskId = result.taskId,
                                expectedStatuses = listOf(ATTEMPT_STATUS_PENDING, ATTEMPT_STATUS_RUNNING),
                                newStatus = persistedTaskStatus(result),
                                failureCategory = result.failureCategory.name,
                                errorCode = Mask.sensitive(result.errorCode),
                                retryable = result.retryable,
                                finishedAt = record.timestamp,
                            ) == 1,
                        ) { "Unable to persist terminal task attempt ${result.taskId}" }
                    }
                }

                check(historyDao.insert(SignHistoryEntity.fromModel(record)) != -1L) {
                    "History already exists for run ${record.runId}"
                }
                calendarTaskStatesFromRecord(record.copy(timestamp = run.startedAt)).forEach(::upsertMergedCalendarState)
                val distinctActions = postRunActions.distinctBy { it.type }
                if (distinctActions.isNotEmpty()) {
                    postRunActionDao.insertAll(
                        distinctActions.map { action ->
                            PostRunActionEntity(
                                actionId = "${record.runId}:${action.type.name}",
                                runId = record.runId,
                                type = action.type.name,
                                status = POST_ACTION_STATUS_PENDING,
                                payloadJson = record.toJson().toString(),
                                attemptCount = 0,
                                nextAttemptAt = record.timestamp,
                                leaseUntil = 0L,
                                lastErrorCategory = "",
                                lastErrorCode = "",
                                createdAt = record.timestamp,
                                updatedAt = record.timestamp,
                                deliveredAt = 0L,
                            )
                        },
                    )
                }
                appendLogsLocked(logs, maxLogs)
                trimHistoryLocked(record.timestamp, maxHistory)
                trimCalendarLocked(record.timestamp)
                postRunActionDao.deleteTerminalOlderThan(
                    listOf(
                        POST_ACTION_STATUS_DELIVERED,
                        POST_ACTION_STATUS_FAILED,
                        POST_ACTION_STATUS_CANCELLED,
                        POST_ACTION_STATUS_RESULT_UNKNOWN,
                    ),
                    record.timestamp - POST_ACTION_RETENTION_MILLIS,
                )

                val completed = runDao.countTerminalTasks(record.runId, TERMINAL_TASK_STATUSES)
                check(completed == run.totalTasks) { "Run still has unfinished tasks: $completed/${run.totalTasks}" }
                check(
                    runDao.updateRun(
                        run.copy(
                            status = RUN_STATUS_COMPLETED,
                            terminationReason = record.terminationReason.name,
                            finishedAt = record.timestamp,
                            completedTasks = completed,
                            recordJson = record.toJson().toString(),
                            updatedAt = record.timestamp,
                        ),
                    ) == 1,
                ) { "Unable to finalize run ${record.runId}" }
                trimRunsLocked()
            }
            activeRunIds.remove(record.runId)
        }

        /**
         * 协程取消不写普通失败历史。已经发起过的任务标记为结果不确定，并阻止同日自动重放；
         * 尚未开始的任务标记为已取消，可由用户稍后重新运行。
         */
        fun cancelRun(
            runId: String,
            reason: RunTerminationReason = RunTerminationReason.SYSTEM_CANCELLED,
            logs: List<LogEntry> = emptyList(),
            now: Long = System.currentTimeMillis(),
        ) = synchronized(lifecycleLock) {
            db.runInTransaction {
                val run = runDao.getRun(runId) ?: return@runInTransaction
                if (run.status != RUN_STATUS_RUNNING) return@runInTransaction
                runDao.transitionUnfinishedTasks(
                    runId,
                    listOf(TASK_STATUS_RUNNING),
                    TASK_STATUS_RESULT_UNKNOWN,
                    RESULT_UNKNOWN_MESSAGE,
                    now,
                )
                runDao.transitionUnfinishedTasks(
                    runId,
                    listOf(TASK_STATUS_PENDING),
                    RunTaskStatus.CANCELLED.name,
                    "任务取消前尚未开始",
                    now,
                )
                runDao.transitionUnfinishedAttempts(
                    runId = runId,
                    expectedStatuses = listOf(ATTEMPT_STATUS_RUNNING),
                    newStatus = TASK_STATUS_RESULT_UNKNOWN,
                    failureCategory = FailureCategory.RESULT_UNKNOWN.name,
                    errorCode = "cancelled-after-request",
                    retryable = false,
                    finishedAt = now,
                )
                runDao.transitionUnfinishedAttempts(
                    runId = runId,
                    expectedStatuses = listOf(ATTEMPT_STATUS_PENDING),
                    newStatus = RunTaskStatus.CANCELLED.name,
                    failureCategory = FailureCategory.CANCELLED.name,
                    errorCode = "cancelled-before-start",
                    retryable = true,
                    finishedAt = now,
                )
                persistConfirmedCalendarStatesLocked(run, now)
                persistUnknownCalendarStatesLocked(run, now)
                appendLogsLocked(logs, DEFAULT_MAX_LOGS)
                trimCalendarLocked(now)
                val completed = runDao.countTerminalTasks(runId, TERMINAL_TASK_STATUSES)
                runDao.updateRun(
                    run.copy(
                        status = RUN_STATUS_CANCELLED,
                        terminationReason = reason.name,
                        finishedAt = now,
                        completedTasks = completed,
                        updatedAt = now,
                    ),
                )
                trimRunsLocked()
            }
            activeRunIds.remove(runId)
        }

        /** 当前运行发生非取消异常时，以 INTERNAL_ERROR 安全收尾，不伪装成进程死亡。 */
        fun failRun(
            runId: String,
            logs: List<LogEntry> = emptyList(),
            now: Long = System.currentTimeMillis(),
        ): RunRecoverySummary =
            finalizeAbandonedRuns(
                now = now,
                onlyRunId = runId,
                additionalLogs = logs,
                terminationReason = RunTerminationReason.INTERNAL_ERROR,
            )

        /** 把上次进程遗留的 RUNNING 会话恢复为 PROCESS_INTERRUPTED，且操作幂等。 */
        fun recoverInterruptedRuns(now: Long = System.currentTimeMillis()): RunRecoverySummary =
            finalizeAbandonedRuns(
                now = now,
                onlyRunId = null,
                additionalLogs = emptyList(),
                terminationReason = RunTerminationReason.PROCESS_INTERRUPTED,
            )

        private fun finalizeAbandonedRuns(
            now: Long,
            onlyRunId: String?,
            additionalLogs: List<LogEntry>,
            terminationReason: RunTerminationReason,
        ): RunRecoverySummary = synchronized(lifecycleLock) {
            var recoveredRuns = 0
            var uncertainTasks = 0
            var notStartedTasks = 0
            val finalizedRunIds = mutableListOf<String>()
            db.runInTransaction {
                runDao.getRunsByStatus(RUN_STATUS_RUNNING)
                    .filter { run ->
                        if (onlyRunId != null) {
                            run.runId == onlyRunId
                        } else {
                            run.runId !in activeRunIds
                        }
                    }
                    .forEach { run ->
                    val before = runDao.getTasks(run.runId)
                    uncertainTasks += before.count { it.status == TASK_STATUS_RUNNING }
                    notStartedTasks += before.count { it.status == TASK_STATUS_PENDING }
                    runDao.transitionUnfinishedTasks(
                        run.runId,
                        listOf(TASK_STATUS_RUNNING),
                        TASK_STATUS_RESULT_UNKNOWN,
                        RESULT_UNKNOWN_MESSAGE,
                        now,
                    )
                    runDao.transitionUnfinishedTasks(
                        run.runId,
                        listOf(TASK_STATUS_PENDING),
                        TASK_STATUS_INTERRUPTED,
                        if (terminationReason == RunTerminationReason.PROCESS_INTERRUPTED) {
                            "进程中断时任务尚未开始"
                        } else {
                            "运行异常时任务尚未开始"
                        },
                        now,
                    )
                    val interruptedPrefix =
                        if (terminationReason == RunTerminationReason.PROCESS_INTERRUPTED) "process-interrupted" else "internal-error"
                    runDao.transitionUnfinishedAttempts(
                        runId = run.runId,
                        expectedStatuses = listOf(ATTEMPT_STATUS_RUNNING),
                        newStatus = TASK_STATUS_RESULT_UNKNOWN,
                        failureCategory = FailureCategory.RESULT_UNKNOWN.name,
                        errorCode = "$interruptedPrefix-result-unknown",
                        retryable = false,
                        finishedAt = now,
                    )
                    runDao.transitionUnfinishedAttempts(
                        runId = run.runId,
                        expectedStatuses = listOf(ATTEMPT_STATUS_PENDING),
                        newStatus = TASK_STATUS_INTERRUPTED,
                        failureCategory =
                            if (terminationReason == RunTerminationReason.PROCESS_INTERRUPTED) {
                                FailureCategory.CANCELLED.name
                            } else {
                                FailureCategory.INTERNAL_ERROR.name
                            },
                        errorCode = "$interruptedPrefix-before-start",
                        retryable = true,
                        finishedAt = now,
                    )
                    val tasks = runDao.getTasks(run.runId)
                    val record = buildAbandonedRecord(run, tasks, now, terminationReason)
                    historyDao.insert(SignHistoryEntity.fromModel(record))
                    calendarTaskStatesFromRecord(record.copy(timestamp = run.startedAt)).forEach(::upsertMergedCalendarState)
                    persistUnknownCalendarStatesLocked(run, now)
                    appendLogsLocked(
                        additionalLogs +
                            LogEntry(
                                timestamp = now,
                                level = LogLevel.WARN.name,
                                message =
                                    if (terminationReason == RunTerminationReason.PROCESS_INTERRUPTED) {
                                        "检测到上次签到进程异常中断，已安全收尾"
                                    } else {
                                        "签到运行发生内部异常，已安全收尾"
                                    },
                                detail = "runId=${run.runId}, uncertain=${tasks.count { it.status == TASK_STATUS_RESULT_UNKNOWN }}",
                            ),
                        DEFAULT_MAX_LOGS,
                    )
                    val completed = runDao.countTerminalTasks(run.runId, TERMINAL_TASK_STATUSES)
                    runDao.updateRun(
                        run.copy(
                            status =
                                if (terminationReason == RunTerminationReason.PROCESS_INTERRUPTED) {
                                    RUN_STATUS_INTERRUPTED
                                } else {
                                    RUN_STATUS_FAILED
                                },
                            terminationReason = terminationReason.name,
                            finishedAt = now,
                            completedTasks = completed,
                            recordJson = record.toJson().toString(),
                            updatedAt = now,
                        ),
                    )
                    recoveredRuns++
                    finalizedRunIds += run.runId
                }
                trimHistoryLocked(now, DEFAULT_MAX_HISTORY)
                trimCalendarLocked(now)
                trimRunsLocked()
            }
            activeRunIds.removeAll(finalizedRunIds.toSet())
            RunRecoverySummary(recoveredRuns, uncertainTasks, notStartedTasks)
        }

        private fun buildAbandonedRecord(
            run: SignRunEntity,
            tasks: List<SignRunTaskEntity>,
            now: Long,
            terminationReason: RunTerminationReason,
        ): RunRecord =
            RunRecord(
                timestamp = now,
                runId = run.runId,
                trigger = RunTrigger.parse(run.trigger),
                terminationReason = terminationReason,
                results = tasks.map { task -> resultForAbandonedTask(task, terminationReason) },
            )

        private fun resultForAbandonedTask(
            task: SignRunTaskEntity,
            terminationReason: RunTerminationReason,
        ): TaskResult {
            if (task.resultJson.isNotBlank()) {
                try {
                    return TaskResult.fromJson(JSONObject(task.resultJson))
                } catch (_: Exception) {
                    // 损坏的结果快照必须显式反馈，不能静默丢弃任务。
                }
            }
            val uncertain = task.status == TASK_STATUS_RESULT_UNKNOWN
            val processInterrupted = terminationReason == RunTerminationReason.PROCESS_INTERRUPTED
            return TaskResult(
                game = task.targetName,
                gameKey = task.gameKey,
                accountLabel = task.accountLabel,
                accountId = task.accountId,
                taskId = task.taskId,
                success = false,
                skipped = true,
                message =
                    when {
                        uncertain -> RESULT_UNKNOWN_MESSAGE
                        processInterrupted -> "进程中断时任务尚未开始"
                        else -> "运行异常时任务尚未开始"
                    },
                failureCategory =
                    if (uncertain) {
                        FailureCategory.RESULT_UNKNOWN
                    } else if (!processInterrupted) {
                        FailureCategory.INTERNAL_ERROR
                    } else {
                        FailureCategory.CANCELLED
                    },
                errorCode =
                    when {
                        uncertain && processInterrupted -> "process-interrupted-result-unknown"
                        uncertain -> "internal-error-result-unknown"
                        processInterrupted -> "process-interrupted-before-start"
                        else -> "internal-error-before-start"
                    },
                retryable = !uncertain,
            )
        }

        private fun persistConfirmedCalendarStatesLocked(
            run: SignRunEntity,
            now: Long,
        ) {
            val businessDay = dayKey(run.startedAt)
            runDao.getTasks(run.runId).forEach { task ->
                val result = task.resultJson.takeIf { it.isNotBlank() }?.let { raw ->
                    try {
                        TaskResult.fromJson(JSONObject(raw))
                    } catch (_: Exception) {
                        null
                    }
                } ?: return@forEach
                if (result.skipped) return@forEach
                upsertMergedCalendarState(
                    CalendarTaskStateEntity(
                        dayKey = businessDay,
                        taskId = task.taskId,
                        accountId = task.accountId,
                        taskType = task.taskType,
                        gameKey = task.gameKey,
                        status = if (result.success) CALENDAR_TASK_STATUS_SIGNED else CALENDAR_TASK_STATUS_FAILED,
                        updatedAt = now,
                        sourceRunId = run.runId,
                    ),
                )
            }
        }

        private fun persistUnknownCalendarStatesLocked(
            run: SignRunEntity,
            now: Long,
        ) {
            val businessDay = dayKey(run.startedAt)
            runDao.getTasks(run.runId)
                .filter { it.status == TASK_STATUS_RESULT_UNKNOWN }
                .forEach { task ->
                    upsertMergedCalendarState(
                        CalendarTaskStateEntity(
                            dayKey = businessDay,
                            taskId = task.taskId,
                            accountId = task.accountId,
                            taskType = task.taskType,
                            gameKey = task.gameKey,
                            status = CALENDAR_TASK_STATUS_RESULT_UNKNOWN,
                            updatedAt = now,
                            sourceRunId = run.runId,
                        ),
                    )
                }
        }

        private fun persistedTaskStatus(result: TaskResult): String =
            if (result.failureCategory == FailureCategory.RESULT_UNKNOWN) {
                TASK_STATUS_RESULT_UNKNOWN
            } else {
                taskStatusFromResult(result).name
            }

        private fun upsertMergedCalendarState(incoming: CalendarTaskStateEntity) {
            val existing = calendarDao.getTaskState(incoming.dayKey, incoming.taskId)
            calendarDao.upsert(mergeCalendarTaskState(existing, incoming))
        }

        private fun appendLogsLocked(
            entries: List<LogEntry>,
            maxKeep: Int,
        ) {
            val cutoff = System.currentTimeMillis() - LOG_RETENTION_MILLIS
            val normalized =
                entries.asSequence()
                    .filter { it.timestamp <= 0L || it.timestamp >= cutoff }
                    .map {
                        LogEntity.fromModel(
                            it.copy(
                                level = LogLevel.normalize(it.level),
                                message = Mask.sensitive(it.message),
                                detail = Mask.sensitive(it.detail),
                            ),
                        )
                    }
                    .toList()
            if (normalized.isNotEmpty()) logDao.insertAll(normalized)
            logDao.deleteOlderThan(cutoff)
            val overflow = logDao.count() - maxKeep.coerceAtLeast(1)
            if (overflow > 0) logDao.deleteOldest(overflow)
        }

        private fun trimHistoryLocked(
            now: Long,
            maxKeep: Int,
        ) {
            historyDao.deleteOlderThan(now - HISTORY_RETENTION_MILLIS)
            val overflow = historyDao.count() - maxKeep.coerceAtLeast(1)
            if (overflow > 0) historyDao.deleteOldest(overflow)
        }

        private fun trimCalendarLocked(now: Long) {
            calendarDao.deleteOlderThan(dayKey(now - CALENDAR_RETENTION_MILLIS))
        }

        private fun trimRunsLocked() {
            val runIds = runDao.getPrunableRunIds(DEFAULT_MAX_RUNS)
            if (runIds.isEmpty()) return
            runDao.deleteAttemptsForRuns(runIds)
            runDao.deleteTasksForRuns(runIds)
            runDao.deleteRuns(runIds)
        }

        companion object {
            internal const val RUN_STATUS_RUNNING = "RUNNING"
            internal const val RUN_STATUS_COMPLETED = "COMPLETED"
            internal const val RUN_STATUS_CANCELLED = "CANCELLED"
            internal const val RUN_STATUS_INTERRUPTED = "INTERRUPTED"
            internal const val RUN_STATUS_FAILED = "FAILED"
            internal const val TASK_STATUS_PENDING = "PENDING"
            internal const val TASK_STATUS_RUNNING = "RUNNING"
            internal const val TASK_STATUS_RESULT_UNKNOWN = "RESULT_UNKNOWN"
            internal const val TASK_STATUS_INTERRUPTED = "INTERRUPTED"
            internal const val RESULT_UNKNOWN_MESSAGE = "任务请求可能已经送达服务器，结果无法确认；为避免重复请求，今日不再自动执行"
            internal const val ATTEMPT_STATUS_PENDING = "PENDING"
            internal const val ATTEMPT_STATUS_RUNNING = "RUNNING"
            internal const val POST_ACTION_STATUS_PENDING = "PENDING"
            internal const val POST_ACTION_STATUS_DELIVERED = "DELIVERED"
            internal const val POST_ACTION_STATUS_FAILED = "FAILED"
            internal const val POST_ACTION_STATUS_CANCELLED = "CANCELLED"
            internal const val POST_ACTION_STATUS_RESULT_UNKNOWN = "RESULT_UNKNOWN"

            private val TERMINAL_TASK_STATUSES =
                listOf(
                    RunTaskStatus.SUCCESS.name,
                    RunTaskStatus.ALREADY_SIGNED.name,
                    RunTaskStatus.FAILED.name,
                    RunTaskStatus.SKIPPED.name,
                    RunTaskStatus.CANCELLED.name,
                    TASK_STATUS_RESULT_UNKNOWN,
                    TASK_STATUS_INTERRUPTED,
                )
            private const val DEFAULT_MAX_HISTORY = 10_000
            private const val DEFAULT_MAX_LOGS = 10_000
            private const val DEFAULT_MAX_RUNS = 90
            private const val LOG_RETENTION_MILLIS = 30L * 24 * 60 * 60 * 1000
            private const val HISTORY_RETENTION_MILLIS = 30L * 24 * 60 * 60 * 1000
            private const val CALENDAR_RETENTION_MILLIS = 370L * 24 * 60 * 60 * 1000
            private const val POST_ACTION_RETENTION_MILLIS = 90L * 24 * 60 * 60 * 1000
        }
    }

data class RunRecoverySummary(
    val recoveredRuns: Int,
    val uncertainTasks: Int,
    val notStartedTasks: Int,
)
