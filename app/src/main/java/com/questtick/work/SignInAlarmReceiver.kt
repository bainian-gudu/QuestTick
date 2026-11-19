@file:Suppress(
    "detekt:InjectDispatcher",
    "detekt:TooGenericExceptionCaught",
)

package com.questtick.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.questtick.data.SecureStore
import com.questtick.repository.log.AppErrorLogger
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 定时签到准点闹钟的接收方。
 *
 * 闹钟只负责「到点把当天已排好的工作提升为立即执行」，调度与去重仍由 WorkManager 决定；
 * 开启定时任务的情况下才会动作，关闭后残留的闹钟不会触发签到。
 */
@AndroidEntryPoint
class SignInAlarmReceiver : BroadcastReceiver() {
    @Inject lateinit var store: SecureStore

    @Inject lateinit var scheduler: Scheduler

    @Inject lateinit var appErrorLogger: AppErrorLogger

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != ACTION_PUNCTUAL_SIGN_IN) return

        // goAsync 延长广播生命周期，避免系统在拉起工作前回收进程。
        // 独立 SupervisorJob：单次失败不应连带取消其它广播留下的任务。
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                if (store.getAppSettings().scheduleEnabled) {
                    scheduler.startPendingRunNow()
                }
            } catch (error: Exception) {
                appErrorLogger.record("定时签到准点拉起", error)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_PUNCTUAL_SIGN_IN = "com.questtick.action.PUNCTUAL_SIGN_IN"
    }
}
