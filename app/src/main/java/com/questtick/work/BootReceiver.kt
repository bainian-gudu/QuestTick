package com.questtick.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.questtick.BuildConfig
import com.questtick.data.SecureStore
import com.questtick.sign.AppUpdateInstaller
import com.questtick.repository.log.AppErrorLogger
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 开机、应用更新或设备时间/时区变化后重新调度每日签到。
 *
 * onReceive 运行在主线程，读取本地配置与 Keystore 预热放到一次性 IO 协程中执行；goAsync()
 * 负责延长广播生命周期，避免系统在异步调度完成前回收进程。
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {
    @Inject lateinit var store: SecureStore

    @Inject lateinit var scheduler: Scheduler

    @Inject lateinit var appErrorLogger: AppErrorLogger

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val action = intent.action ?: return
        if (action !in RESCHEDULE_ACTIONS) return

        val appContext = context.applicationContext

        // PendingResult finish 前，系统会给异步处理保留时间。
        val pending = goAsync()

        // 使用一次性 IO 协程，不持有长生命周期 scope。
        CoroutineScope(Dispatchers.IO).launch {
            try {
                AppUpdateInstaller.cleanupInstalledApks(appContext, BuildConfig.VERSION_NAME)
                val settings = store.getAppSettings()
                if (settings.scheduleEnabled) {
                    scheduler.schedule(settings.scheduleHour, settings.scheduleMinute)
                } else {
                    scheduler.cancel()
                }
            } catch (error: Exception) {
                appErrorLogger.record("系统事件重新调度", error)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private val RESCHEDULE_ACTIONS =
            setOf(
                Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_MY_PACKAGE_REPLACED,
                Intent.ACTION_TIME_CHANGED,
                Intent.ACTION_TIMEZONE_CHANGED,
                Intent.ACTION_LOCALE_CHANGED,
            )
    }
}
