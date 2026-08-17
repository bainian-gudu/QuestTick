package com.questtick.security

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/** Root 检测结果缓存（内存 + 本地持久化），有效期 10 分钟。 */
object RootCheckCache {
    private const val TAG = "RootCheckCache"
    private const val PREFS_NAME = "signin_root_check_cache"
    private const val KEY_RESULT = "result_json"
    private const val KEY_TIME = "cached_time"
    private const val CACHE_VALID_MS = 10 * 60 * 1000L

    fun get(context: Context): RootDetectorV2.RootCheckResult? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_RESULT, null) ?: return null
        val cachedTime = prefs.getLong(KEY_TIME, 0)
        if (System.currentTimeMillis() - cachedTime > CACHE_VALID_MS) return null

        return try {
            parseFromJson(jsonStr)
        } catch (e: Exception) {
            Log.w(TAG, "Root 检测结果缓存解析失败，将重新检测", e)
            null
        }
    }

    fun save(
        context: Context,
        result: RootDetectorV2.RootCheckResult,
    ) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_RESULT, toJson(result))
            .putLong(KEY_TIME, System.currentTimeMillis())
            .apply()
    }

    fun checkWithCache(context: Context): RootDetectorV2.RootCheckResult {
        get(context)?.let { return it }
        return RootDetectorV2.check(context).also { save(context, it) }
    }

    fun forceCheck(context: Context): RootDetectorV2.RootCheckResult =
        RootDetectorV2.check(context).also { save(context, it) }

    private fun toJson(result: RootDetectorV2.RootCheckResult): String =
        JSONObject().apply {
            put("isRooted", result.isRooted)
            put("score", result.score)
            put("level", result.level.name)
            put("isEmulator", result.isEmulator)
            put("completeness", result.completeness.name)
            put("checkedProbeCount", result.checkedProbeCount)
            put("unavailableProbes", JSONArray().apply { result.unavailableProbes.forEach(::put) })
            put("rootEvidenceTriggers", JSONArray().apply { result.rootEvidenceTriggers.forEach(::put) })
            put("triggers", JSONArray().apply { result.triggers.forEach(::put) })
        }.toString()

    private fun parseFromJson(jsonStr: String): RootDetectorV2.RootCheckResult {
        val json = JSONObject(jsonStr)
        val triggers = json.getJSONArray("triggers").toStringList()
        val rootEvidenceTriggers =
            json.getJSONArray("rootEvidenceTriggers")
                .toStringList()
                .filter(RootDetectorV2::isRootEvidenceTrigger)
                .distinct()
        return RootDetectorV2.RootCheckResult(
            isRooted = rootEvidenceTriggers.isNotEmpty(),
            score = json.getInt("score"),
            triggers = triggers,
            level = RootDetectorV2.RiskLevel.valueOf(json.getString("level")),
            rootEvidenceTriggers = rootEvidenceTriggers,
            isEmulator = json.getBoolean("isEmulator"),
            completeness = RootDetectorV2.CheckCompleteness.valueOf(json.getString("completeness")),
            checkedProbeCount = json.getInt("checkedProbeCount"),
            unavailableProbes = json.getJSONArray("unavailableProbes").toStringList().distinct(),
        )
    }

    private fun JSONArray.toStringList(): List<String> =
        List(length()) { index -> getString(index) }
}
