@file:Suppress(
    "detekt:TooManyFunctions",
)

package com.questtick.sign

/*
 * 签到运行进度的协调器：维护任务计划与任务级状态，并把变更同步给首页进度 UI。
 */

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/** 当前持有运行锁的调用方快照，用于诊断「锁被谁占着、占了多久」。 */
data class RunLockHolder(
    val owner: String,
    val acquiredAtMillis: Long,
)

/**
 * 运行锁的持有凭据。
 *
 * 拿到它的运行可以在长任务的关键节点询问「我还持有锁吗」，用于在过期接管后停下，
 * 避免与被接管方重复发出签到请求。
 */
class RunLease internal constructor(
    internal val token: RunLockToken,
)

/** 一次取得运行锁的结果；[staleTakeover] 非空表示顺带接管了一把过期锁。 */
data class RunLockOutcome<T>(
    val value: T,
    val staleTakeover: RunLockHolder? = null,
)

/** 全局签到运行协调器，避免手动签到与后台定时签到同时执行。 */
@Singleton
class SignInRunCoordinator
    @Inject
    constructor() {
        /**
         * 运行锁用 CAS 维护持有者快照，而不是 kotlinx Mutex。
         *
         * Mutex 只能由持有者释放：一旦某次运行停在不响应取消的阻塞调用里（数据库等待、
         * 收尾刷新等），`finally` 永远执行不到，锁会长期处于被占状态，后续定时触发只会
         * 反复写「已有签到任务正在运行」并无限退避。这里允许持锁超过
         * [DEFAULT_STALE_TAKEOVER_MILLIS] 的过期锁被新运行接管；被接管者的释放因 CAS
         * 失败而变成空操作，不会清掉接管方的运行现场。
         */
        private val lockRef = AtomicReference<RunLockToken?>(null)
        private val lockSequence = AtomicLong(0L)

        /** 时间源；生产环境用系统墙钟，测试可注入固定时钟验证过期接管。 */
        private var clock: () -> Long = { System.currentTimeMillis() }
        private val lockStateGuard = Any()
        private val _lockHolder = MutableStateFlow<RunLockHolder?>(null)
        private val _running = MutableStateFlow(false)
        private val _runningStartedAt = MutableStateFlow(0L)
        private val _progress = MutableStateFlow(RunProgressState())

        /** 当前锁持有者；空闲时为 null。 */
        val lockHolder: StateFlow<RunLockHolder?> = _lockHolder.asStateFlow()
        val running: StateFlow<Boolean> = _running.asStateFlow()
        val runningStartedAt: StateFlow<Long> = _runningStartedAt.asStateFlow()
        val progress: StateFlow<RunProgressState> = _progress.asStateFlow()

        fun resetProgress() {
            _progress.value = RunProgressState()
        }

        fun updateProgress(transform: (RunProgressState) -> RunProgressState) {
            // 运行进度是长任务的心跳：只要还在推进，就不应被判成过期锁。
            markActivity()
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
                    running =
                        phase != RunProgressPhase.FINISHED &&
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
                        tasks =
                            current.tasks.map { task ->
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
                        tasks =
                            current.tasks.map { task ->
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
                    tasks =
                        current.tasks.map { task ->
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

        suspend fun <T> tryRun(block: suspend (RunLease) -> T): T? =
            tryRunAs(
                owner = UNSPECIFIED_OWNER,
                staleAfterMillis = DEFAULT_STALE_TAKEOVER_MILLIS,
                block = block,
            )?.value

        /**
         * 取得运行锁执行 [block]，并返回这次是否接管了过期锁；锁被正常占用时返回 null。
         *
         * @param owner 调用方标识，写入锁持有者快照与日志，便于判断谁占住了运行
         * @param staleAfterMillis 持锁超过该时长视为过期锁并可被接管；小于等于 0 表示从不接管
         */
        suspend fun <T> tryRunAs(
            owner: String,
            staleAfterMillis: Long = DEFAULT_STALE_TAKEOVER_MILLIS,
            block: suspend (RunLease) -> T,
        ): RunLockOutcome<T>? {
            val acquired = acquireLock(owner, staleAfterMillis) ?: return null
            publishRunningLock(acquired.token)
            val lease = RunLease(acquired.token)
            return try {
                RunLockOutcome(block(lease), acquired.staleTakeover)
            } catch (e: CancellationException) {
                // 已被接管的旧运行不能改写接管方的运行现场。
                if (isCurrentLock(acquired.token)) {
                    cancelUnfinishedTasks()
                    finishProgress(RunProgressPhase.CANCELLED, "签到任务已取消")
                }
                throw e
            } finally {
                releaseLock(acquired.token)
            }
        }

        private data class AcquiredLock(
            val token: RunLockToken,
            val staleTakeover: RunLockHolder?,
        )

        private fun acquireLock(
            owner: String,
            staleAfterMillis: Long,
        ): AcquiredLock? {
            while (true) {
                val current = lockRef.get()
                val staleTakeover: RunLockHolder? =
                    if (current != null && isStaleLock(current, staleAfterMillis)) current.toHolder() else null
                if (current != null && staleTakeover == null) return null
                val now = clock()
                val candidate =
                    RunLockToken(
                        id = lockSequence.incrementAndGet(),
                        owner = owner.ifBlank { UNSPECIFIED_OWNER },
                        acquiredAtMillis = now,
                        lastActivityMillis = now,
                    )
                if (lockRef.compareAndSet(current, candidate)) {
                    return AcquiredLock(candidate, staleTakeover)
                }
            }
        }

        private fun isCurrentLock(token: RunLockToken): Boolean = lockRef.get()?.id == token.id

        /**
         * 当前调用方是否仍持有运行锁。
         *
         * 过期接管发生后，被接管的一方必须在下一个任务边界自行停下：它的锁已经失效，
         * 继续发请求就会和接管方重复签到。
         */
        fun isLeaseHeld(lease: RunLease): Boolean = isCurrentLock(lease.token)

        private fun isStaleLock(
            current: RunLockToken,
            staleAfterMillis: Long,
        ): Boolean =
            staleAfterMillis > 0L &&
                clock() - current.lastActivity() >= staleAfterMillis

        /** 记录一次运行进展；只有仍持有锁的一方才能刷新心跳。 */
        private fun markActivity() {
            val current = lockRef.get() ?: return
            current.beat(clock())
        }

        private fun publishRunningLock(token: RunLockToken) {
            val startedAt = token.acquiredAtMillis
            synchronized(lockStateGuard) {
                _lockHolder.value = token.toHolder()
                _runningStartedAt.value = startedAt
                _running.value = true
                _progress.value =
                    RunProgressState(
                        running = true,
                        phase = RunProgressPhase.PREPARING,
                        startedAt = startedAt,
                        message = "正在准备签到任务",
                    )
            }
        }

        /** 释放锁并清理运行现场；锁已被接管时什么都不做，避免覆盖接管方的状态。 */
        private fun releaseLock(token: RunLockToken) {
            synchronized(lockStateGuard) {
                if (!lockRef.compareAndSet(token, null)) return
                _lockHolder.value = null
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
            }
        }

        private fun RunLockToken.toHolder(): RunLockHolder = RunLockHolder(owner, acquiredAtMillis)

        internal constructor(clock: () -> Long) : this() {
            this.clock = clock
        }

        companion object {
            const val UNSPECIFIED_OWNER = "未标注"

            /** 正常运行只持续十余秒；持锁超过该时长按过期锁处理，允许后来的运行接管。 */
            const val DEFAULT_STALE_TAKEOVER_MILLIS = 30L * 60 * 1000
        }
    }

/**
 * 运行锁凭据。
 *
 * [lastActivityMillis] 记录最近一次运行进度更新：过期判定看的是「多久没有进展」，而不是
 * 「持锁多久」。否则一次耗时超过接管阈值、但仍在正常发请求的运行会被新运行接管，两边的
 * 签到请求都会真的发出去。
 */
internal class RunLockToken(
    val id: Long,
    val owner: String,
    val acquiredAtMillis: Long,
    lastActivityMillis: Long,
) {
    private val activity = AtomicLong(lastActivityMillis)

    fun beat(now: Long) {
        activity.set(now)
    }

    fun lastActivity(): Long = activity.get()
}
