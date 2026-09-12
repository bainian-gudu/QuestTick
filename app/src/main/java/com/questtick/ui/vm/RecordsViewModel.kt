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
import com.questtick.work.PostRunActionScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import javax.inject.Inject

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
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyMap())

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
                    if (
                        !mail.enabled ||
                        mail.mailTo.isBlank() ||
                        mail.smtpServer.isBlank() ||
                        mail.username.isBlank() ||
                        mail.password.isBlank()
                    ) {
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
        }
    }

data class EmailDeliveryUiState(
    val actionId: String,
    val status: String,
    val attemptCount: Int,
)
