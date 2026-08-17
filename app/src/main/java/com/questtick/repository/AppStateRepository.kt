package com.questtick.repository

import android.util.Log
import com.questtick.repository.account.AccountRepository
import com.questtick.repository.history.HistoryRepository
import com.questtick.repository.calendar.SignInCalendarRepository
import com.questtick.repository.log.LogRepository
import com.questtick.repository.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import javax.inject.Inject
import javax.inject.Singleton

/** 全局应用状态与一次性事件仓库。业务数据由各领域 Repository 自行负责。 */
@Singleton
class AppStateRepository
    @Inject
    constructor(
        private val accountRepository: AccountRepository,
        private val historyRepository: HistoryRepository,
        private val signInCalendarRepository: SignInCalendarRepository,
        private val logRepository: LogRepository,
        private val settingsRepository: SettingsRepository,
    ) {
        private val _ready = MutableStateFlow(false)
        val ready: StateFlow<Boolean> = _ready.asStateFlow()

        private val _toast = MutableStateFlow<String?>(null)
        val toast: StateFlow<String?> = _toast.asStateFlow()

        private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        init {
            scope.launch {
                try {
                    supervisorScope {
                        listOf(
                            async { reloadWithRetry("accounts") { accountRepository.reload() } },
                            async { reloadWithRetry("history") { historyRepository.reload() } },
                            async { reloadWithRetry("signInCalendar") { signInCalendarRepository.reload() } },
                            async { reloadWithRetry("logs") { logRepository.reload() } },
                            async { reloadWithRetry("settings") { settingsRepository.reloadSettings() } },
                        ).awaitAll()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Initial repository reload failed", e)
                } finally {
                    _ready.value = true
                }
            }
        }

        private suspend fun reloadWithRetry(
            name: String,
            attempts: Int = INITIAL_RELOAD_ATTEMPTS,
            block: suspend () -> Unit,
        ) {
            var lastError: Throwable? = null
            repeat(attempts.coerceAtLeast(1)) { index ->
                try {
                    block()
                    return
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastError = e
                    if (index < attempts - 1) {
                        delay(INITIAL_RELOAD_RETRY_DELAY_MS * (index + 1))
                    }
                }
            }
            lastError?.let { Log.w(TAG, "Initial $name reload failed after $attempts attempts", it) }
        }

        fun triggerToast(message: String) {
            _toast.value = message
        }

        fun consumeToast() {
            _toast.value = null
        }

        private companion object {
            const val TAG = "AppStateRepository"
            const val INITIAL_RELOAD_ATTEMPTS = 3
            const val INITIAL_RELOAD_RETRY_DELAY_MS = 180L
        }
    }
