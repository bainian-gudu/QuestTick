@file:Suppress(
    "detekt:InjectDispatcher",
    "detekt:TooGenericExceptionCaught",
)

package com.questtick.ui.vm

// 运行日志页面的加载、清理和导出状态管理。

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.questtick.data.LogEntry
import com.questtick.repository.AppStateRepository
import com.questtick.repository.base.RepositoryLoadState
import com.questtick.repository.log.LogRepository
import com.questtick.repository.log.AppErrorLogger
import com.questtick.sign.Mask
import com.questtick.sign.SignInRunCoordinator
import com.questtick.ui.screens.LogStats
import com.questtick.ui.screens.PreparedLogs
import com.questtick.ui.screens.prepareLogs
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

private const val LOG_TIME_PATTERN = "HH:mm:ss"
private const val LOG_DATE_PATTERN = "yyyy-MM-dd"

/** 页面切走后保留预处理结果的时间；期间切回可复用，超过后释放全量脱敏副本。 */
private const val LOG_PREPARE_KEEP_MILLIS = 5_000L

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

        /**
         * 日志展示数据（脱敏 + 时间格式化）在后台线程预处理。
         *
         * 全量脱敏要跑数十条正则，放在 Composable 里会在每次切回时阻塞主线程；
         * 这里只随日志变化重建，无订阅者后释放，避免处理结果长期常驻内存。
         */
        internal val displayLogs: StateFlow<PreparedLogs?> =
            logs
                .map { entries ->
                    withContext(Dispatchers.Default) {
                        val timeFormatter = SimpleDateFormat(LOG_TIME_PATTERN, Locale.getDefault())
                        val dateFormatter = SimpleDateFormat(LOG_DATE_PATTERN, Locale.getDefault())
                        prepareLogs(
                            logs = entries,
                            formatTime = { timestamp -> timeFormatter.format(Date(timestamp)) },
                            formatDate = { timestamp -> dateFormatter.format(Date(timestamp)) },
                            mask = Mask::sensitive,
                        )
                    }
                }.catch { error ->
                    appErrorLogger.record("日志预处理", error)
                    emit(PreparedLogs(emptyList(), LogStats()))
                }.stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(LOG_PREPARE_KEEP_MILLIS, replayExpirationMillis = 0L),
                    null,
                )

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
