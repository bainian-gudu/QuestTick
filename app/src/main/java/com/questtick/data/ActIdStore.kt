package com.questtick.data

import android.content.SharedPreferences
import com.questtick.log.AppLog
import org.json.JSONObject

/** act_id 缓存存储；非敏感，使用明文 SharedPreferences。 */
internal class ActIdStore(
    private val prefs: SharedPreferences,
    private val key: String,
) {
    @Volatile private var cache: MutableMap<String, String>? = null
    private val lock = Any()

    fun get(): MutableMap<String, String> =
        synchronized(lock) {
            cache?.let { return@synchronized it.toMutableMap() }
            val raw =
                prefs.getString(key, null)
                    ?: return@synchronized mutableMapOf<String, String>().also { cache = it }
            return@synchronized try {
                val obj = JSONObject(raw)
                val map = mutableMapOf<String, String>()
                for (name in obj.keys()) {
                    obj.optString(name).takeIf { it.isNotBlank() }?.let { map[name] = it }
                }
                map.also { cache = it }
            } catch (_: Exception) {
                mutableMapOf<String, String>().also { cache = it }
            }
        }

    fun save(
        values: Map<String, String>,
        maxEntries: Int = DEFAULT_MAX_ENTRIES,
    ) = synchronized(lock) {
        if (values.isEmpty()) {
            val success = prefs.edit().remove(key).commitSafely(TAG, "clear act_id cache")
            if (!success) {
                AppLog.w(TAG, "Failed to clear act_id cache, keeping existing in-memory cache")
                return@synchronized
            }
            cache = mutableMapOf()
            return@synchronized
        }
        val trimmed =
            if (values.size > maxEntries) {
                values.entries.take(maxEntries).associate { it.key to it.value }
            } else {
                values
            }
        val obj = JSONObject()
        trimmed.forEach { (k, v) -> obj.put(k, v) }
        val success =
            prefs
                .edit()
                .putString(key, obj.toString())
                .commitSafely(TAG, "save act_id cache")
        if (!success) {
            AppLog.w(TAG, "Failed to save act_id cache, keeping existing in-memory cache")
            return@synchronized
        }
        cache = trimmed.toMutableMap()
    }

    companion object {
        private const val TAG = "ActIdStore"
        private const val DEFAULT_MAX_ENTRIES = 20
    }
}
