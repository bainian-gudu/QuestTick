package com.questtick.i18n

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.compositionLocalOf
import java.util.Locale

/** 用户可选语言。SYSTEM 只在未手动指定或用户明确选择“跟随系统”时使用。 */
enum class AppLanguage(
    val settingValue: String,
    val localeTag: String?,
) {
    SYSTEM("SYSTEM", null),
    SIMPLIFIED_CHINESE("ZH_CN", "zh-CN"),
    TRADITIONAL_CHINESE("ZH_TW", "zh-Hant-TW"),
    ENGLISH("EN", "en"),
    JAPANESE("JA", "ja"),
    KOREAN("KO", "ko"),
    ;

    companion object {
        fun fromSetting(value: String): AppLanguage = entries.firstOrNull { it.settingValue == value.uppercase(Locale.ROOT) } ?: SYSTEM

        fun resolve(
            settingValue: String,
            deviceLocale: Locale = Locale.getDefault(),
        ): AppLanguage {
            val selected = fromSetting(settingValue)
            if (selected != SYSTEM) return selected
            return fromDeviceLocale(deviceLocale)
        }

        fun fromDeviceLocale(locale: Locale): AppLanguage =
            when (locale.language.lowercase(Locale.ROOT)) {
                "zh" -> {
                    val script = locale.script
                    val country = locale.country.uppercase(Locale.ROOT)
                    if (script.equals("Hant", ignoreCase = true) || country in setOf("TW", "HK", "MO")) {
                        TRADITIONAL_CHINESE
                    } else {
                        SIMPLIFIED_CHINESE
                    }
                }
                "en" -> ENGLISH
                "ja" -> JAPANESE
                "ko" -> KOREAN
                else -> ENGLISH
            }
    }
}

val LocalAppLanguageSetting = compositionLocalOf { AppLanguage.SYSTEM.settingValue }

object AppLocaleController {
    private const val PREFERENCES_FILE = "signin_preferences"
    private const val KEY_LANGUAGE = "appLanguage"

    fun setting(context: Context): String =
        context
            .getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE)
            .getString(KEY_LANGUAGE, AppLanguage.SYSTEM.settingValue)
            .orEmpty()
            .normalizeAppLanguage()

    fun resolved(context: Context): AppLanguage = AppLanguage.resolve(setting(context), systemLocale(context))

    fun wrap(context: Context): Context {
        val language = resolved(context)
        val locale = Locale.forLanguageTag(language.localeTag ?: "en")
        // 仅设置当前应用进程的语言环境，不修改设备语言或时区。
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return context.createConfigurationContext(config)
    }

    fun systemLocale(context: Context): Locale {
        val locales = context.resources.configuration.locales
        return if (!locales.isEmpty) locales[0] else Locale.getDefault()
    }
}

fun String.normalizeAppLanguage(): String = AppLanguage.fromSetting(this).settingValue
