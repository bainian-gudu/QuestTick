@file:Suppress(
    "detekt:InjectDispatcher",
    "detekt:TooGenericExceptionCaught",
)

package com.questtick.ui.vm

// 签到记录页面的加载、清理和邮件重发状态管理。

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.questtick.data.RunRecord
import com.questtick.repository.AppStateRepository
import com.questtick.repository.base.RepositoryLoadState
import com.questtick.repository.history.HistoryRepository
import com.questtick.repository.log.AppErrorLogger
import com.questtick.repository.run.PostRunActionRepository
import com.questtick.repository.run.PostRunActionType
import com.questtick.repository.settings.SettingsRepository
import com.questtick.ui.screens.RecordsSnapshot
import com.questtick.ui.screens.buildRecordsSnapshot
import com.questtick.work.PostRunActionScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** 记录页切走后保留索引的时间；期间切回复用，避免按记录数重建。 */
private const val RECORDS_INDEX_KEEP_MILLIS = 5_000L

@HiltViewModel
class RecordsViewModel
    @Inject
    constructor(
        private val historyRepository: HistoryRepository,
        private val appStateRepository: AppStateRepository,
        private val postRunActionRepository: PostRunActionRepository,
        private val postRunActionScheduler: PostRunActionScheduler,
        private val settingsRepository: SettingsRepository,
        private val appErrorLogger: AppErrorLogger,
    ) : ViewModel() {
        private var refreshJob: Job? = null
        private var lastRefreshAt = 0L

        val history: StateFlow<List<RunRecord>> = historyRepository.history
        val loadState: StateFlow<RepositoryLoadState> = historyRepository.loadState

        /**
         * 记录索引在 ViewModel 内构建，只随历史数据变化重建。
         *
         * 分页器会回收远离视口的记录页，索引若放在 Composable 里会在每次切回时按记录数重建；
         * 记录多时这段全量遍历会与切换动画争抢 CPU 与内存带宽，导致切页不流畅。
         * 索引按需构建并在切走后短暂保留，避免应用启动时白白遍历一遍历史。
         */
        internal val records: StateFlow<RecordsSnapshot?> =
            historyRepository.history
                .map { records -> withContext(Dispatchers.Default) { buildRecordsSnapshot(records) } }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(RECORDS_INDEX_KEEP_MILLIS), null)

        val emailDeliveries: StateFlow<Map<String, EmailDeliveryUiState>> =
            postRunActionRepository
                .observeAll()
                .map { actions ->
                    actions
                        .asSequence()
                        .filter { it.type == PostRunActionType.EMAIL.name }
                        .associate { action ->
                            action.runId to
                                EmailDeliveryUiState(
                                    actionId = action.actionId,
                                    status = action.status,
                                    attemptCount = action.attemptCount,
                                )
                        }
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(EMAIL_DELIVERY_STOP_TIMEOUT_MS), emptyMap())

        /**
         * 记录页在主分页器中会保留组合状态，每次切回页面都会收到可见性事件。
         * 已加载且刚刷新过时直接复用仓库快照，避免大历史记录反复从 Room 解码。
         */
        fun refresh(force: Boolean = false) {
            val now = System.currentTimeMillis()
            if (!force && historyRepository.loaded.value && now - lastRefreshAt < MIN_REFRESH_INTERVAL_MILLIS) return
            if (refreshJob?.isActive == true) return
            lastRefreshAt = now
            refreshJob =
                viewModelScope.launch(Dispatchers.IO) {
                    runCatching { historyRepository.reload() }
                        .onFailure {
                            lastRefreshAt = 0L
                            appErrorLogger.record("签到记录刷新", it)
                        }
                }
        }

        fun clearHistory() {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    historyRepository.clear()
                } catch (e: Exception) {
                    appErrorLogger.record("签到记录清空", e)
                    appStateRepository.triggerToast("清空失败: ${e.message}")
                }
            }
        }

        fun retryEmail(actionId: String) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val mail = settingsRepository.getMailSettings()
                    if (!mail.enabled || !mail.isConfigured) {
                        appStateRepository.triggerToast("请先在设置中启用并完善邮件配置")
                        return@launch
                    }
                    val action = postRunActionRepository.resetEmailForManualRetry(actionId)
                    postRunActionScheduler.schedule(action)
                    appStateRepository.triggerToast("已重新加入邮件发送队列")
                } catch (e: Exception) {
                    appErrorLogger.record("邮件重新发送", e)
                    appStateRepository.triggerToast("邮件重新发送失败，请稍后重试")
                }
            }
        }

        private companion object {
            const val MIN_REFRESH_INTERVAL_MILLIS = 15_000L
            const val EMAIL_DELIVERY_STOP_TIMEOUT_MS = 5_000L
        }
    }

data class EmailDeliveryUiState(
    val actionId: String,
    val status: String,
    val attemptCount: Int,
)
