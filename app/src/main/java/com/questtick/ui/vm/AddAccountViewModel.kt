package com.questtick.ui.vm

import androidx.lifecycle.ViewModel
import com.questtick.sign.MysCoinCheckIn
import androidx.lifecycle.viewModelScope
import com.questtick.data.Account
import com.questtick.data.Games
import com.questtick.repository.auth.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

/**
 * 添加 / 编辑账号页的状态与校验逻辑。
 *
 * 将输入状态、游戏选择、保存校验与 Cookie 解析从 Composable 中移出，降低弹层首帧组合成本。
 */
@HiltViewModel
class AddAccountViewModel
    @Inject
    constructor(
        private val authRepository: AuthRepository,
    ) : ViewModel() {
        companion object {
            private const val COOKIE_VALIDATION_DEBOUNCE_MS = 350L
        }

        internal var backgroundDispatcher: CoroutineDispatcher = Dispatchers.IO
        internal var cookieValidationDebounceMs: Long = COOKIE_VALIDATION_DEBOUNCE_MS

        private var latestAccount: Account = Account(id = "", label = "")
        private var activeEditorSessionKey: Long? = null
        private var cookieValidationJob: Job? = null
        private val cookieValidationVersion = AtomicLong(0L)

        private val _uiState = MutableStateFlow(AddAccountUiState.from(latestAccount))
        val uiState: StateFlow<AddAccountUiState> = _uiState

        /** 扫码登录、Cookie 刷新或云游戏扫码回写后，同步外部最新账号数据。 */
        fun syncInitial(
            account: Account,
            editorSessionKey: Long = 0L,
        ) {
            val previousAccount = latestAccount
            val sameEditorSession =
                activeEditorSessionKey == editorSessionKey &&
                    previousAccount.id.isNotBlank() &&
                    previousAccount.id == account.id
            activeEditorSessionKey = editorSessionKey
            latestAccount = account
            _uiState.update { current ->
                val fresh = AddAccountUiState.from(account)
                if (sameEditorSession) {
                    mergeExternalAccountUpdate(current, AddAccountUiState.from(previousAccount), fresh)
                } else {
                    fresh.copy(hydrated = true)
                }
            }
            validateCookieAsync(_uiState.value.cookie, debounce = false)
        }

        private fun mergeExternalAccountUpdate(
            current: AddAccountUiState,
            previous: AddAccountUiState,
            fresh: AddAccountUiState,
        ): AddAccountUiState =
            fresh.copy(
                label = if (current.label != previous.label) current.label else fresh.label,
                cookie = if (current.cookie != previous.cookie) current.cookie else fresh.cookie,
                genshin = if (current.genshin != previous.genshin) current.genshin else fresh.genshin,
                starrail = if (current.starrail != previous.starrail) current.starrail else fresh.starrail,
                selectedGames =
                    if (current.selectedGames != previous.selectedGames) {
                        current.selectedGames
                    } else {
                        fresh.selectedGames
                    },
                mysCoinEnabled =
                    if (current.mysCoinEnabled != previous.mysCoinEnabled) {
                        current.mysCoinEnabled
                    } else {
                        fresh.mysCoinEnabled
                    },
                tab = current.tab,
                hydrated = true,
                cookieWarning = current.cookieWarning,
                saveError = current.saveError,
            )

        fun resetEditorState(editorSessionKey: Long? = null) {
            if (editorSessionKey != null && activeEditorSessionKey != editorSessionKey) {
                return
            }
            cookieValidationJob?.cancel()
            cookieValidationJob = null
            cookieValidationVersion.incrementAndGet()
            activeEditorSessionKey = null
            latestAccount = Account(id = "", label = "")
            _uiState.value = AddAccountUiState.from(latestAccount)
        }

        fun setHydrated() {
            _uiState.update { it.copy(hydrated = true) }
        }

        fun setTab(tab: Int) {
            _uiState.update { it.copy(tab = tab.coerceIn(0, 2)) }
        }

        fun updateLabel(value: String) {
            _uiState.update { it.copy(label = value) }
        }

        fun updateCookie(value: String) {
            _uiState.update { it.copy(cookie = value) }
            validateCookieAsync(value)
        }

        fun updateGenshinToken(value: String) {
            _uiState.update { it.copy(genshin = value) }
        }

        fun updateStarrailToken(value: String) {
            _uiState.update { it.copy(starrail = value) }
        }

        fun toggleGame(key: String) {
            _uiState.update { state ->
                val next =
                    state.selectedGames.toMutableSet().apply {
                        if (contains(key)) remove(key) else add(key)
                    }
                state.copy(selectedGames = next)
            }
        }

        fun setMysCoinEnabled(enabled: Boolean) {
            if (!MysCoinCheckIn.featureEnabled) return
            _uiState.update { it.copy(mysCoinEnabled = enabled) }
        }

        fun dismissSaveError() {
            _uiState.update { it.copy(saveError = null) }
        }

        /** 异步校验 Cookie，并把结果写回 UI 状态。 */
        private fun validateCookieAsync(
            cookie: String,
            debounce: Boolean = true,
        ) {
            cookieValidationJob?.cancel()
            val validationVersion = cookieValidationVersion.incrementAndGet()
            val c = cookie.trim()
            if (c.isEmpty()) {
                cookieValidationJob = null
                _uiState.update { it.copy(cookieWarning = null) }
                return
            }

            cookieValidationJob =
                viewModelScope.launch(backgroundDispatcher) {
                    if (debounce) delay(cookieValidationDebounceMs)
                    val accountSnapshot = latestAccount.copy(mysCookie = c)
                    val health = authRepository.checkCookieHealth(accountSnapshot)
                    val warning =
                        if (health.state == AuthRepository.CredentialState.VALID) {
                            null
                        } else {
                            "⚠️ ${health.reason}，签到可能失败"
                        }
                    _uiState.update { state ->
                        if (cookieValidationVersion.get() == validationVersion && state.cookie.trim() == c) {
                            state.copy(cookieWarning = warning)
                        } else {
                            state
                        }
                    }
                }
        }

        /** 在 IO 协程中完成解析与校验，成功后回到主线程执行保存回调。 */
        fun saveAccount(onSuccess: (Account) -> Unit) {
            viewModelScope.launch(backgroundDispatcher) {
                val state = _uiState.value
                val cookieTrimmed = state.cookie.trim()
                val genshinTrimmed = state.genshin.trim()
                val starrailTrimmed = state.starrail.trim()
                val selectedSet = state.selectedGames

                val hasPreservedKeepLogin =
                    latestAccount.hasMysKeepLogin ||
                        latestAccount.hasGenshinCloudKeepLogin ||
                        latestAccount.hasStarrailCloudKeepLogin

                // 表单验证。
                if (cookieTrimmed.isBlank() &&
                    genshinTrimmed.isBlank() &&
                    starrailTrimmed.isBlank() &&
                    !hasPreservedKeepLogin
                ) {
                    _uiState.update { it.copy(saveError = "请至少填写米游社 Cookie、云游戏 Token 或保留扫码登录凭证中的一项") }
                    return@launch
                }
                if (selectedSet.isEmpty()) {
                    _uiState.update { it.copy(saveError = "请至少选择一个需要签到的游戏") }
                    return@launch
                }

                _uiState.update { it.copy(saveError = null) }
                val updated =
                    latestAccount.copy(
                        label = state.label.trim().ifEmpty { "未命名账号" },
                        mysCookie = cookieTrimmed,
                        genshinToken = genshinTrimmed,
                        starrailToken = starrailTrimmed,
                        genshinCloudQrLoginBound = latestAccount.hasGenshinCloudKeepLogin,
                        starrailCloudQrLoginBound = latestAccount.hasStarrailCloudKeepLogin,
                        selectedGames = selectedSet,
                        mysCoinEnabled = state.mysCoinEnabled,
                    )

                withContext(Dispatchers.Main) {
                    onSuccess(updated)
                }
            }
        }
    }

data class AddAccountUiState(
    val label: String = "",
    val cookie: String = "",
    val genshin: String = "",
    val starrail: String = "",
    val selectedGames: Set<String> = Games.ALL_KEYS,
    val mysCoinEnabled: Boolean = false,
    val tab: Int = 0,
    val hydrated: Boolean = false,
    val saveError: String? = null,
    val cookieWarning: String? = null,
) {
    fun hasFormChangesFrom(account: Account): Boolean {
        val baseline = AddAccountUiState.from(account)
        return label != baseline.label ||
            cookie != baseline.cookie ||
            genshin != baseline.genshin ||
            starrail != baseline.starrail ||
            selectedGames != baseline.selectedGames ||
            mysCoinEnabled != baseline.mysCoinEnabled
    }

    companion object {
        fun from(account: Account): AddAccountUiState =
            AddAccountUiState(
                label = account.label.let { if (it == "新账号") "" else it },
                cookie = account.mysCookie,
                genshin = account.genshinToken,
                starrail = account.starrailToken,
                selectedGames = account.selectedGames,
                mysCoinEnabled = account.mysCoinEnabled,
                hydrated = false,
            )
    }
}
