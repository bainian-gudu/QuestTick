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
import com.questtick.data.FailureCategory
import com.questtick.data.LogEntry
import com.questtick.data.RunRecord
import com.questtick.data.dayKey
import com.questtick.data.SecureStore
import com.questtick.repository.account.AccountRepository
import com.questtick.repository.history.HistoryRepository
import com.questtick.repository.log.LogRepository
import com.questtick.repository.log.AppErrorLogger
import com.questtick.repository.run.ExecutionStateRepository
import com.questtick.repository.settings.SettingsRepository
import com.questtick.sign.RiskState
import com.questtick.sign.SignInRunCoordinator
import com.questtick.data.RunTerminationReason
import com.questtick.data.RunTrigger
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException

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
                val result =
                    runCoordinator.tryRun {
                        // 尝试提升为前台服务；被 ROM 拒绝时降级为普通后台执行。
                        try {
                            setForeground(createForegroundInfo())
                        } catch (e: CancellationException) {
                            throw e
                        } catch (error: Exception) {
                            appErrorLogger.record("定时签到前台服务", error)
                            // ROM 拒绝前台切换时降级，但不能吞掉 WorkManager 取消信号。
                        }
                        executeSignIn()
                    }
                result ?: retryBecauseRunLockBusy()
            } catch (e: CancellationException) {
                // 系统主动取消时向上抛出，让 WorkManager 处理。
                throw e
            } catch (e: Exception) {
                appErrorLogger.record("定时签到", e, "定时签到执行异常，已停止本业务日自动重放")
                // 未形成结构化运行结果时不盲目重放 POST；结束本周期但保留下一业务日调度。
                Result.success()
            }

        private suspend fun retryBecauseRunLockBusy(): Result {
            val entry =
                LogEntry(
                    timestamp = System.currentTimeMillis(),
                    level = "WARN",
                    message = "定时签到暂未开始：已有签到任务正在运行",
                    detail = "WorkManager 将按退避策略重试，避免本次定时签到被静默跳过。",
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

        private suspend fun executeSignIn(): Result {
            val settings = store.getAppSettings()
            if (!settings.scheduleEnabled) {
                logScheduleDisabledSkip()
                syncRepositoryAfterBackgroundRun()
                return Result.success()
            }

            val scheduledHour = inputData.getInt(Scheduler.INPUT_HOUR, -1)
            val scheduledMinute = inputData.getInt(Scheduler.INPUT_MINUTE, -1)
            val scheduledTimeZone = inputData.getString(Scheduler.INPUT_TIME_ZONE).orEmpty()
            val scheduleGeneration = inputData.getLong(Scheduler.INPUT_GENERATION, -1L)
            val scheduledTargetAt = inputData.getLong(Scheduler.INPUT_TARGET_AT, System.currentTimeMillis())
            if (
                scheduledHour != settings.scheduleHour ||
                scheduledMinute != settings.scheduleMinute ||
                !scheduler.isCurrentSchedule(
                    scheduledHour,
                    scheduledMinute,
                    scheduledTimeZone,
                    scheduleGeneration,
                )
            ) {
                // 取消与入队是异步的；即使旧任务在竞态窗口中被系统启动，也不能执行过期计划。
                return Result.success()
            }
            // 目标时间与代次组成唯一工作名，因此每次退避重试都可安全修复下一日调度而不会重复入队。
            // 补下一日失败不能反向阻止本次已到点任务；时间/时区广播与下次启动还会再次修复调度。
            runCatching {
                scheduler.scheduleFollowingRun(
                    scheduledHour,
                    scheduledMinute,
                    scheduleGeneration,
                    scheduledTargetAt,
                )
            }.onFailure { error -> appErrorLogger.record("下一次定时签到调度", error) }

            val startedAt = System.currentTimeMillis()
            // 延迟启动与跨午夜退避始终归属原计划业务日，确保只重试上一轮明确可重试的失败任务。
            val businessDayKey = dayKey(scheduledTargetAt)
            val workId = id.toString()
            when (executionStateRepository.claimScheduledSlot(workId, runAttemptCount, businessDayKey, startedAt)) {
                ExecutionStateRepository.SlotClaim.DUPLICATE -> {
                    logDuplicateScheduleSkip(businessDayKey)
                    return Result.success()
                }

                ExecutionStateRepository.SlotClaim.NOT_BEFORE -> {
                    return Result.retry()
                }

                ExecutionStateRepository.SlotClaim.CLAIMED -> {
                    Unit
                }
            }

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
                    )

                if (record.total == 0) {
                    executionStateRepository.completeScheduledSlot(workId, businessDayKey, record)
                    syncRepositoryAfterBackgroundRun()
                    return Result.success()
                }

                executionStateRepository.applyRunGuards(record)
                updateFailureGuard(store, record)
                syncRepositoryAfterBackgroundRun()

                val decision = decideSignInWorkerResult(record, actualRunCount - 1, MAX_RETRIES)
                if (decision.retry) {
                    executionStateRepository.failScheduledSlot(
                        workId = workId,
                        businessDayKey = businessDayKey,
                        record = record,
                        category = decision.category,
                        retryAfterMillis = decision.retryDelayMillis,
                    )
                    Result.retry()
                } else if (decision.category == FailureCategory.NONE) {
                    executionStateRepository.completeScheduledSlot(workId, businessDayKey, record)
                    Result.success()
                } else {
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

        /** 连续失败达到阈值时自动关闭定时任务，避免进一步触发风控。 */
        private suspend fun updateFailureGuard(
            store: SecureStore,
            record: RunRecord,
        ) {
            if (
                record.terminationReason == RunTerminationReason.ROOT_DETECTED ||
                record.terminationReason == RunTerminationReason.ROOT_CHECK_FAILED ||
                record.resultUnknown > 0
            ) {
                // 结果待确认既不能计作远端失败，也不能清零已有风控计数。
                return
            }
            val risk = RiskState.fromRecord(record)
            val countAsFailure = record.total > 0 && (record.failed == record.total || risk != RiskState.NORMAL)
            if (!countAsFailure) {
                store.resetSignInFailures()
                return
            }

            val failures = store.recordSignInFailure()
            if (failures < MAX_CONSECUTIVE_FAILURES) return

            val reason =
                if (risk == RiskState.NORMAL) {
                    "连续${failures}次签到全部失败"
                } else {
                    "连续${failures}次签到异常，检测到风控状态：$risk"
                }
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
        }
    }
