package com.questtick.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val MINIMUM_SCHEDULE_DELAY_MS = 60_000L
private const val MAXIMUM_SCHEDULE_JITTER_MS = 30L * 60 * 1000

internal fun computeScheduleTargetMillis(
    hour: Int,
    minute: Int,
    nowMillis: Long = System.currentTimeMillis(),
    timeZone: TimeZone = TimeZone.getDefault(),
): Long {
    val targetHour = hour.coerceIn(0, 23)
    val targetMinute = minute.coerceIn(0, 59)
    val now = Calendar.getInstance(timeZone).apply { timeInMillis = nowMillis }
    val next =
        Calendar.getInstance(timeZone).apply {
            timeInMillis = nowMillis
            set(Calendar.HOUR_OF_DAY, targetHour)
            set(Calendar.MINUTE, targetMinute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
    if (next.timeInMillis <= now.timeInMillis) {
        next.add(Calendar.DAY_OF_MONTH, 1)
    }
    // Calendar 按设备时区解析当地墙上时间，可确定性处理夏令时缺失/重叠时段。
    return next.timeInMillis
}

internal fun computeScheduleInitialDelayMillis(
    hour: Int,
    minute: Int,
    nowMillis: Long = System.currentTimeMillis(),
    jitterMillis: Long = 0L,
    timeZone: TimeZone = TimeZone.getDefault(),
): Long {
    val safeJitterMillis = jitterMillis.coerceIn(0L, MAXIMUM_SCHEDULE_JITTER_MS)
    val targetAt = computeScheduleTargetMillis(hour, minute, nowMillis, timeZone)
    return (targetAt - nowMillis).coerceAtLeast(MINIMUM_SCHEDULE_DELAY_MS) + safeJitterMillis
}

/**
 * 按设备当前时区安排下一次每日签到。
 *
 * 每次只创建一个指向下一当地墙上时间的一次性工作，Worker 启动时再安排下一天。这样不会因
 * PeriodicWork 的固定 24 小时周期在夏令时切换后偏移一小时，也不会沿用旧任务的入队基准。
 */
@Singleton
class Scheduler
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val scheduleClock: ScheduleClock,
    ) {
        /** 设置发生变化时取消全部旧代任务，并从当前时间重新建立调度。 */
        suspend fun schedule(
            hour: Int,
            minute: Int,
        ) {
            val previousGeneration = currentGeneration()
            val generation = advanceGeneration()
            WorkManager.getInstance(context).apply {
                cancelUniqueWork(LEGACY_UNIQUE_NAME)
                // 每代使用独立标签；异步取消旧代不会误伤随后入队的新代任务。
                cancelAllWorkByTag(generationTag(previousGeneration))
            }
            enqueueNext(hour, minute, generation)
        }

        /** 应用启动时只补丢失的调度，不打断仍在等待、运行或退避重试的有效工作。 */
        suspend fun reconcile(
            hour: Int,
            minute: Int,
        ) {
            val safeHour = hour.coerceIn(0, 23)
            val safeMinute = minute.coerceIn(0, 59)
            val metadataMatches =
                context.getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE).let { prefs ->
                    prefs.getInt(KEY_HOUR, -1) == safeHour &&
                        prefs.getInt(KEY_MINUTE, -1) == safeMinute &&
                        prefs.getString(KEY_TIME_ZONE, "") == TimeZone.getDefault().id
                }
            val hasActiveWork =
                runCatching {
                    WorkManager.getInstance(context)
                        .getWorkInfosByTag(generationTag(currentGeneration()))
                        .get(10, TimeUnit.SECONDS)
                        .any { info ->
                            info.state == WorkInfo.State.ENQUEUED ||
                                info.state == WorkInfo.State.RUNNING ||
                                info.state == WorkInfo.State.BLOCKED
                        }
                }.getOrDefault(false)
            if (!metadataMatches || !hasActiveWork) schedule(safeHour, safeMinute)
        }

        /** Worker 启动后预先补上下一个当地日任务，不取消当前正在运行或重试的工作。 */
        suspend fun scheduleFollowingRun(
            hour: Int,
            minute: Int,
            generation: Long,
            scheduledTargetAt: Long,
        ) {
            if (generation == currentGeneration()) {
                enqueueNext(hour, minute, generation, scheduledTargetAt)
            }
        }

        fun isCurrentSchedule(
            hour: Int,
            minute: Int,
            timeZoneId: String,
            generation: Long,
        ): Boolean =
            generation == currentGeneration() &&
                hour in 0..23 && minute in 0..59 &&
                timeZoneId == TimeZone.getDefault().id

        private suspend fun enqueueNext(
            hour: Int,
            minute: Int,
            generation: Long,
            previousTargetAt: Long? = null,
        ) {
            val safeHour = hour.coerceIn(0, 23)
            val safeMinute = minute.coerceIn(0, 59)
            val now = scheduleClock.nowMillis()
            val zone = TimeZone.getDefault()
            val targetAt = computeNextTargetMillis(safeHour, safeMinute, now, zone, previousTargetAt)
            val delay = (targetAt - now).coerceAtLeast(MINIMUM_SCHEDULE_DELAY_MS)
            val workName = "${WORK_NAME_PREFIX}${generation}_$targetAt"
            val constraints =
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .setRequiresStorageNotLow(true)
                    .build()
            val input =
                Data.Builder()
                    .putInt(INPUT_HOUR, safeHour)
                    .putInt(INPUT_MINUTE, safeMinute)
                    .putString(INPUT_TIME_ZONE, zone.id)
                    .putLong(INPUT_TARGET_AT, targetAt)
                    .putLong(INPUT_GENERATION, generation)
                    .build()
            val request =
                OneTimeWorkRequestBuilder<SignInWorker>()
                    .addTag(generationTag(generation))
                    .setInputData(input)
                    .setConstraints(constraints)
                    .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                    // 从 5 分钟开始指数退避；持久化 notBefore 继续限制网络故障与 HTTP 429 重放。
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
                    .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                workName,
                ExistingWorkPolicy.KEEP,
                request,
            )
            check(
                context.getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE)
                    .edit()
                    .putInt(KEY_HOUR, safeHour)
                    .putInt(KEY_MINUTE, safeMinute)
                    .putString(KEY_TIME_ZONE, zone.id)
                    .commit(),
            ) { "Failed to persist schedule metadata" }
        }

        private fun computeNextTargetMillis(
            hour: Int,
            minute: Int,
            nowMillis: Long,
            timeZone: TimeZone,
            previousTargetAt: Long?,
        ): Long {
            if (previousTargetAt == null) return computeScheduleTargetMillis(hour, minute, nowMillis, timeZone)
            val next = Calendar.getInstance(timeZone).apply {
                timeInMillis = previousTargetAt
                add(Calendar.DAY_OF_MONTH, 1)
                set(Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23))
                set(Calendar.MINUTE, minute.coerceIn(0, 59))
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            while (next.timeInMillis <= nowMillis) next.add(Calendar.DAY_OF_MONTH, 1)
            return next.timeInMillis
        }

        fun cancel() {
            val previousGeneration = currentGeneration()
            advanceGeneration()
            WorkManager.getInstance(context).apply {
                cancelUniqueWork(LEGACY_UNIQUE_NAME)
                cancelAllWorkByTag(generationTag(previousGeneration))
            }
        }

        private fun generationTag(generation: Long): String = "$SCHEDULE_TAG_PREFIX$generation"

        private fun advanceGeneration(): Long {
            val prefs = context.getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE)
            val next = prefs.getLong(KEY_GENERATION, 0L) + 1L
            check(prefs.edit().putLong(KEY_GENERATION, next).commit()) {
                "Failed to persist schedule generation"
            }
            return next
        }

        private fun currentGeneration(): Long =
            context.getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE)
                .getLong(KEY_GENERATION, 0L)

        companion object {
            const val LEGACY_UNIQUE_NAME = "daily_signin_work"
            const val SCHEDULE_TAG_PREFIX = "daily_signin_schedule_"
            const val WORK_NAME_PREFIX = "daily_signin_at_"
            const val INPUT_HOUR = "schedule_hour"
            const val INPUT_MINUTE = "schedule_minute"
            const val INPUT_TIME_ZONE = "schedule_time_zone"
            const val INPUT_TARGET_AT = "schedule_target_at"
            const val INPUT_GENERATION = "schedule_generation"
            private const val PREFERENCES_FILE = "signin_preferences"
            private const val KEY_GENERATION = "schedule_generation"
            private const val KEY_HOUR = "schedule_registered_hour"
            private const val KEY_MINUTE = "schedule_registered_minute"
            private const val KEY_TIME_ZONE = "schedule_registered_time_zone"
        }
    }
