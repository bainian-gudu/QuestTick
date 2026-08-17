package com.questtick.data

import android.content.SharedPreferences
import com.questtick.i18n.normalizeAppLanguage

/** 非敏感应用设置存储。 */
internal class SettingsStore(
    private val prefs: SharedPreferences,
) {
    @Volatile private var cache: AppSettings? = null

    fun get(): AppSettings {
        cache?.let { return it }
        val result =
            AppSettings(
                scheduleEnabled = prefs.getBoolean("scheduleEnabled", false),
                scheduleHour = prefs.getInt("scheduleHour", 8),
                scheduleMinute = prefs.getInt("scheduleMinute", 0),
                notifyEnabled = prefs.getBoolean("notifyEnabled", true),
                appThemeMode = prefs.getString("appThemeMode", "SYSTEM").orEmpty().normalizeThemeMode(),
                oledPureBlackEnabled = prefs.getBoolean("oledPureBlackEnabled", false),
                dynamicColorEnabled = prefs.getBoolean("dynamicColorEnabled", false),
                appLanguage = prefs.getString("appLanguage", "SYSTEM").orEmpty().normalizeAppLanguage(),
                mysDeviceId = prefs.getString("mysDeviceIdUser", "").orEmpty(),
                cloudDeviceId = prefs.getString("cloudDeviceIdUser", "").orEmpty(),
                cloudBackupDeviceId = prefs.getString("cloudBackupDeviceIdUser", "").orEmpty(),
                actIdAutoRefresh = prefs.getBoolean("actIdAutoRefresh", false),
                mysAppVersion = prefs.getString("mysAppVersion", "").orEmpty(),
                mysAppVersionAutoFetch = prefs.getBoolean("mysAppVersionAutoFetch", false),
                parallelEnabled = prefs.getBoolean("parallelEnabled", false),
                appUpdateAutoCheck = prefs.getBoolean("appUpdateAutoCheck", true),
                appUpdateSource = prefs.getString("appUpdateSource", "AUTO").orEmpty().normalizeUpdateSource(),
                debugLoggingEnabled = prefs.getBoolean("debugLoggingEnabled", false),
                cloudYsVersion = prefs.getString("cloudYsVersion", "").orEmpty(),
                cloudSrVersion = prefs.getString("cloudSrVersion", "").orEmpty(),
                // 产品意图：云游戏版本自动获取默认关闭，避免首次安装即向官网发起大量探测请求。
                cloudVersionAutoFetch = prefs.getBoolean("cloudVersionAutoFetch", false),
            )
        cache = result
        return result
    }

    fun save(settings: AppSettings) {
        val success =
            prefs.edit()
                .putBoolean("scheduleEnabled", settings.scheduleEnabled)
                .putInt("scheduleHour", settings.scheduleHour)
                .putInt("scheduleMinute", settings.scheduleMinute)
                .putBoolean("notifyEnabled", settings.notifyEnabled)
                .putString("appThemeMode", settings.appThemeMode.normalizeThemeMode())
                .putBoolean("oledPureBlackEnabled", settings.oledPureBlackEnabled)
                .putBoolean("dynamicColorEnabled", settings.dynamicColorEnabled)
                .putString("appLanguage", settings.appLanguage.normalizeAppLanguage())
                .putString("mysDeviceIdUser", settings.mysDeviceId.trim())
                .putString("cloudDeviceIdUser", settings.cloudDeviceId.trim())
                .putString("cloudBackupDeviceIdUser", settings.cloudBackupDeviceId.trim())
                .putBoolean("actIdAutoRefresh", settings.actIdAutoRefresh)
                .putString("mysAppVersion", settings.mysAppVersion.trim())
                .putBoolean("mysAppVersionAutoFetch", settings.mysAppVersionAutoFetch)
                .putBoolean("parallelEnabled", settings.parallelEnabled)
                .putBoolean("appUpdateAutoCheck", settings.appUpdateAutoCheck)
                .putString("appUpdateSource", settings.appUpdateSource.normalizeUpdateSource())
                .putBoolean("debugLoggingEnabled", settings.debugLoggingEnabled)
                .putString("cloudYsVersion", settings.cloudYsVersion.trim())
                .putString("cloudSrVersion", settings.cloudSrVersion.trim())
                .putBoolean("cloudVersionAutoFetch", settings.cloudVersionAutoFetch)
                .commitSafely(TAG, "save app settings")
        if (!success) {
            throw IllegalStateException("Failed to save app settings: plain commit returned false")
        }
        cache = settings
    }

    companion object {
        private const val TAG = "SettingsStore"

        private fun String.normalizeThemeMode(): String =
            when (uppercase()) {
                "LIGHT", "DARK" -> uppercase()
                else -> "SYSTEM"
            }

        private fun String.normalizeUpdateSource(): String =
            when (uppercase()) {
                "DIRECT", "GHFAST", "GHPROXY" -> uppercase()
                else -> "AUTO"
            }
    }
}
