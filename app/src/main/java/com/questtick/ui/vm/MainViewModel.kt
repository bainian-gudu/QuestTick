package com.questtick.ui.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.questtick.data.Account
import com.questtick.data.AppSettings
import com.questtick.data.SecureStore
import com.questtick.repository.AppStateRepository
import com.questtick.repository.account.AccountRepository
import com.questtick.repository.auth.AuthRepository
import com.questtick.repository.log.AppErrorLogger
import com.questtick.repository.settings.SettingsRepository
import com.questtick.sign.CredentialFieldSanitizer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject

/**
 * 主 ViewModel，负责根级状态、账号编辑登录操作和全局 Toast 委托。
 *
 * 账号保存、启停、退出登录和 Cookie 刷新路径已纳入 Baseline Profile；修改后请同步检查规则。
 */
@HiltViewModel
class MainViewModel
    @Inject
    constructor(
        private val appStateRepository: AppStateRepository,
        private val accountRepository: AccountRepository,
        private val authRepository: AuthRepository,
        private val settingsRepository: SettingsRepository,
        private val secureStore: SecureStore,
        private val appErrorLogger: AppErrorLogger,
    ) : ViewModel() {
        val ready: StateFlow<Boolean> = appStateRepository.ready
        val toast: StateFlow<String?> = appStateRepository.toast
        val accounts: StateFlow<List<Account>> = accountRepository.accounts

        val settings: StateFlow<AppSettings> = settingsRepository.settings

        fun consumeToast() {
            appStateRepository.consumeToast()
        }

        fun showToast(msg: String) {
            appStateRepository.triggerToast(msg)
        }

        /** 供非 ViewModel 的登录/资源流程复用应用级原始错误日志入口。 */
        fun recordApplicationError(
            feature: String,
            detail: String,
        ) {
            appErrorLogger.record(feature, IllegalStateException(detail), detail)
        }

        fun newAccountTemplate(): Account = Account(id = UUID.randomUUID().toString(), label = "新账号")

        fun saveAccount(account: Account) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val saved = saveEditableAccount(account)
                    ensureDeviceIdsFor(saved)
                } catch (e: Exception) {
                    appErrorLogger.record("账号保存", e)
                    appStateRepository.triggerToast("保存失败")
                }
            }
        }

        private suspend fun saveEditableAccount(account: Account): Account {
            val sanitized = sanitizeEditableCredentials(account)
            val updated =
                accountRepository.update(account.id) { latest ->
                    mergeEditableAccount(latest, sanitized)
                }
            if (updated != null) return updated
            accountRepository.upsert(sanitized)
            return sanitized
        }

        private fun sanitizeEditableCredentials(account: Account): Account =
            account.copy(
                mysCookie = CredentialFieldSanitizer.mysSigninCookie(account.mysCookie),
                genshinToken = CredentialFieldSanitizer.cloudSigninToken(account.genshinToken),
                starrailToken = CredentialFieldSanitizer.cloudSigninToken(account.starrailToken),
            )

        private fun ensureDeviceIdsFor(account: Account) {
            // 只有存在对应 Cookie / Token 或保持登录凭证时才生成设备 ID。
            if (account.mysCookie.isNotBlank() || account.hasMysKeepLogin) {
                secureStore.effectiveMysDeviceId(forceCreate = true)
            }
            if (account.genshinToken.isNotBlank() ||
                account.starrailToken.isNotBlank() ||
                account.hasGenshinCloudKeepLogin ||
                account.hasStarrailCloudKeepLogin
            ) {
                secureStore.effectiveCloudDeviceId(forceCreate = true)
            }
        }

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

        fun logoutAccount(
            account: Account,
            clearCookie: Boolean,
            onUpdated: ((Account) -> Unit)? = null,
        ) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val updated =
                        accountRepository.update(account.id) { latest ->
                            latest.copy(
                                mysUid = "",
                                stoken = "",
                                stmid = "",
                                ltoken = "",
                                qrLoginBound = false,
                                mysCookie = if (clearCookie) "" else latest.mysCookie,
                            )
                        }
                    updated?.let { withContext(Dispatchers.Main) { onUpdated?.invoke(it) } }
                } catch (e: Exception) {
                    appErrorLogger.record("米游社退出登录", e)
                    appStateRepository.triggerToast("退出失败")
                }
            }
        }

        /** 将米游社扫码结果合并到编辑草稿；持久化只在用户点击保存时执行。 */
        @Suppress("detekt:LongParameterList")
        fun buildMysQrLoginDraft(
            account: Account,
            cookie: String,
            uid: String,
            stoken: String,
            stmid: String,
            ltoken: String,
            keepLogin: Boolean,
            nickname: String = "",
        ): Account {
            val signinCookie = CredentialFieldSanitizer.mysSigninCookie(cookie)
            return account.copy(
                label = mergeMysNicknameLabel(account.label, nickname),
                mysCookie = signinCookie,
                mysUid = uid,
                stoken = stoken,
                stmid = stmid,
                ltoken = ltoken,
                qrLoginBound = keepLogin,
            )
        }

        private fun mergeMysNicknameLabel(
            currentLabel: String,
            nickname: String,
        ): String {
            val trimmedNickname = nickname.trim()
            if (trimmedNickname.isBlank()) return currentLabel
            return when (currentLabel.trim()) {
                "", "新账号", "未命名账号" -> trimmedNickname
                else -> currentLabel
            }
        }

        fun refreshCookie(
            account: Account,
            onUpdated: ((Account) -> Unit)? = null,
            onFinished: ((Boolean) -> Unit)? = null,
        ) {
            if (!account.hasMysKeepLogin) {
                onFinished?.invoke(false)
                return
            }
            viewModelScope.launch {
                var success = false
                try {
                    val result =
                        withContext(Dispatchers.IO) {
                            authRepository.refreshCookie(account)
                        }
                    if (result != null) {
                        val updated =
                            withContext(Dispatchers.IO) {
                                val saved =
                                    accountRepository.update(account.id) { latest ->
                                        latest.copy(
                                            mysCookie = result.fullCookie,
                                            ltoken = result.ltoken,
                                        )
                                    }
                                // Cookie 刷新成功后确保米游社设备 ID 已存在。
                                secureStore.effectiveMysDeviceId(forceCreate = true)
                                saved
                            }
                        updated?.let { onUpdated?.invoke(it) }
                        success = true
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    appErrorLogger.record("米游社 Cookie 刷新", e)
                    // 刷新页内按钮会展示失败态，这里不再弹出全局提示。
                } finally {
                    onFinished?.invoke(success)
                }
            }
        }

        fun effectiveMysDeviceId(forceCreate: Boolean = true): String = secureStore.effectiveMysDeviceId(forceCreate)

        fun effectiveCloudDeviceId(forceCreate: Boolean = true): String = secureStore.effectiveCloudDeviceId(forceCreate)

        fun persistGeneratedMysDeviceId(id: String) {
            secureStore.persistGeneratedMysDeviceId(id)
        }

        fun persistGeneratedCloudDeviceId(id: String) {
            secureStore.persistGeneratedCloudDeviceId(id)
        }

        /** 将云游戏扫码结果合并到编辑草稿；持久化只在用户点击保存时执行。 */
        fun buildCloudQrLoginDraft(
            account: Account,
            gameKey: String,
            comboToken: String,
            webCookie: String,
            keepLogin: Boolean,
        ): Account =
            mergeCloudQrLogin(
                account,
                gameKey,
                CredentialFieldSanitizer.cloudSigninToken(comboToken),
                webCookie,
                keepLogin,
            )

        fun logoutCloudAccount(
            account: Account,
            gameKey: String,
            onUpdated: ((Account) -> Unit)? = null,
        ) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val updated =
                        accountRepository.update(account.id) { latest ->
                            when (gameKey) {
                                "CloudYS" -> {
                                    latest.copy(
                                        genshinWebCookie = "",
                                        genshinCloudQrLoginBound = false,
                                    )
                                }

                                "CloudSR" -> {
                                    latest.copy(
                                        starrailWebCookie = "",
                                        starrailCloudQrLoginBound = false,
                                    )
                                }

                                else -> {
                                    latest
                                }
                            }
                        }
                    updated?.let { withContext(Dispatchers.Main) { onUpdated?.invoke(it) } }
                } catch (e: Exception) {
                    appErrorLogger.record("云游戏退出登录", e)
                    appStateRepository.triggerToast("退出云游戏登录失败")
                }
            }
        }

        fun refreshCloudToken(
            account: Account,
            gameKey: String,
            onUpdated: ((Account) -> Unit)? = null,
            onFinished: ((Boolean) -> Unit)? = null,
        ) {
            val keepLogin =
                when (gameKey) {
                    "CloudYS" -> account.hasGenshinCloudKeepLogin
                    "CloudSR" -> account.hasStarrailCloudKeepLogin
                    else -> false
                }
            val webCookie =
                when (gameKey) {
                    "CloudYS" -> account.genshinWebCookie
                    "CloudSR" -> account.starrailWebCookie
                    else -> ""
                }
            if (!keepLogin || webCookie.isBlank()) {
                onFinished?.invoke(false)
                return
            }
            viewModelScope.launch {
                var success = false
                try {
                    val deviceId =
                        withContext(Dispatchers.IO) {
                            secureStore.effectiveCloudDeviceId(forceCreate = true)
                        }
                    val token =
                        withContext(Dispatchers.IO) {
                            authRepository.refreshCloudToken(gameKey, webCookie, deviceId).getOrThrow()
                        }
                    val updated =
                        withContext(Dispatchers.IO) {
                            accountRepository.update(account.id) { latest ->
                                when (gameKey) {
                                    "CloudYS" -> {
                                        latest.copy(
                                            genshinToken = CredentialFieldSanitizer.cloudSigninToken(token),
                                            genshinWebCookie = webCookie,
                                            genshinCloudQrLoginBound = true,
                                        )
                                    }

                                    "CloudSR" -> {
                                        latest.copy(
                                            starrailToken = CredentialFieldSanitizer.cloudSigninToken(token),
                                            starrailWebCookie = webCookie,
                                            starrailCloudQrLoginBound = true,
                                        )
                                    }

                                    else -> {
                                        latest
                                    }
                                }
                            }
                        }
                    updated?.let { onUpdated?.invoke(it) }
                    success = updated != null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    appErrorLogger.record("云游戏 Token 刷新", e)
                    // 刷新页内按钮会展示失败态，这里不再弹出全局提示。
                } finally {
                    onFinished?.invoke(success)
                }
            }
        }

        private fun mergeCloudQrLogin(
            account: Account,
            gameKey: String,
            comboToken: String,
            webCookie: String,
            keepLogin: Boolean,
        ): Account =
            when (gameKey) {
                "CloudYS" -> {
                    account.copy(
                        genshinToken = comboToken,
                        genshinWebCookie = if (keepLogin) webCookie else "",
                        genshinCloudQrLoginBound = keepLogin,
                    )
                }

                "CloudSR" -> {
                    account.copy(
                        starrailToken = comboToken,
                        starrailWebCookie = if (keepLogin) webCookie else "",
                        starrailCloudQrLoginBound = keepLogin,
                    )
                }

                else -> {
                    account
                }
            }
    }

internal fun mergeEditableAccount(
    latest: Account,
    edited: Account,
): Account =
    latest.copy(
        label = edited.label,
        mysCookie = edited.mysCookie,
        genshinToken = edited.genshinToken,
        starrailToken = edited.starrailToken,
        genshinWebCookie = edited.genshinWebCookie,
        starrailWebCookie = edited.starrailWebCookie,
        genshinCloudQrLoginBound = edited.genshinCloudQrLoginBound,
        starrailCloudQrLoginBound = edited.starrailCloudQrLoginBound,
        selectedGames = edited.selectedGames,
        mysUid = edited.mysUid,
        stoken = edited.stoken,
        stmid = edited.stmid,
        ltoken = edited.ltoken,
        qrLoginBound = edited.qrLoginBound,
        mysCoinEnabled = edited.mysCoinEnabled,
    )
