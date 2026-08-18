package com.questtick.repository.settings

import com.questtick.data.AppSettings
import com.questtick.data.MailSettings
import com.questtick.data.SecureStore
import com.questtick.repository.base.StatefulRepository
import com.questtick.work.Scheduler
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** 设置仓库：负责应用配置、通知配置与邮件配置。 */
@Singleton
class SettingsRepository
    @Inject
    constructor(
        private val store: SecureStore,
        private val scheduler: Scheduler,
    ) : StatefulRepository<AppSettings>(AppSettings()) {
        private val _mail = kotlinx.coroutines.flow.MutableStateFlow(MailSettings())
        val mail: StateFlow<MailSettings> = _mail

        private val _mailLoaded = kotlinx.coroutines.flow.MutableStateFlow(false)
        val mailLoaded: StateFlow<Boolean> = _mailLoaded

        val settings: StateFlow<AppSettings> = state

        init {
            scope.launch {
                runCatching { reloadMail() }
            }
        }

        override suspend fun loadFromStore(): AppSettings {
            return withContext(kotlinx.coroutines.Dispatchers.IO) {
                store.getAppSettings()
            }
        }

        override suspend fun saveToStore(data: AppSettings) {
            withContext(kotlinx.coroutines.Dispatchers.IO) {
                val previous = getCurrentState()
                store.saveAppSettings(data)
                val scheduleChanged =
                    previous.scheduleEnabled != data.scheduleEnabled ||
                        previous.scheduleHour != data.scheduleHour ||
                        previous.scheduleMinute != data.scheduleMinute
                if (scheduleChanged) {
                    if (data.scheduleEnabled) {
                        scheduler.schedule(data.scheduleHour, data.scheduleMinute)
                    } else {
                        scheduler.cancel()
                    }
                }
            }
        }

        suspend fun reloadSettings() {
            reload()
        }

        suspend fun reloadMail() {
            _mail.value = withContext(kotlinx.coroutines.Dispatchers.IO) { store.getMailSettings() }
            _mailLoaded.value = true
        }

        // 强制重新加载邮件配置（用于设置页刷新）
        suspend fun forceReloadMail() {
            reloadMail()
        }

        suspend fun getSettings(): AppSettings = getCurrentState()

        suspend fun saveSettings(settings: AppSettings) {
            updateAndPersist { settings }
        }

        suspend fun getMailSettings(): MailSettings =
            withContext(kotlinx.coroutines.Dispatchers.IO) {
                store.getMailSettings()
            }

        suspend fun saveMailSettings(settings: MailSettings) {
            withContext(kotlinx.coroutines.Dispatchers.IO) { store.saveMailSettings(settings) }
            _mail.value = settings
            _mailLoaded.value = true
        }
    }
