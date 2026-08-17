package com.questtick.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.questtick.repository.account.AccountRepository
import com.questtick.repository.history.HistoryRepository
import com.questtick.repository.log.LogRepository
import com.questtick.repository.log.AppErrorLogger
import com.questtick.core.throwIfCancellation
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 数据同步任务：进程存活时把 Room / Store 最新数据同步到内存 StateFlow。 */
@HiltWorker
class SyncWorker
    @AssistedInject
    constructor(
        @Assisted appContext: Context,
        @Assisted params: WorkerParameters,
        private val accountRepository: AccountRepository,
        private val historyRepository: HistoryRepository,
        private val logRepository: LogRepository,
        private val appErrorLogger: AppErrorLogger,
    ) : CoroutineWorker(appContext, params) {
        override suspend fun doWork(): Result =
            withContext(Dispatchers.IO) {
                try {
                    accountRepository.reload()
                    historyRepository.reload()
                    logRepository.reload()
                    Result.success()
                } catch (error: Exception) {
                    error.throwIfCancellation()
                    appErrorLogger.record("后台数据同步", error)
                    Result.retry()
                }
            }

        companion object {
            const val UNIQUE_NAME = "sync_worker"
        }
    }
