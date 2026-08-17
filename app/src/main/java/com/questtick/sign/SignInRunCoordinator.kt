package com.questtick.sign

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import javax.inject.Inject
import javax.inject.Singleton

/** 全局签到运行协调器，避免手动签到与后台定时签到同时执行。 */
@Singleton
class SignInRunCoordinator
    @Inject
    constructor() {
        private val mutex = Mutex()
        private val _running = MutableStateFlow(false)
        private val _runningStartedAt = MutableStateFlow(0L)
        private val _progress = MutableStateFlow(RunProgressState())

        val running: StateFlow<Boolean> = _running.asStateFlow()
        val runningStartedAt: StateFlow<Long> = _runningStartedAt.asStateFlow()
        val progress: StateFlow<RunProgressState> = _progress.asStateFlow()

        fun resetProgress() {
            _progress.value = RunProgressState()
        }

        fun updateProgress(transform: (RunProgressState) -> RunProgressState) {
            _progress.update(transform)
        }

        fun setProgressPhase(
            phase: RunProgressPhase,
            message: String = phase.label,
        ) {
            updateProgress { current ->
                current.copy(
                    phase = phase,
                    message = message,
                    running = phase != RunProgressPhase.FINISHED &&
                        phase != RunProgressPhase.BLOCKED &&
                        phase != RunProgressPhase.CANCELLED &&
                        phase != RunProgressPhase.FAILED,
                )
            }
        }

        fun setTaskPlan(tasks: List<RunTaskProgress>) {
            require(tasks.map { it.id }.distinct().size == tasks.size) { "Task plan contains duplicate task IDs" }
            updateProgress { current ->
                current.copy(
                    phase = RunProgressPhase.CHECKING,
                    message = if (tasks.isEmpty()) "没有可执行任务" else "已生成 ${tasks.size} 个签到任务",
                    tasks = tasks,
                    currentTaskId = "",
                )
            }
        }

        fun markTaskRunning(
            taskId: String,
            message: String = "正在执行",
        ) {
            val now = System.currentTimeMillis()
            updateProgress { current ->
                val canStart = current.tasks.any { it.id == taskId && !it.status.isTerminal }
                if (!canStart) {
                    current
                } else {
                    current.copy(
                        phase = RunProgressPhase.SIGNING,
                        message = message,
                        currentTaskId = taskId,
                        tasks = current.tasks.map { task ->
                            if (task.id == taskId && !task.status.isTerminal) {
                                task.copy(
                                    status = RunTaskStatus.RUNNING,
                                    message = message,
                                    startedAt = task.startedAt.takeIf { it > 0L } ?: now,
                                )
                            } else {
                                task
                            }
                        },
                    )
                }
            }
        }

        fun markTaskFinished(
            taskId: String,
            status: RunTaskStatus,
            message: String,
        ) {
            require(status.isTerminal) { "Finished task must use a terminal status" }
            val now = System.currentTimeMillis()
            updateProgress { current ->
                val canFinish = current.tasks.any { it.id == taskId && !it.status.isTerminal }
                if (!canFinish) {
                    current
                } else {
                    current.copy(
                        message = message,
                        currentTaskId = current.currentTaskId.takeUnless { it == taskId }.orEmpty(),
                        tasks = current.tasks.map { task ->
                            if (task.id == taskId && !task.status.isTerminal) {
                                task.copy(status = status, message = message, finishedAt = now)
                            } else {
                                task
                            }
                        },
                    )
                }
            }
        }

        fun skipUnfinishedTasks(message: String) {
            finishUnfinishedTasks(RunTaskStatus.SKIPPED, message)
        }

        fun cancelUnfinishedTasks(message: String = "签到任务已取消") {
            finishUnfinishedTasks(RunTaskStatus.CANCELLED, message)
        }

        private fun finishUnfinishedTasks(
            status: RunTaskStatus,
            message: String,
        ) {
            require(status.isTerminal) { "Unfinished tasks must be moved to a terminal status" }
            val now = System.currentTimeMillis()
            updateProgress { current ->
                current.copy(
                    message = message,
                    currentTaskId = "",
                    tasks = current.tasks.map { task ->
                        if (task.status.isTerminal) {
                            task
                        } else {
                            task.copy(status = status, message = message, finishedAt = now)
                        }
                    },
                )
            }
        }

        fun finishProgress(
            phase: RunProgressPhase = RunProgressPhase.FINISHED,
            message: String = phase.label,
        ) {
            updateProgress { current ->
                current.copy(
                    running = false,
                    phase = phase,
                    message = message,
                    currentTaskId = "",
                    finishedAt = System.currentTimeMillis(),
                )
            }
        }

        suspend fun <T> tryRun(block: suspend () -> T): T? {
            if (!mutex.tryLock()) return null
            val startedAt = System.currentTimeMillis()
            _runningStartedAt.value = startedAt
            _running.value = true
            _progress.value = RunProgressState(
                running = true,
                phase = RunProgressPhase.PREPARING,
                startedAt = startedAt,
                message = "正在准备签到任务",
            )
            return try {
                block()
            } catch (e: CancellationException) {
                cancelUnfinishedTasks()
                finishProgress(RunProgressPhase.CANCELLED, "签到任务已取消")
                throw e
            } finally {
                _running.value = false
                _runningStartedAt.value = 0L
                _progress.update { current ->
                    if (current.phase == RunProgressPhase.IDLE) {
                        current
                    } else {
                        current.copy(
                            running = false,
                            finishedAt = current.finishedAt.takeIf { it > 0L } ?: System.currentTimeMillis(),
                        )
                    }
                }
                mutex.unlock()
            }
        }
    }
