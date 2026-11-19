@file:Suppress(
    "detekt:CyclomaticComplexMethod",
    "detekt:LongMethod",
    "detekt:LongParameterList",
    "detekt:TooGenericExceptionCaught",
    "detekt:TooManyFunctions",
)

package com.questtick.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.questtick.core.throwIfCancellation
import com.questtick.R
import com.questtick.data.AppSettings
import com.questtick.data.FailureCategory
import com.questtick.data.LogEntry
import com.questtick.data.RunRecord
import com.questtick.data.dayKey
import com.questtick.data.SecureStore
import com.questtick.log.LogMarkers
import com.questtick.repository.account.AccountRepository
import com.questtick.repository.history.HistoryRepository
import com.questtick.repository.log.LogRepository
import com.questtick.repository.log.AppErrorLogger
import com.questtick.repository.run.ExecutionStateRepository
import com.questtick.repository.settings.SettingsRepository
import com.questtick.sign.MysCoinCheckIn
import com.questtick.sign.RiskState
import com.questtick.sign.RunLockHolder
import com.questtick.sign.SignInRunCoordinator
import com.questtick.data.RunTerminationReason
import com.questtick.data.RunTrigger
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * 后台签到 Worker。
 *
 * 执行时尽量提升为前台服务，然后调用 SignInRunner；运行后通知与邮件由持久化 Outbox 独立投递。
 * CancellationException 继续向外抛出，让 WorkManager 正常处理取消。
 */
