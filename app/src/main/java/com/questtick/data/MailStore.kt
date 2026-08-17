package com.questtick.data

import android.content.SharedPreferences
import org.json.JSONObject

/** 邮件配置存储；SMTP 密码由外层加密 SharedPreferences 负责加密。 */
internal class MailStore(
    private val prefs: SharedPreferences,
    private val key: String,
) {
    @Volatile private var cache: MailSettings? = null

    fun get(): MailSettings {
        cache?.let { return it }
        val raw =
            prefs.getString(key, null)
                ?: return MailSettings().also { cache = it }
        return try {
            MailSettings.fromJson(JSONObject(raw)).also { cache = it }
        } catch (_: Exception) {
            MailSettings().also { cache = it }
        }
    }

    fun save(settings: MailSettings) {
        val success =
            prefs.edit()
                .putString(key, settings.toJson().toString())
                .commitSafely(TAG, "save mail settings")
        if (!success) {
            throw IllegalStateException("Failed to save mail settings: encrypted commit returned false")
        }
        cache = settings
    }

    companion object {
        private const val TAG = "MailStore"
    }
}
