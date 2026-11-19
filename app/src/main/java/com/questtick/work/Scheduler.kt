@file:Suppress(
    "detekt:TooManyFunctions",
)

package com.questtick.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.await
import com.questtick.data.commitSafely
import com.questtick.log.AppLog
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withTimeoutOrNull

private const val MINIMUM_SCHEDULE_DELAY_MS = 60_000L
private const val MAX_SCHEDULE_HOUR = 23
private const val MAX_SCHEDULE_MINUTE = 59
private const val WORK_STATE_QUERY_TIMEOUT_SECONDS = 10L
private const val INITIAL_BACKOFF_MINUTES = 5L
private const val CANCEL_AWAIT_TIMEOUT_MILLIS = 5_000L

/** 入队基准最多前视一天；超出说明上一计划时刻已经损坏，必须按当前时间重算。 */
private const val MAX_SCHEDULE_LOOKAHEAD_MS = 26L * 60 * 60 * 1000

/** 准点闹钟提前触发时允许的提前量；更早的闹钟直接忽略，交给 WorkManager 的定时任务。 */
private const val ALARM_EARLY_TOLERANCE_MS = 2L * 60 * 1000
private const val TAG = "Scheduler"

/**
 * 是否应当重建每日调度。
 *
 * 查询现有调度失败时必须按「仍有活动工作」处理：把失败当成「没有活动任务」会取消掉
 * 正在退避重试的工作，当天的签到连同重试链会被静默丢弃。
 */
internal fun shouldRebuildSchedule(
    metadataMatches: Boolean,
    hasActiveWork: Boolean,
    probeFailed: Boolean,
): Boolean = !probeFailed && (!metadataMatches || !hasActiveWork)

