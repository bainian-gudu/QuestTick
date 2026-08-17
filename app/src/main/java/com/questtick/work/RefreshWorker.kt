package com.questtick.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.questtick.config.DsConfigRepository
import com.questtick.data.SecureStore
import com.questtick.core.throwIfCancellation
import com.questtick.repository.log.AppErrorLogger
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 轻量本地刷新任务：仅初始化本地 DS 配置并预热设置，不访问远程 ds_config。 */
@HiltWorker
class RefreshWorker
    @AssistedInject
    constructor(
        @Assisted appContext: Context,
        @Assisted params: WorkerParameters,
        private val store: SecureStore,
        private val appErrorLogger: AppErrorLogger,
    ) : CoroutineWorker(appContext, params) {
        override suspend fun doWork(): Result =
            withContext(Dispatchers.IO) {
                try {
                    DsConfigRepository.initialize(applicationContext)
                    store.getAppSettings()
                    Result.success()
                } catch (error: Exception) {
                    error.throwIfCancellation()
                    appErrorLogger.record("本地刷新任务", error)
                    Result.retry()
                }
            }

        companion object {
            const val UNIQUE_NAME = "refresh_worker"
        }
    }