@HiltWorker
class SignInWorker
    @AssistedInject
    constructor(
        @Assisted appContext: Context,
        @Assisted params: WorkerParameters,
        private val store: SecureStore,
        private val accountRepository: AccountRepository,
        private val historyRepository: HistoryRepository,
        private val logRepository: LogRepository,
        private val settingsRepository: SettingsRepository,
        private val executionStateRepository: ExecutionStateRepository,
        private val runnerFactory: SignInRunnerFactory,
        private val runCoordinator: SignInRunCoordinator,
        private val scheduler: Scheduler,
        private val appErrorLogger: AppErrorLogger,
    ) : CoroutineWorker(appContext, params) {
        override suspend fun doWork(): Result =
            try {
                val settings = store.getAppSettings()
                val input = scheduleInputContext()
                if (settings.scheduleEnabled && !input.isExpired(settings, scheduler)) {
                    val firedTooEarly = logIfFiredTooEarly(input)
                    // 下一日调度必须在取运行锁之前补上：锁被占用时若把它留在锁内，
                    // 一次拿不到锁就会连次日任务一起丢掉，整条定时链从此断掉。
                    ensureFollowingRunScheduled(input, anchorFromNow = firedTooEarly)
                }
                runScheduledSignIn()
            } catch (e: CancellationException) {
                // 系统主动取消时向上抛出，让 WorkManager 处理。
                throw e
            } catch (e: Exception) {
                appErrorLogger.record("定时签到", e, "定时签到执行异常，已停止本业务日自动重放")
                // 未形成结构化运行结果时不盲目重放 POST；结束本周期但保留下一业务日调度。
                Result.success()
            }

        /**
         * 触发时间明显早于计划时刻时记录告警。
         *
         * WorkManager 的 initialDelay 按真实经过时间倒计时，而计划时刻可能来自可信时钟（网络时间）；
         * 设备时钟或时区被调整后任务会在计划时刻之前被放行（日志里出现过 05:00 的任务在 02:10 触发）。
         * 本次照常执行、不跳过：跳过会让时钟偏移的当天直接漏签。偏差只用来在补次日调度时改按
         * 当前时间重算一次（见 ensureFollowingRunScheduled），避免偏移沿链一路传下去。
         */
        private suspend fun logIfFiredTooEarly(input: ScheduleInputContext): Boolean {
            val now = System.currentTimeMillis()
            if (!firedTooEarly(now, input.targetAt, EARLY_FIRE_TOLERANCE_MILLIS)) return false
            logRepository.persistAppended(
                listOf(
                    LogEntry(
                        timestamp = now,
                        level = "WARN",
                        message = "定时签到触发时间早于计划",
                        detail =
                            "now=${formatScheduleTime(now)}，target=${formatScheduleTime(input.targetAt)}，" +
                                "generation=${input.generation}；设备时钟或调度基准存在偏移，本次仍按计划业务日执行。",
                    ),
                ),
            )
            return true
        }

        /**
         * 在取运行锁之前补上下一次调度；重复调用（含退避重试）不会重复入队。
         *
         * [anchorFromNow] 用于触发时刻明显早于计划的情况：说明设备时钟被调整过，
         * 继续沿旧绝对时间戳加一天会让触发时刻一直偏，改为按当前时间重算下一次。
         */
        private suspend fun ensureFollowingRunScheduled(
            input: ScheduleInputContext,
            anchorFromNow: Boolean,
        ) {
            runCatching {
                // 下一日调度是链式推进的唯一来源，必须抗取消：取消发生在入队完成之后才安全。
                withContext(NonCancellable) {
                    scheduler.scheduleFollowingRun(
                        hour = input.hour,
                        minute = input.minute,
                        generation = input.generation,
                        scheduledTargetAt = input.targetAt,
                        anchorFromNow = anchorFromNow,
                    )
                }
            }.onFailure { error -> appErrorLogger.record("下一次定时签到调度", error) }
        }

        private suspend fun runScheduledSignIn(): Result {
            val outcome =
                runCoordinator.tryRunAs(
                    owner = RUN_LOCK_OWNER_SCHEDULED,
                    staleAfterMillis = SignInRunCoordinator.DEFAULT_STALE_TAKEOVER_MILLIS,
                ) { lease ->
                    logScheduledRunStarted()
                    // 尝试提升为前台服务；被 ROM 拒绝时降级为普通后台执行。
                    // 准点闹钟拉起的加急工作已由 WorkManager 自己维护前台服务，重复提升没有意义。
                    if (!inputData.getBoolean(Scheduler.INPUT_EXPEDITED_KICK, false)) {
                        try {
                            setForeground(createForegroundInfo())
                        } catch (e: CancellationException) {
                            throw e
                        } catch (error: Exception) {
                            appErrorLogger.record("定时签到前台服务", error)
                            // ROM 拒绝前台切换时降级，但不能吞掉 WorkManager 取消信号。
                        }
                    }
                    executeSignIn(isLeaseCurrent = { runCoordinator.isLeaseHeld(lease) })
                }
            if (outcome == null) return retryBecauseRunLockBusy()
            outcome.staleTakeover?.let { logStaleLockTakeover(it) }
            return outcome.value
        }

        /**
         * 取锁成功后立刻落盘一条运行痕迹。
         *
         * 运行日志只在收尾时批量写入；一旦运行在收尾前卡住或被杀，日志里连「开始」都不会留下，
         * 也就无法判断锁被谁占住、运行卡在哪一步。
         */
        private suspend fun logScheduledRunStarted() {
            val input = scheduleInputContext()
            logRepository.persistAppended(
                listOf(
                    LogEntry(
                        timestamp = System.currentTimeMillis(),
                        level = "INFO",
                        message = LogMarkers.SCHEDULED_SUBMITTED,
                        detail =
                            "第 ${runAttemptCount + 1} 次尝试，businessDay=${dayKey(input.targetAt)}，" +
                                "target=${formatScheduleTime(input.targetAt)}，generation=${input.generation}，" +
                                "触发偏差=${formatDelay(System.currentTimeMillis() - input.targetAt)}",
                    ),
                ),
            )
        }

        private suspend fun logStaleLockTakeover(previous: RunLockHolder) {
            val heldSeconds = (System.currentTimeMillis() - previous.acquiredAtMillis) / MILLIS_PER_SECOND
            logRepository.persistAppended(
                listOf(
                    LogEntry(
                        timestamp = System.currentTimeMillis(),
                        level = "WARN",
                        message = "已接管过期的签到运行锁",
                        detail = "原持锁者=${previous.owner}，已持锁 ${heldSeconds}s 未释放，按过期处理并继续签到。",
                    ),
                ),
            )
        }

        private fun scheduleInputContext(): ScheduleInputContext =
            ScheduleInputContext(
                hour = inputData.getInt(Scheduler.INPUT_HOUR, -1),
                minute = inputData.getInt(Scheduler.INPUT_MINUTE, -1),
                timeZoneId = inputData.getString(Scheduler.INPUT_TIME_ZONE).orEmpty(),
                generation = inputData.getLong(Scheduler.INPUT_GENERATION, -1L),
                targetAt = inputData.getLong(Scheduler.INPUT_TARGET_AT, System.currentTimeMillis()),
            )

        private suspend fun retryBecauseRunLockBusy(): Result {
            val input = scheduleInputContext()
            val holder = runCoordinator.lockHolder.value
            val diagnostic =
                buildString {
                    append("第 ${runAttemptCount + 1} 次尝试，businessDay=${dayKey(input.targetAt)}")
                    append("，target=${formatScheduleTime(input.targetAt)}")
                    append("，generation=${input.generation}")
                    if (holder == null) {
                        append("，持锁者=未知")
                    } else {
                        append("，持锁者=${holder.owner}")
                        append("，已持锁 ${(System.currentTimeMillis() - holder.acquiredAtMillis) / MILLIS_PER_SECOND}s")
                    }
                }
            if (shouldGiveUpWhenRunLockBusy(runAttemptCount, MAX_LOCK_BUSY_ATTEMPTS)) {
                logRepository.persistAppended(
                    listOf(
                        LogEntry(
                            timestamp = System.currentTimeMillis(),
                            level = "ERROR",
                            message = "已放弃本业务日的定时签到重试：运行锁长期被占用",
                            detail = "$diagnostic；下一业务日调度已独立保留，可手动签到或等待下次定时。",
                        ),
                    ),
                )
                return Result.success()
            }
            val entry =
                LogEntry(
                    timestamp = System.currentTimeMillis(),
                    level = "WARN",
                    message = "定时签到暂未开始：已有签到任务正在运行",
                    detail = "WorkManager 将按退避策略重试，避免本次定时签到被静默跳过。$diagnostic",
                )
            logRepository.persistAppended(listOf(entry))
            return Result.retry()
        }

        private suspend fun logDuplicateScheduleSkip(businessDayKey: String) {
            logRepository.persistAppended(
                listOf(
                    LogEntry(
                        timestamp = System.currentTimeMillis(),
                        level = "INFO",
                        message = "已跳过重复的定时签到",
                        detail = "businessDay=$businessDayKey，当前业务日已有执行槽位。",
                    ),
                ),
            )
        }

        private suspend fun logScheduleDisabledSkip() {
            val entry =
                LogEntry(
                    timestamp = System.currentTimeMillis(),
                    level = "INFO",
                    message = "已跳过定时签到：定时任务已关闭",
                    detail = "Worker 启动时重新确认到定时任务开关已关闭，避免已取消任务因系统调度竞态继续执行。",
                )
            logRepository.persistAppended(listOf(entry))
        }

        private suspend fun executeSignIn(isLeaseCurrent: () -> Boolean): Result {
            val settings = store.getAppSettings()
            if (!settings.scheduleEnabled) {
                logScheduleDisabledSkip()
                syncRepositoryAfterBackgroundRun()
                return Result.success()
            }

            val input = scheduleInputContext()
            val scheduledTargetAt = input.targetAt
            val scheduleExpired = input.isExpired(settings, scheduler)
            val startedAt = System.currentTimeMillis()
            // 延迟启动与跨午夜退避始终归属原计划业务日，确保只重试上一轮明确可重试的失败任务。
            val businessDayKey = dayKey(scheduledTargetAt)
            val workId = id.toString()
            val slotClaim =
                if (scheduleExpired) {
                    null
                } else {
                    executionStateRepository.claimScheduledSlot(workId, runAttemptCount, businessDayKey, startedAt)
                }
            // 旧代任务可能因取消竞态被系统放行，过期计划不执行；只有 CLAIMED 才继续签到，
            // DUPLICATE / NOT_BEFORE 必须立刻返回，避免同一业务日重复投递。
            return when {
                scheduleExpired -> {
                    logExpiredScheduleSkip(input, businessDayKey)
                    Result.success()
                }

                slotClaim == ExecutionStateRepository.SlotClaim.DUPLICATE -> {
                    logDuplicateScheduleSkip(businessDayKey)
                    Result.success()
                }

                slotClaim == ExecutionStateRepository.SlotClaim.NOT_BEFORE -> {
                    logSlotWaitingRetryWindow(businessDayKey)
                    Result.retry()
                }

                else -> {
                    executeClaimedSignIn(
                        settings = settings,
                        scheduledTargetAt = scheduledTargetAt,
                        workId = workId,
                        businessDayKey = businessDayKey,
                        isLeaseCurrent = isLeaseCurrent,
                    )
                }
            }
        }

        /** 过期计划不再执行，但必须留下日志，否则当天会显得「什么都没发生」。 */
        private suspend fun logExpiredScheduleSkip(
            input: ScheduleInputContext,
            businessDayKey: String,
        ) {
            logRepository.persistAppended(
                listOf(
                    LogEntry(
                        timestamp = System.currentTimeMillis(),
                        level = "INFO",
                        message = "已跳过过期的定时签到",
                        detail =
                            "businessDay=$businessDayKey，target=${formatScheduleTime(input.targetAt)}，" +
                                "generation=${input.generation}；计划已变更或代次失效，本次不再执行。",
                    ),
                ),
            )
        }

        /** 同一任务的失败重试窗口未到：WorkManager 会再次退避，这里必须记录以免看起来像卡住。 */
        private suspend fun logSlotWaitingRetryWindow(businessDayKey: String) {
            logRepository.persistAppended(
                listOf(
                    LogEntry(
                        timestamp = System.currentTimeMillis(),
                        level = "INFO",
                        message = "定时签到等待可重试时间",
                        detail = "businessDay=$businessDayKey；本轮失败重试窗口尚未到达，WorkManager 将继续退避重试。",
                    ),
                ),
            )
        }

        private suspend fun executeClaimedSignIn(
            settings: AppSettings,
            scheduledTargetAt: Long,
            workId: String,
            businessDayKey: String,
            isLeaseCurrent: () -> Boolean,
        ): Result {
            var record: RunRecord? = null
            return try {
                val allowedTaskIds =
                    executionStateRepository.retryableTaskIdsForSlot(
                        workId = workId,
                        businessDayKey = businessDayKey,
                    )
                if (allowedTaskIds != null && allowedTaskIds.isEmpty()) {
                    executionStateRepository.completeScheduledSlot(workId, businessDayKey, null)
                    return Result.success()
                }
                // 任务失败不再冻结账号；清理旧版本遗留的账号保护记录后继续执行全部启用账号。
                executionStateRepository.clearAccountGuards()
                val actualRunCount =
                    executionStateRepository.markScheduledRunStarted(workId, businessDayKey)
                val trigger = if (actualRunCount > 1) RunTrigger.RETRY else RunTrigger.SCHEDULED
                record =
                    runnerFactory.create(settings.parallelEnabled).runAll(
                        trigger = trigger,
                        allowedTaskIds = allowedTaskIds,
                        scheduledBusinessDayAt = scheduledTargetAt,
                        isLeaseCurrent = isLeaseCurrent,
                    )

                if (record.total == 0) {
                    executionStateRepository.completeScheduledSlot(workId, businessDayKey, record)
                    syncRepositoryAfterBackgroundRun()
                    Result.success()
                } else {
                    finishScheduledSignIn(record, actualRunCount, workId, businessDayKey)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                runCatching {
                    executionStateRepository.failScheduledSlot(
                        workId = workId,
                        businessDayKey = businessDayKey,
                        record = record,
                        category = FailureCategory.INTERNAL_ERROR,
                        retryAfterMillis = null,
                    )
                }
                throw e
            }
        }

        private suspend fun finishScheduledSignIn(
            record: RunRecord,
            actualRunCount: Int,
            workId: String,
            businessDayKey: String,
        ): Result {
            updateFailureGuard(store, record)
            syncRepositoryAfterBackgroundRun()
            val decision = decideSignInWorkerResult(record, actualRunCount - 1, MAX_RETRIES)
            return when {
                decision.retry -> {
                    executionStateRepository.failScheduledSlot(
                        workId = workId,
                        businessDayKey = businessDayKey,
                        record = record,
                        category = decision.category,
                        retryAfterMillis = decision.retryDelayMillis,
                    )
                    Result.retry()
                }

                decision.category == FailureCategory.NONE -> {
                    executionStateRepository.completeScheduledSlot(workId, businessDayKey, record)
                    Result.success()
                }

                else -> {
                    executionStateRepository.failScheduledSlot(
                        workId = workId,
                        businessDayKey = businessDayKey,
                        record = record,
                        category = decision.category,
                        retryAfterMillis = null,
                    )
                    // 周期任务的本业务日已形成确定终态；返回 success 以保留下一周期调度。
                    Result.success()
                }
            }
        }

        /** 连续失败达到阈值时自动关闭定时任务，避免进一步触发风控。 */
        private suspend fun updateFailureGuard(
            store: SecureStore,
            record: RunRecord,
        ) {
            val reason = evaluateFailureGuard(store, record, MAX_CONSECUTIVE_FAILURES)
            if (reason != null) {
                store.markSignInPaused(reason)
                val settings = store.getAppSettings()
                if (settings.scheduleEnabled) {
                    settingsRepository.saveSettings(settings.copy(scheduleEnabled = false))
                }
                val entry =
                    LogEntry(
                        timestamp = System.currentTimeMillis(),
                        level = "WARN",
                        message = "已自动暂停定时签到：$reason",
                        detail = "可在设置页重新开启定时任务；建议先检查 Cookie / Token 或手动完成验证。",
                    )
                logRepository.persistAppended(listOf(entry))
            }
        }

        private suspend fun syncRepositoryAfterBackgroundRun() {
            try {
                accountRepository.reload()
                historyRepository.reload()
                logRepository.reload()
            } catch (error: Exception) {
                error.throwIfCancellation()
                appErrorLogger.record("后台签到结果同步", error)
                // UI 同步失败不影响本次签到结果，下次启动仍会从磁盘读取最新数据。
            }
        }

        /** WorkManager 在加急或前台路径下会回调它；默认实现直接抛异常，这里复用同一套通知。 */
        override suspend fun getForegroundInfo(): ForegroundInfo = createForegroundInfo()

        private fun createForegroundInfo(): ForegroundInfo {
            val nm = applicationContext.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val ch =
                    NotificationChannel(
                        CHANNEL_ID,
                        "签到任务运行中",
                        NotificationManager.IMPORTANCE_LOW,
                    ).apply { description = "定时签到执行期间的前台通知，结束后自动消失" }
                nm.createNotificationChannel(ch)
            }

            val notif =
                NotificationCompat
                    .Builder(applicationContext, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_stat_signin)
                    .setContentTitle("正在执行米游社签到…")
                    .setContentText("完成后将自动发送结果通知")
                    .setOngoing(true)
                    .setSilent(true)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .build()

            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ForegroundInfo(FG_NOTIFY_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                ForegroundInfo(FG_NOTIFY_ID, notif)
            }
        }

        companion object {
            private const val CHANNEL_ID = "signin_running"
            private const val FG_NOTIFY_ID = 1002
            private const val MAX_RETRIES = 3
            private const val MAX_CONSECUTIVE_FAILURES = 5

            /** 锁忙重试上限：达到后结束本业务日，避免无限指数退避；正常运行会先被过期锁接管。 */
            private const val MAX_LOCK_BUSY_ATTEMPTS = 4

            /** 触发时间早于计划超过该时长，按时钟偏移处理并按当前时间重排。 */
            private const val EARLY_FIRE_TOLERANCE_MILLIS = 15L * 60 * 1000

            private const val RUN_LOCK_OWNER_SCHEDULED = "定时签到"
        }
    }

