package com.questtick.ui.vm

/** 运行日志页面的加载、清理和导出状态管理。 */

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.questtick.data.LogEntry
import com.questtick.repository.AppStateRepository
import com.questtick.repository.base.RepositoryLoadState
import com.questtick.repository.log.LogRepository
import com.questtick.repository.log.AppErrorLogger
import com.questtick.sign.SignInRunCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LogsViewModel
    @Inject
    constructor(
        private val logRepository: LogRepository,
        private val appStateRepository: AppStateRepository,
        private val runCoordinator: SignInRunCoordinator,
        private val appErrorLogger: AppErrorLogger,
    ) : ViewModel() {
        val logs: StateFlow<List<LogEntry>> = logRepository.logs
        val loadState: StateFlow<RepositoryLoadState> = logRepository.loadState
        val runningStartedAt: StateFlow<Long> = runCoordinator.runningStartedAt

        fun refresh() {
            viewModelScope.launch(Dispatchers.IO) {
                runCatching { logRepository.reload() }
                    .onFailure { appErrorLogger.record("运行日志刷新", it) }
            }
        }

        fun clearLogs() {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    logRepository.clear()
                } catch (e: Exception) {
                    appErrorLogger.record("运行日志清空", e)
                    appStateRepository.triggerToast("清空失败: ${e.message}")
                }
            }
        }

        fun showToast(msg: String) {
            appStateRepository.triggerToast(msg)
        }
    }