internal fun computeScheduleTargetMillis(
    hour: Int,
    minute: Int,
    nowMillis: Long = System.currentTimeMillis(),
    timeZone: TimeZone = TimeZone.getDefault(),
): Long {
    val targetHour = hour.coerceIn(0, MAX_SCHEDULE_HOUR)
    val targetMinute = minute.coerceIn(0, MAX_SCHEDULE_MINUTE)
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

/**
 * 解析下一次触发的计划时刻。
 *
 * 正常情况在上一计划时刻的基础上加一天，保证「一个业务日只跑一次」；但入队基准一旦损坏或被旧版本
 * 写歪（例如比现在晚几十小时），继续加一天会让触发时刻一天天飘走，因此超出合理前视窗口就按当前
 * 时间重算。所有入参都必须是系统墙钟（WorkManager 的入队延迟按真实经过时间倒计时）。
 */
internal fun resolveNextTargetMillis(
    hour: Int,
    minute: Int,
    nowMillis: Long,
    timeZone: TimeZone = TimeZone.getDefault(),
    previousTargetAt: Long? = null,
): Long {
    if (previousTargetAt == null) return computeScheduleTargetMillis(hour, minute, nowMillis, timeZone)
    val next =
        Calendar.getInstance(timeZone).apply {
            timeInMillis = previousTargetAt
            add(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, hour.coerceIn(0, MAX_SCHEDULE_HOUR))
            set(Calendar.MINUTE, minute.coerceIn(0, MAX_SCHEDULE_MINUTE))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
    while (next.timeInMillis <= nowMillis) next.add(Calendar.DAY_OF_MONTH, 1)
    return if (next.timeInMillis - nowMillis > MAX_SCHEDULE_LOOKAHEAD_MS) {
        computeScheduleTargetMillis(hour, minute, nowMillis, timeZone)
    } else {
        next.timeInMillis
    }
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
    ) {
        /** 保护代次的读-改-写；只在极短的 SharedPreferences 写入期间持有。 */
        private val generationLock = Any()

        /** 设置发生变化时取消全部旧代任务，并从当前时间重新建立调度。 */
        suspend fun schedule(
            hour: Int,
            minute: Int,
        ) {
            val (previousGeneration, generation) = beginGeneration()
            PunctualAlarm.cancel(context)
            WorkManager.getInstance(context).apply {
                // 每代使用独立标签；取消是异步操作，先等它提交，避免旧代与新代在同一时刻都被放行。
                // 等待超时只告警：新代任务用独立工作名，仍可正常入队。
                cancelUniqueWork(LEGACY_UNIQUE_NAME).awaitCanceled()
                cancelAllWorkByTag(generationTag(previousGeneration)).awaitCanceled()
            }
            enqueueNext(hour, minute, generation)
        }

        /** 应用启动时只补丢失的调度，不打断仍在等待、运行或退避重试的有效工作。 */
        suspend fun reconcile(
            hour: Int,
            minute: Int,
        ) {
            val safeHour = hour.coerceIn(0, MAX_SCHEDULE_HOUR)
            val safeMinute = minute.coerceIn(0, MAX_SCHEDULE_MINUTE)
            val metadataMatches =
                context.getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE).let { prefs ->
                    prefs.getInt(KEY_HOUR, -1) == safeHour &&
                        prefs.getInt(KEY_MINUTE, -1) == safeMinute &&
                        prefs.getString(KEY_TIME_ZONE, "") == TimeZone.getDefault().id
                }
            val probe =
                runCatching {
                    WorkManager
                        .getInstance(context)
                        .getWorkInfosByTag(generationTag(currentGeneration()))
                        .get(WORK_STATE_QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                }
            val probeFailed = probe.isFailure
            val hasActiveWork =
                probe.getOrNull()?.any { info ->
                    info.state == WorkInfo.State.ENQUEUED ||
                        info.state == WorkInfo.State.RUNNING ||
                        info.state == WorkInfo.State.BLOCKED
                } ?: false
            when {
                shouldRebuildSchedule(metadataMatches, hasActiveWork, probeFailed) -> {
                    schedule(safeHour, safeMinute)
                }

                probeFailed -> {
                    // 查询失败不能当成「没有活动任务」：那会取消正在退避重试的工作，当天的签到被静默丢弃。
                    val error = probe.exceptionOrNull()
                    if (error == null) {
                        AppLog.w(TAG, "读取现有签到调度失败，保留当前调度以免打断退避重试")
                    } else {
                        AppLog.w(TAG, "读取现有签到调度失败，保留当前调度以免打断退避重试", error)
                    }
                }

                else -> {
                    // 调度没丢也要补挂闹钟：闹钟可能在重启、应用更新或系统清理后消失，而工作仍在排队。
                    rearmPunctualAlarmIfPending()
                }
            }
        }

        /** Worker 启动后预先补上下一个当地日任务，不取消当前正在运行或重试的工作。 */
        suspend fun scheduleFollowingRun(
            hour: Int,
            minute: Int,
            generation: Long,
            scheduledTargetAt: Long,
            anchorFromNow: Boolean = false,
        ) {
            if (generation != currentGeneration()) return
            // anchorFromNow 用于设备时钟被调整过的场景：继续沿旧绝对时间戳加一天会让触发时刻一直偏，
            // 按当前时间重算一次即可回到配置的墙上时间（不会跳过执行，也不会形成连续空转）。
            enqueueNext(hour, minute, generation, if (anchorFromNow) null else scheduledTargetAt)
        }

        fun isCurrentSchedule(
            hour: Int,
            minute: Int,
            timeZoneId: String,
            generation: Long,
        ): Boolean =
            generation == currentGeneration() &&
                hour in 0..MAX_SCHEDULE_HOUR &&
                minute in 0..MAX_SCHEDULE_MINUTE &&
                timeZoneId == TimeZone.getDefault().id

        private suspend fun enqueueNext(
            hour: Int,
            minute: Int,
            generation: Long,
            previousTargetAt: Long? = null,
        ) {
            val safeHour = hour.coerceIn(0, MAX_SCHEDULE_HOUR)
            val safeMinute = minute.coerceIn(0, MAX_SCHEDULE_MINUTE)
            val zone = TimeZone.getDefault()
            // WorkManager 的入队延迟按「真实经过时间」倒计时，因此计划时刻必须用系统墙钟推导。
            // 曾混用网络授时（多源平均）作为基准，一旦与系统墙钟不一致，触发点就会整体偏移——日志里
            // 出现过配置 05:00、实际 02:10 触发，因此这里只用系统墙钟。
            val now = System.currentTimeMillis()
            val targetAt = resolveNextTargetMillis(safeHour, safeMinute, now, zone, previousTargetAt)
            val delay = (targetAt - now).coerceAtLeast(MINIMUM_SCHEDULE_DELAY_MS)

            WorkManager.getInstance(context).enqueueUniqueWork(
                workName(generation, targetAt),
                ExistingWorkPolicy.KEEP,
                buildSignInRequest(
                    spec = SignInRequestSpec(safeHour, safeMinute, zone.id, generation, targetAt),
                    delayMillis = delay,
                    expedited = false,
                ),
            )
            persistPendingSchedule(generation, targetAt, safeHour, safeMinute, zone.id)
            PunctualAlarm.arm(context, targetAt)
        }

        private fun buildSignInRequest(
            spec: SignInRequestSpec,
            delayMillis: Long,
            expedited: Boolean,
        ): OneTimeWorkRequest {
            val input =
                Data
                    .Builder()
                    .putInt(INPUT_HOUR, spec.hour)
                    .putInt(INPUT_MINUTE, spec.minute)
                    .putString(INPUT_TIME_ZONE, spec.timeZoneId)
                    .putLong(INPUT_TARGET_AT, spec.targetAt)
                    .putLong(INPUT_GENERATION, spec.generation)
                    .putBoolean(INPUT_EXPEDITED_KICK, expedited)
                    .build()
            val builder =
                OneTimeWorkRequestBuilder<SignInWorker>()
                    .addTag(generationTag(spec.generation))
                    .setInputData(input)
                    // 只要网络可用即可执行：签到本身只有一次请求，电池/存储约束只会在夜里把任务卡住。
                    .setConstraints(
                        Constraints
                            .Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .setRequiresStorageNotLow(true)
                            .build(),
                    )
                    // 从 5 分钟开始指数退避；持久化 notBefore 继续限制网络故障与 HTTP 429 重放。
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, INITIAL_BACKOFF_MINUTES, TimeUnit.MINUTES)
            if (expedited) {
                // 加急任务由 WorkManager 以自身前台服务立刻执行，不受 JobScheduler 的 Doze 排队影响。
                builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            } else {
                builder.setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            }
            return builder.build()
        }

        /**
         * 准点闹钟触发：把当天已排好的工作提升为「立即执行 + 加急」。
         *
         * WorkManager 的入队延迟在 Doze 和厂商后台策略下会被推迟数小时（日志里 05:00 的计划
         * 实际 10:43 才跑）。闹钟是系统层面的准点唤醒，这里用它提前拉起同一个工作名的工作，
         * 因此业务日归属不变，执行槽位仍按业务日去重，也不会与 WorkManager 的定时任务重复签到。
         */
        suspend fun startPendingRunNow() {
            val pending = readPendingSchedule()?.takeIf { it.generation == currentGeneration() } ?: return
            val name = workName(pending.generation, pending.targetAt)
            val manager = WorkManager.getInstance(context)
            // 闹钟异常提前时不抢跑，交给 WorkManager 按计划时刻执行；已开始或已结束的工作不再重复拉起。
            val readyToRun =
                System.currentTimeMillis() + ALARM_EARLY_TOLERANCE_MS >= pending.targetAt && !manager.workStateOf(name)
            if (readyToRun) {
                manager.enqueueUniqueWork(
                    name,
                    ExistingWorkPolicy.REPLACE,
                    buildSignInRequest(
                        spec = SignInRequestSpec(pending),
                        delayMillis = 0L,
                        expedited = true,
                    ),
                )
                AppLog.i(TAG, "准点闹钟已把当日签到提升为立即执行")
            }
        }

        /** 已开始或已结束的工作不再重复拉起，避免把正在进行的运行换成新的。 */
        private suspend fun WorkManager.workStateOf(name: String): Boolean {
            val infos =
                runCatching {
                    getWorkInfosForUniqueWork(name).get(WORK_STATE_QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                }.getOrNull() ?: return false
            return infos.any { info ->
                info.state == WorkInfo.State.RUNNING ||
                    info.state == WorkInfo.State.SUCCEEDED ||
                    info.state == WorkInfo.State.FAILED
            }
        }

        private fun rearmPunctualAlarmIfPending() {
            val pending = readPendingSchedule()?.takeIf { it.generation == currentGeneration() } ?: return
            if (pending.targetAt > System.currentTimeMillis()) {
                PunctualAlarm.arm(context, pending.targetAt)
            }
        }

        private fun persistPendingSchedule(
            generation: Long,
            targetAt: Long,
            hour: Int,
            minute: Int,
            timeZoneId: String,
        ) {
            context
                .getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_HOUR, hour)
                .putInt(KEY_MINUTE, minute)
                .putString(KEY_TIME_ZONE, timeZoneId)
                .putLong(KEY_TARGET_AT, targetAt)
                .putLong(KEY_PENDING_GENERATION, generation)
                .commitSafely(TAG, "persist schedule metadata")
        }

        private fun readPendingSchedule(): PendingSchedule? {
            val prefs = context.getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE)
            val targetAt = prefs.getLong(KEY_TARGET_AT, 0L)
            val hour = prefs.getInt(KEY_HOUR, -1)
            val minute = prefs.getInt(KEY_MINUTE, -1)
            val generation = prefs.getLong(KEY_PENDING_GENERATION, -1L)
            if (targetAt <= 0L || hour !in 0..MAX_SCHEDULE_HOUR || minute !in 0..MAX_SCHEDULE_MINUTE) return null
            return PendingSchedule(
                generation = generation,
                targetAt = targetAt,
                hour = hour,
                minute = minute,
                timeZoneId = prefs.getString(KEY_TIME_ZONE, "").orEmpty(),
            )
        }

        private fun workName(
            generation: Long,
            targetAt: Long,
        ): String = "${WORK_NAME_PREFIX}${generation}_$targetAt"

        private data class PendingSchedule(
            val generation: Long,
            val targetAt: Long,
            val hour: Int,
            val minute: Int,
            val timeZoneId: String,
        )

        private data class SignInRequestSpec(
            val hour: Int,
            val minute: Int,
            val timeZoneId: String,
            val generation: Long,
            val targetAt: Long,
        ) {
            constructor(pending: PendingSchedule) : this(
                hour = pending.hour,
                minute = pending.minute,
                timeZoneId = pending.timeZoneId,
                generation = pending.generation,
                targetAt = pending.targetAt,
            )
        }

        fun cancel() {
            val previousGeneration = beginGeneration().first
            PunctualAlarm.cancel(context)
            WorkManager.getInstance(context).apply {
                cancelUniqueWork(LEGACY_UNIQUE_NAME)
                cancelAllWorkByTag(generationTag(previousGeneration))
            }
        }

        private fun generationTag(generation: Long): String = "$SCHEDULE_TAG_PREFIX$generation"

        /**
         * 原子地读取旧代次并推进到新代次。
         *
         * 读-改-写分开做时，两次并发 schedule 可能读到同一个旧代次、取消同一批标签，
         * 导致其中一代的工作既没被取消也没被正确替换。
         */
        private fun beginGeneration(): Pair<Long, Long> =
            synchronized(generationLock) {
                val prefs = context.getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE)
                val previous = prefs.getLong(KEY_GENERATION, 0L)
                val next = previous + 1L
                prefs.edit().putLong(KEY_GENERATION, next).commitSafely(TAG, "advance schedule generation")
                previous to next
            }

        private fun currentGeneration(): Long =
            context
                .getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE)
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

            /** 本次工作是否由准点闹钟提升为加急执行（加急工作由 WorkManager 自行管理前台服务）。 */
            const val INPUT_EXPEDITED_KICK = "schedule_expedited_kick"
            private const val PREFERENCES_FILE = "signin_preferences"
            private const val KEY_GENERATION = "schedule_generation"
            private const val KEY_HOUR = "schedule_registered_hour"
            private const val KEY_MINUTE = "schedule_registered_minute"
            private const val KEY_TIME_ZONE = "schedule_registered_time_zone"

            /** 已排定但尚未执行的计划：准点闹钟据此把工作提升为立即执行（新增键，旧版本缺失时自动跳过）。 */
            private const val KEY_TARGET_AT = "schedule_pending_target_at"
            private const val KEY_PENDING_GENERATION = "schedule_pending_generation"
        }
    }

/** 等待 WorkManager 取消操作提交；超时或失败只告警，不阻塞新代次入队。 */
private suspend fun Operation.awaitCanceled() {
    val settled =
        withTimeoutOrNull(CANCEL_AWAIT_TIMEOUT_MILLIS) {
            runCatching { await() }.isSuccess
        }
    if (settled != true) {
        AppLog.w(TAG, "等待 WorkManager 取消旧调度未完成，已继续按新代次入队")
    }
}
