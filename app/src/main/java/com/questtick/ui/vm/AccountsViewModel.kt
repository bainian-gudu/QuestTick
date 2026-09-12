package com.questtick.ui.vm

/*
 * 账号列表页状态容器：账号增删改、登录态风险（恢复项）的忽略与恢复、账号启停与列表刷新。
 */
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.questtick.data.Account
import com.questtick.repository.AppStateRepository
import com.questtick.repository.account.AccountRepository
import com.questtick.repository.base.RepositoryLoadState
import com.questtick.repository.run.ExecutionStateRepository
import com.questtick.repository.log.AppErrorLogger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class AccountsViewModel
    @Inject
    constructor(
        private val accountRepository: AccountRepository,
        private val executionStateRepository: ExecutionStateRepository,
        private val appStateRepository: AppStateRepository,
        private val appErrorLogger: AppErrorLogger,
    ) : ViewModel() {
        val accounts: StateFlow<List<Account>> = accountRepository.accounts
        val loadState: StateFlow<RepositoryLoadState> = accountRepository.loadState
        val recoveryIssues = accountRepository.recoveryIssues

        private val _executionGuards = MutableStateFlow<Map<String, ExecutionStateRepository.AccountGuard>>(emptyMap())
        val executionGuards = _executionGuards.asStateFlow()

        fun refresh() {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    accountRepository.reload()
                    _executionGuards.value = executionStateRepository.activeAccountGuards().associateBy { it.accountId }
                } catch (e: Exception) {
                    appErrorLogger.record("账号列表刷新", e)
                    // loadState 已携带可恢复错误，页面提供重试入口。
                }
            }
        }

        fun newAccountTemplate(): Account = Account(id = UUID.randomUUID().toString(), label = "新账号")

        fun deleteAccount(id: String) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    accountRepository.delete(id)
                } catch (e: Exception) {
                    appErrorLogger.record("账号删除", e)
                    appStateRepository.triggerToast("删除失败")
                }
            }
        }

        fun deleteRecoveryIssue(accountId: String) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    accountRepository.deleteRecoveryIssue(accountId)
                    appStateRepository.triggerToast("已删除无法恢复的账号数据")
                } catch (e: Exception) {
                    appErrorLogger.record("损坏账号数据删除", e)
                    appStateRepository.triggerToast("删除损坏账号数据失败")
                }
            }
        }

        fun resumeAccount(accountId: String) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    executionStateRepository.resumeAccount(accountId)
                    _executionGuards.value = _executionGuards.value - accountId
                    appStateRepository.triggerToast("已恢复该账号的后台签到")
                } catch (e: Exception) {
                    appErrorLogger.record("账号后台签到恢复", e)
                    appStateRepository.triggerToast("恢复后台签到失败")
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
                    appErrorLogger.record("账号开关", e)
                    appStateRepository.triggerToast("操作失败")
                }
            }
        }
    }
