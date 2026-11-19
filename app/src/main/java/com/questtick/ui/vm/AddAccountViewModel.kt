@file:Suppress(
    "detekt:InjectDispatcher",
    "detekt:TooManyFunctions",
)

package com.questtick.ui.vm

import androidx.lifecycle.ViewModel
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
                zzz = if (current.zzz != previous.zzz) current.zzz else fresh.zzz,
                stoken = if (current.stoken != previous.stoken) current.stoken else fresh.stoken,
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

        fun updateZzzToken(value: String) {
            _uiState.update { it.copy(zzz = value) }
        }

        fun updateStoken(value: String) {
            _uiState.update { it.copy(stoken = value) }
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

        /** 是否允许开启由界面按功能开关决定（见 LocalMysCoinFeatureEnabled），这里只落状态。 */
        fun setMysCoinEnabled(enabled: Boolean) {
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
                val zzzTrimmed = state.zzz.trim()
                val stokenTrimmed = state.stoken.trim()
                val selectedSet = state.selectedGames

                val hasPreservedKeepLogin =
                    latestAccount.hasMysKeepLogin ||
                        latestAccount.hasGenshinCloudKeepLogin ||
                        latestAccount.hasStarrailCloudKeepLogin
                val hasMysCredential = cookieTrimmed.isNotBlank()
                val hasCloudCredential =
                    genshinTrimmed.isNotBlank() || starrailTrimmed.isNotBlank() || zzzTrimmed.isNotBlank()
                val hasAnyCredential = hasMysCredential || hasCloudCredential

                if (looksLikeCookieWithoutStoken(stokenTrimmed)) {
                    _uiState.update {
                        it.copy(
                            saveError =
                                "SToken 里填的是整段 Cookie（且不含 stoken 字段）：Cookie 请填到「米游社 Cookie」，" +
                                    "SToken 只填 stoken / stoken_v2 的值",
                        )
                    }
                    return@launch
                }
                if (!hasAnyCredential && !hasPreservedKeepLogin) {
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
                        zzzToken = zzzTrimmed,
                        // 表单已完成水合时以输入框为准：允许用户清空已保存的 SToken；
                        // 尚未水合（极端竞态）才回退到账号里的旧值，避免误清空。
                        stoken = if (state.hydrated) stokenTrimmed else latestAccount.stoken,
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

/** 判断输入是否是「像 Cookie 但里面没有 stoken」的内容，用于保存前拦截。 */
private fun looksLikeCookieWithoutStoken(value: String): Boolean {
    if (value.isBlank() || !value.contains(';')) return false
    val keys =
        value
            .split(';')
            .mapNotNull { part ->
                val index = part.indexOf('=')
                if (index > 0) part.substring(0, index).trim().lowercase() else null
            }.toSet()
    val looksLikeCookie = keys.any { it in COOKIE_FIELD_NAMES }
    val hasStoken = keys.contains("stoken") || keys.contains("stoken_v2")
    return looksLikeCookie && !hasStoken
}

private val COOKIE_FIELD_NAMES =
    setOf("ltuid", "ltuid_v2", "account_id", "account_id_v2", "cookie_token", "cookie_token_v2", "ltoken", "ltoken_v2")

data class AddAccountUiState(
    val label: String = "",
    val cookie: String = "",
    val genshin: String = "",
    val starrail: String = "",
    val zzz: String = "",
    /** 手动填写的米游社 SToken；扫码登录拿不到可用 App 登录态时用它兜底。 */
    val stoken: String = "",
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
            zzz != baseline.zzz ||
            stoken != baseline.stoken ||
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
                zzz = account.zzzToken,
                stoken = account.stoken,
                selectedGames = account.selectedGames,
                mysCoinEnabled = account.mysCoinEnabled,
                hydrated = false,
            )
    }
}