private fun shouldSkipFailureGuard(record: RunRecord): Boolean =
    record.terminationReason == RunTerminationReason.ROOT_DETECTED ||
        record.terminationReason == RunTerminationReason.ROOT_CHECK_FAILED ||
        record.resultUnknown > 0

private fun shouldCountAsFailure(
    record: RunRecord,
    risk: RiskState,
): Boolean = record.total > 0 && (record.failed == record.total || risk != RiskState.NORMAL)

private fun failureGuardPauseReason(
    risk: RiskState,
    failures: Int,
    maxFailures: Int,
): String? =
    if (failures < maxFailures) {
        null
    } else if (risk == RiskState.NORMAL) {
        "连续${failures}次签到全部失败"
    } else {
        "连续${failures}次签到异常，检测到风控状态：$risk"
    }

private fun evaluateFailureGuard(
    store: SecureStore,
    record: RunRecord,
    maxFailures: Int,
): String? =
    if (shouldSkipFailureGuard(record)) {
        null
    } else {
        val risk = RiskState.fromRecord(record)
        if (!shouldCountAsFailure(record, risk)) {
            store.resetSignInFailures()
            null
        } else {
            failureGuardPauseReason(risk, store.recordSignInFailure(), maxFailures)
        }
    }

private const val MILLIS_PER_SECOND = 1_000L
private const val SECONDS_PER_MINUTE = 60L

/** 触发时刻相对计划时刻的偏差；正数表示晚于计划时刻。 */
private fun formatDelay(deltaMillis: Long): String {
    val totalSeconds = abs(deltaMillis) / MILLIS_PER_SECOND
    val sign = if (deltaMillis >= 0L) "+" else "-"
    val minutes = totalSeconds / SECONDS_PER_MINUTE
    val seconds = totalSeconds % SECONDS_PER_MINUTE
    return if (minutes > 0L) "$sign${minutes}m${seconds}s" else "$sign${seconds}s"
}

/** 一次定时触发携带的调度上下文，用于日志、过期判定与提前触发保护。 */
private data class ScheduleInputContext(
    val hour: Int,
    val minute: Int,
    val timeZoneId: String,
    val generation: Long,
    val targetAt: Long,
) {
    fun isExpired(
        settings: AppSettings,
        scheduler: Scheduler,
    ): Boolean =
        hour != settings.scheduleHour ||
            minute != settings.scheduleMinute ||
            !scheduler.isCurrentSchedule(hour, minute, timeZoneId, generation)
}

private fun formatScheduleTime(millis: Long): String = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(millis))
