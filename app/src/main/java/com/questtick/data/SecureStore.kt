package com.questtick.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 本地持久化门面。
 *
 * 具体职责已拆到 RoomAccountStore / RoomLogStore / RoomHistoryStore / SettingsStore / MailStore / ActIdStore。
 * 本类只负责创建加密与明文 SharedPreferences 以及设备 ID 管理，降低单个类的维护风险。
 *
 * Cookie、云游戏 Token、SMTP 密码等敏感字段使用 Keystore AES-256-GCM 加密；
 * 账号、运行历史与日志保存在 Room，日志保存前会再次脱敏并按生命周期裁剪。
 */
@Singleton
class SecureStore
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) {
        private val appContext = context.applicationContext

        private val securePrefs: SharedPreferences by lazy { createSecurePrefs() }
        private val plainPrefs: SharedPreferences by lazy {
            appContext.getSharedPreferences(PLAIN_FILE, Context.MODE_PRIVATE)
        }

        private val accountStore: RoomAccountStore by lazy { RoomAccountStore(appContext) }
        private val mailStore: MailStore by lazy { MailStore(securePrefs, KEY_MAIL) }
        private val settingsStore: SettingsStore by lazy { SettingsStore(plainPrefs) }
        private val actIdStore: ActIdStore by lazy { ActIdStore(plainPrefs, KEY_ACT_IDS) }
        private val logStore: RoomLogStore by lazy { RoomLogStore(appContext) }
        private val historyStore: RoomHistoryStore by lazy { RoomHistoryStore(appContext) }
        private val signInCalendarStore: SignInCalendarStore by lazy {
            SignInCalendarStore(appContext)
        }
        private val signInGuardStore: SignInGuardStore by lazy { SignInGuardStore(plainPrefs) }

        private fun createSecurePrefs(): SharedPreferences =
            KeystoreEncryptedSharedPreferences(appContext, SECURE_FILE)

        // 账号数据（加密存储）。

        fun getAccounts(): List<Account> = accountStore.get()

        fun getAccountRecoveryIssues(): List<AccountRecoveryIssue> = accountStore.getRecoveryIssues()

        fun upsertAccount(account: Account) = accountStore.upsert(account)

        /** 基于最新账号快照执行原子更新，防止自动刷新覆盖用户刚保存的其它字段。 */
        fun updateAccount(
            id: String,
            transform: (Account) -> Account,
        ): Account? = accountStore.update(id, transform)

        fun deleteAccount(id: String) = accountStore.delete(id)

        // 邮件设置（包含 SMTP 密码，使用加密存储）。

        fun getMailSettings(): MailSettings = mailStore.get()

        fun saveMailSettings(settings: MailSettings) = mailStore.save(settings)

        // 应用设置（非敏感，明文存储）。

        fun getAppSettings(): AppSettings = settingsStore.get()

        fun saveAppSettings(settings: AppSettings) = settingsStore.save(settings)

        // act_id 缓存（非敏感，明文存储）。

        fun getActIdCache(): MutableMap<String, String> = actIdStore.get()

        fun saveActIdCache(
            cache: Map<String, String>,
            maxEntries: Int = 20,
        ) = actIdStore.save(cache, maxEntries)

        // 设备 ID。

        fun effectiveMysDeviceId(forceCreate: Boolean = true): String {
            val user = plainPrefs.getString("mysDeviceIdUser", "").orEmpty()
            if (user.isNotBlank()) return user
            plainPrefs.getString("mysDeviceId", null)?.takeIf { it.isNotBlank() }?.let { return it }
            return if (forceCreate) getOrCreateDeviceId("mysDeviceId") else ""
        }

        fun effectiveCloudDeviceId(forceCreate: Boolean = true): String {
            val user =
                plainPrefs.getString("cloudDeviceIdUser", "").orEmpty().ifBlank {
                    plainPrefs.getString("cloudBackupDeviceIdUser", "").orEmpty()
                }
            if (user.isNotBlank()) return user
            plainPrefs.getString("cloudDeviceId", null)?.takeIf { it.isNotBlank() }?.let { return it }
            return if (forceCreate) getOrCreateDeviceId("cloudDeviceId") else ""
        }

        fun persistGeneratedMysDeviceId(id: String) {
            persistGeneratedDeviceId("mysDeviceId", id)
        }

        fun persistGeneratedCloudDeviceId(id: String) {
            persistGeneratedDeviceId("cloudDeviceId", id)
        }

        private fun persistGeneratedDeviceId(
            key: String,
            id: String,
        ) {
            val trimmed = id.trim()
            if (trimmed.isBlank()) return
            if (plainPrefs.getString(key, null).isNullOrBlank()) {
                val success =
                    plainPrefs.edit()
                        .putString(key, trimmed)
                        // 生成 ID 视为当前设置值，便于设置页和后续运行直接回显。
                        .putString("${key}User", trimmed)
                        .commitSafely(TAG, "persist device id")
                if (!success) {
                    Log.w(TAG, "Failed to persist device id for key=$key")
                } else {
                    val current = settingsStore.get()
                    when (key) {
                        "mysDeviceId" -> settingsStore.save(current.copy(mysDeviceId = trimmed))
                        "cloudDeviceId" -> settingsStore.save(current.copy(cloudDeviceId = trimmed))
                    }
                }
            }
        }

        private fun getOrCreateDeviceId(key: String): String {
            plainPrefs.getString(key, null)?.takeIf { it.isNotBlank() }?.let { return it }
            val id = UUID.randomUUID().toString()
            persistGeneratedDeviceId(key, id)
            return id
        }

        // 运行日志（已脱敏，明文存储）。

        fun getLogs(): List<LogEntry> = logStore.get()

        fun saveLogs(
            logs: List<LogEntry>,
            maxKeep: Int = RoomLogStore.DEFAULT_MAX_KEEP,
        ) = logStore.save(logs, maxKeep)

        fun appendLogs(
            logs: List<LogEntry>,
            maxKeep: Int = RoomLogStore.DEFAULT_MAX_KEEP,
        ) = logStore.append(logs, maxKeep)

        fun clearLogs() = logStore.clear()

        // 签到历史（已脱敏，明文存储）。

        fun getHistory(): List<RunRecord> = historyStore.get()

        fun addRunRecord(
            record: RunRecord,
            maxKeep: Int = RoomHistoryStore.DEFAULT_MAX_KEEP,
        ) = historyStore.add(record, maxKeep)

        fun clearHistory() = historyStore.clear()

        // 签到日历（独立于运行历史，清空历史不影响日历状态）。

        fun getSignInCalendarDays(): List<SignInCalendarDay> = signInCalendarStore.get()

        fun updateSignInCalendar(record: RunRecord) = signInCalendarStore.updateFromRecord(record)

        fun clearSignInCalendar() = signInCalendarStore.clear()

        // 后台签到保护状态。

        fun getConsecutiveSignInFailures(): Int = signInGuardStore.getConsecutiveFailures()

        fun recordSignInFailure(): Int = signInGuardStore.recordFailure()

        fun resetSignInFailures() = signInGuardStore.resetFailures()

        fun markSignInPaused(reason: String) = signInGuardStore.markPaused(reason)

        fun warmUp() {
            securePrefs.contains("")
            plainPrefs.contains("")
            getAccounts()
            getAppSettings()
        }

        fun warmSecondaryCaches() {
            getHistory()
            getSignInCalendarDays()
            getLogs()
            getMailSettings()
            getActIdCache()
        }

        companion object {
            private const val TAG = "SecureStore"
            private const val SECURE_FILE = "signin_secure"
            private const val PLAIN_FILE = "signin_preferences"
            private const val KEY_MAIL = "mail_settings"
            private const val KEY_ACT_IDS = "act_id_cache"
        }
    }
