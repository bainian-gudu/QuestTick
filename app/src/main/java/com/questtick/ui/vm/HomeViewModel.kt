package com.questtick.ui.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.questtick.data.Account
import com.questtick.data.RunRecord
import com.questtick.data.RunTerminationReason
import com.questtick.data.RunTrigger
import com.questtick.data.SignInCalendarDay
import com.questtick.repository.AppStateRepository
import com.questtick.repository.account.AccountRepository
import com.questtick.repository.history.HistoryRepository
import com.questtick.repository.calendar.SignInCalendarRepository
import com.questtick.repository.log.LogRepository
import com.questtick.repository.log.AppErrorLogger
import com.questtick.repository.run.ExecutionStateRepository
import com.questtick.repository.settings.SettingsRepository
import com.questtick.security.RootBlockingPolicy
import com.questtick.security.RootDetectorV2
import com.questtick.security.RootEnvironmentChecker
import com.questtick.sign.ErrorText
import com.questtick.sign.RootBlockMessages
import com.questtick.sign.RunProgressPhase
import com.questtick.sign.RunProgressState
import com.questtick.sign.SignInRunCoordinator
import com.questtick.work.SignInRunnerFactory
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RootBlockWarningUiState(
    val title: String,
    val message: String,
)

/**
 * 首页 ViewModel，提供“立即全部签到”入口。
 *
 * runNow 属于高频热路径，已纳入 Baseline Profile；修改后请同步检查相关规则。
 */
