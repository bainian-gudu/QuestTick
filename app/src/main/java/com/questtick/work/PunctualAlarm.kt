package com.questtick.work

/*
 * 准点闹钟：WorkManager 的入队延迟在 Doze 与厂商后台策略下可能被推迟数小时，日志里出现过
 * 配置 05:00、实际 10:43 才执行的情况。这里额外用系统的 RTC 唤醒闹钟在计划时刻把任务拉起
 * （见 Scheduler.startPendingRunNow），WorkManager 仍是唯一的调度与去重依据。
 */

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.questtick.log.AppLog

private const val TAG = "PunctualAlarm"
private const val ALARM_REQUEST_CODE = 2001

internal object PunctualAlarm {
    /** 安排（或覆盖）计划时刻的唤醒闹钟；精确闹钟不可用时退化为允许打盹的宽松闹钟。 */
    fun arm(
        context: Context,
        targetAtMillis: Long,
    ) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = pendingIntent(context)
        runCatching {
            manager.cancel(pending)
            if (canUseExactAlarm(manager)) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, targetAtMillis, pending)
            } else {
                // Android 13+ 默认不授予 SCHEDULE_EXACT_ALARM；宽松闹钟仍可在 Doze 中触发，
                // 只是允许系统小幅推迟，比 JobScheduler 的排队准确得多。
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, targetAtMillis, pending)
            }
        }.onFailure { error -> AppLog.w(TAG, "设置定时签到准点闹钟失败，继续依赖 WorkManager", error) }
    }

    fun cancel(context: Context) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        runCatching { manager.cancel(pendingIntent(context)) }
            .onFailure { error -> AppLog.w(TAG, "取消定时签到准点闹钟失败", error) }
    }

    private fun canUseExactAlarm(manager: AlarmManager): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return manager.canScheduleExactAlarms()
    }

    /** 固定 requestCode：同一个 PendingIntent 被覆盖，系统里始终只挂一个签到闹钟。 */
    private fun pendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            ALARM_REQUEST_CODE,
            Intent(context, SignInAlarmReceiver::class.java)
                .setAction(SignInAlarmReceiver.ACTION_PUNCTUAL_SIGN_IN),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
