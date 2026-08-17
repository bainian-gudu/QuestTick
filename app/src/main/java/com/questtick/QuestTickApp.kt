package com.questtick

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import com.questtick.config.DsConfigRepository
import com.questtick.data.SecureStore
import com.questtick.log.CrashHandler
import com.questtick.repository.run.RunPersistenceRepository
import com.questtick.repository.log.AppErrorLogger
import com.questtick.sign.AppUpdateInstaller
import com.questtick.sign.CloudVersionRepository
import com.questtick.sign.MysAppVersionRepository
import com.questtick.work.PostRunActionScheduler
import com.questtick.work.Scheduler
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.launch
import okio.Path.Companion.toOkioPath
import javax.inject.Inject

@HiltAndroidApp
class QuestTickApp : Application(), SingletonImageLoader.Factory, Configuration.Provider {
    private companion object {
        const val TAG = "QuestTickApp"
    }

    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var secureStore: SecureStore

    @Inject lateinit var runPersistenceRepository: RunPersistenceRepository

    @Inject lateinit var postRunActionScheduler: PostRunActionScheduler

    @Inject lateinit var scheduler: Scheduler

    @Inject lateinit var appErrorLogger: AppErrorLogger

    override val workManagerConfiguration: Configuration
        get() =
            Configuration.Builder()
                // WorkManager 复用受限执行器，避免后台任务占满 IO 线程。
                .setExecutor(initDispatcher.asExecutor())
                .setWorkerFactory(workerFactory)
                .setMinimumLoggingLevel(android.util.Log.WARN)
                .build()

    // 启动期预热统一走低并发调度，降低与首屏渲染的资源竞争。
    private val initDispatcher = Dispatchers.IO.limitedParallelism(2)
    private val initScope = CoroutineScope(SupervisorJob() + initDispatcher)

    // 全局 Coil ImageLoader：面向小尺寸奖励图标控制缓存占用。
    override fun newImageLoader(context: Context): ImageLoader =
        ImageLoader.Builder(context)
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, 0.18)
                    .strongReferencesEnabled(true)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("coil_images").toOkioPath())
                    .maxSizeBytes(20L * 1024 * 1024)
                    .build()
            }
            .build()

    override fun onCreate() {
        super.onCreate()

        // 崩溃时补充写入本地诊断信息。
        CrashHandler.install(this, secureStore)
        // 先加载本地 DS 配置，确保首个请求即可使用。
        DsConfigRepository.initialize(this)
        try {
            CloudVersionRepository.initialize(this)
            // 将用户在设置页输入的自定义版本同步到生效版本，确保启动后即可使用自定义值
            CloudVersionRepository.applyOverrides(
                secureStore.getAppSettings().cloudYsVersion,
                secureStore.getAppSettings().cloudSrVersion,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "CloudVersionRepository 初始化失败", t)
            appErrorLogger.record("云游戏版本初始化", t)
        }
        // 初始化米游社版本缓存
        try {
            MysAppVersionRepository.initialize(this)
        } catch (t: Throwable) {
            Log.w(TAG, "MysAppVersionRepository 初始化失败", t)
            appErrorLogger.record("米游社版本初始化", t)
        }

        initScope.launch {
            try {
                // 先恢复上次进程遗留的运行会话，再加载历史与日历，避免 UI 短暂展示不一致状态。
                val recovery = runPersistenceRepository.recoverInterruptedRuns()
                if (recovery.recoveredRuns > 0) {
                    Log.w(
                        TAG,
                        "已恢复 ${recovery.recoveredRuns} 个中断运行，" +
                            "结果不确定任务 ${recovery.uncertainTasks} 个，未开始任务 ${recovery.notStartedTasks} 个",
                    )
                }
                // 恢复数据库中未投递或租约过期的运行后动作，避免提交后进程退出导致永久漏发。
                postRunActionScheduler.scheduleOutstanding()
                // 若 WorkManager 数据被系统清理或旧周期任务尚未切换，则在启动时补回当前时区的下一次任务。
                val currentSettings = secureStore.getAppSettings()
                if (currentSettings.scheduleEnabled) {
                    scheduler.reconcile(currentSettings.scheduleHour, currentSettings.scheduleMinute)
                }
                // 预热加密存储。DS 配置仅使用本地 assets/内置配置，不在后台联网刷新。
                secureStore.warmUp()
                // 米游社和云游戏版本只允许在实际签到流程中按 3 天间隔自动获取。
            } catch (t: Throwable) {
                Log.w(TAG, "启动后台预热任务失败", t)
                appErrorLogger.record("应用启动预热", t)
            }
        }
        initScope.launch {
            try {
                // 次级缓存延后执行，避免抢占首帧资源。
                kotlinx.coroutines.delay(2000)
                secureStore.warmSecondaryCaches()
                AppUpdateInstaller.cleanupInstalledApks(this@QuestTickApp, BuildConfig.VERSION_NAME)
            } catch (t: Throwable) {
                Log.w(TAG, "启动次级缓存清理任务失败", t)
                appErrorLogger.record("启动缓存清理", t)
            }
        }
        // 通知渠道延后到 MainActivity 创建，避免 Application 启动阶段阻塞。
    }
}