@HiltViewModel
class HomeViewModel
    @Inject
    constructor(
        private val appStateRepository: AppStateRepository,
        private val accountRepository: AccountRepository,
        private val historyRepository: HistoryRepository,
        private val signInCalendarRepository: SignInCalendarRepository,
        private val logRepository: LogRepository,
        private val settingsRepository: SettingsRepository,
        private val executionStateRepository: ExecutionStateRepository,
        private val rootEnvironmentChecker: RootEnvironmentChecker,
        private val runnerFactory: SignInRunnerFactory,
        private val runCoordinator: SignInRunCoordinator,
        private val appErrorLogger: AppErrorLogger,
    ) : ViewModel() {
        val accounts: StateFlow<List<Account>> = accountRepository.accounts
        val history: StateFlow<List<RunRecord>> = historyRepository.history
        val historyLoaded: StateFlow<Boolean> = historyRepository.loaded
        val calendarDays: StateFlow<List<SignInCalendarDay>> = signInCalendarRepository.days
        val calendarLoaded: StateFlow<Boolean> = signInCalendarRepository.loaded

        val running: StateFlow<Boolean> = runCoordinator.running

        val progress: StateFlow<RunProgressState> = runCoordinator.progress

        private val _rootBlockWarning = MutableStateFlow<RootBlockWarningUiState?>(null)
        val rootBlockWarning: StateFlow<RootBlockWarningUiState?> = _rootBlockWarning.asStateFlow()

        fun dismissRootBlockWarning() {
            _rootBlockWarning.value = null
        }

        fun refreshHistory() {
            viewModelScope.launch(Dispatchers.IO) {
                runCatching { historyRepository.reload() }
            }
        }

        fun runNow() {
            val currentAccounts = accountRepository.accounts.value
            if (currentAccounts.isEmpty()) {
                appStateRepository.triggerToast("请先添加账号后再签到")
                return
            }
            val enabledAccounts = currentAccounts.filter { it.enabled }
            if (enabledAccounts.isEmpty()) {
                appStateRepository.triggerToast("没有启用的账号，请先启用账号后再签到")
                return
            }
            val hasRunnableTask =
                enabledAccounts.any { account ->
                    account.mysCookie.isNotBlank() || account.hasMysKeepLogin ||
                        account.genshinToken.isNotBlank() || account.hasGenshinCloudKeepLogin ||
                        account.starrailToken.isNotBlank() || account.hasStarrailCloudKeepLogin
                }
            if (!hasRunnableTask) {
                appStateRepository.triggerToast("启用账号未配置 Cookie 或 Token，请先完善账号信息")
                return
            }
            viewModelScope.launch(Dispatchers.IO) {
                val started =
                    runCoordinator.tryRun {
                        runCoordinator.setProgressPhase(RunProgressPhase.CHECKING, "正在检查运行环境")
                        val rootResult =
                            try {
                                rootEnvironmentChecker.check(forceRefresh = true)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Throwable) {
                                appErrorLogger.record("Root 环境检测", e)
                                null
                            }
                        val rootDecision = RootBlockingPolicy.decide(rootResult)
                        if (rootDecision != RootBlockingPolicy.Decision.ALLOW) {
                            _rootBlockWarning.value = rootBlockWarning(rootDecision)
                        }

                        try {
                            executionStateRepository.clearAccountGuards()
                            val useParallel = settingsRepository.settings.value.parallelEnabled
                            val record =
                                runnerFactory.create(useParallel).runAll(
                                    precheckedRootResult = rootResult,
                                    rootCheckAlreadyPerformed = true,
                                    trigger = RunTrigger.MANUAL,
                                )

                            executionStateRepository.applyRunGuards(record)
                            historyRepository.reload()
                            signInCalendarRepository.reload()
                            logRepository.reload()

                            if (isSecurityBlockedRecord(record)) {
                                return@tryRun
                            }

                            val summary = completionSummary(record)
                            runCoordinator.finishProgress(RunProgressPhase.FINISHED, summary)

                            if (record.total > 0) {
                                appStateRepository.triggerToast(summary)
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            appErrorLogger.record("手动签到", e)
                            val message = "签到失败：${friendlyActionError(e)}"
                            runCoordinator.finishProgress(RunProgressPhase.FAILED, message)
                            appStateRepository.triggerToast(message)
                        }
                    }
                if (started == null) {
                    appStateRepository.triggerToast("签到正在运行中，请稍后再试")
                }
            }
        }

        fun toggleAccount(
            account: Account,
            enabled: Boolean,
        ) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    accountRepository.update(account.id) { latest -> latest.copy(enabled = enabled) }
                } catch (e: Exception) {
                    appErrorLogger.record("主页账号开关", e)
                    appStateRepository.triggerToast("操作失败")
                }
            }
        }

        private fun rootBlockWarning(decision: RootBlockingPolicy.Decision): RootBlockWarningUiState =
            if (decision == RootBlockingPolicy.Decision.BLOCK_CHECK_FAILED) {
                RootBlockWarningUiState(
                    title = RootBlockMessages.CheckFailedTitle,
                    message = RootBlockMessages.CheckFailedContent,
                )
            } else {
                RootBlockWarningUiState(
                    title = RootBlockMessages.DetectedTitle,
                    message = RootBlockMessages.DetectedContent,
                )
            }

        private fun isSecurityBlockedRecord(record: RunRecord): Boolean =
            record.terminationReason == RunTerminationReason.ROOT_DETECTED ||
                record.terminationReason == RunTerminationReason.ROOT_CHECK_FAILED

        private fun completionSummary(record: RunRecord): String =
            if (record.total <= 0) {
                "没有可执行任务"
            } else if (record.resultUnknown == record.total) {
                "签到结果待确认：为避免重复请求，今日不会自动重试"
            } else if (record.skipped == record.total && record.failed == 0) {
                "签到已跳过：${record.results.firstOrNull()?.message ?: "无可执行任务"}"
            } else {
                "签到完成：成功${record.succeeded} 已签${record.alreadySigned} " +
                    "失败${record.failed} 待确认${record.resultUnknown} 跳过${record.skipped}"
            }

        private fun friendlyActionError(e: Throwable?): String =
            when {
                e == null -> "未知错误"
                e is IllegalStateException && !e.message.isNullOrBlank() -> e.message.orEmpty()
                else -> ErrorText.fromException(e)
            }
    }
