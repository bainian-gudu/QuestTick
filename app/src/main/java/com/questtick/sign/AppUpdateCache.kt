package com.questtick.sign

/*
 * 更新检查结果的本地缓存：保存 ETag 与上次检查到的版本，避免重复请求 Release API。
 */

import android.content.Context
import android.content.SharedPreferences
import com.questtick.net.TrustedUrlPolicy
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference

/**
 * 应用更新缓存。
 *
 * 采用“内存优先、磁盘兜底”的读取策略，让更新提示在大多数情况下无需等待网络。
 * 强缓存直接返回，弱缓存先返回再后台刷新，过期缓存仅作为界面兜底使用。
 */
object AppUpdateCache {
    private const val PREF_NAME = "signin_app_update_cache"
    private const val KEY_JSON = "last_update_json"
    private const val KEY_TIMESTAMP = "last_update_ts"
    private const val KEY_ETAG = "last_etag"
    private const val KEY_CURRENT_VERSION = "last_current_version"

    // 强缓存保留 5 分钟，弱缓存保留 60 分钟。
    private const val STRONG_CACHE_MS = 5 * 60 * 1000L
    private const val STALE_CACHE_MS = 60 * 60 * 1000L

    private data class MemoryEntry(
        val info: AppUpdateChecker.AppUpdateInfo,
        val timestamp: Long,
        val etag: String,
    )

    private val memoryCache = AtomicReference<MemoryEntry?>(null)

    /** 缓存读取结果 */
    data class CacheResult(
        val info: AppUpdateChecker.AppUpdateInfo?,
        val ageMs: Long,
        val isStrong: Boolean, // 强缓存，可直接使用
        val isStale: Boolean, // 弱缓存，建议后台刷新
        val etag: String,
    )

    /** 优先读取内存缓存，未命中时退回磁盘缓存，不触发网络请求。 */
    fun getFast(
        context: Context,
        currentVersion: String,
    ): CacheResult {
        val now = System.currentTimeMillis()

        // 1. 内存缓存 0ms
        memoryCache.get()?.let { mem ->
            if (mem.info.hasUpdate && !TrustedUrlPolicy.isUpdateAssetUrl(mem.info.apkUrl)) {
                memoryCache.compareAndSet(mem, null)
            } else if (mem.info.currentVersion == currentVersion) {
                val age = now - mem.timestamp
                return CacheResult(
                    info = mem.info,
                    ageMs = age,
                    isStrong = age < STRONG_CACHE_MS,
                    isStale = age >= STRONG_CACHE_MS,
                    etag = mem.etag,
                )
            }
        }

        // 2. 磁盘缓存 <5ms
        val prefs = prefs(context)
        val jsonStr = prefs.getString(KEY_JSON, null)
        val ts = prefs.getLong(KEY_TIMESTAMP, 0L)
        val etag = prefs.getString(KEY_ETAG, "").orEmpty()
        val cachedVersion = prefs.getString(KEY_CURRENT_VERSION, "")

        if (!jsonStr.isNullOrBlank() && ts > 0) {
            try {
                val obj = JSONObject(jsonStr)
                // 只反序列化更新提示所需字段，避免额外对象构造。
                val cachedLatest = obj.optString("latestVersion", currentVersion)
                val info =
                    AppUpdateChecker.AppUpdateInfo(
                        currentVersion = currentVersion,
                        latestVersion = cachedLatest,
                        tagName = obj.optString("tagName", ""),
                        releaseUrl = obj.optString("releaseUrl", ""),
                        releaseNotes = obj.optString("releaseNotes", ""),
                        publishedAt = obj.optString("publishedAt", ""),
                        apkName = obj.optString("apkName", ""),
                        apkUrl = obj.optString("apkUrl", ""),
                        apkSha256 = obj.optString("apkSha256", ""),
                        // 缓存里的 hasUpdate 是相对于写入时的 currentVersion；读取时必须按当前版本重新计算，
                        // 否则应用版本号变化后会出现“最新版本号已变高但缓存仍认为无需更新”的情况。
                        hasUpdate = isVersionNewerFast(cachedLatest, currentVersion),
                    )
                if (info.hasUpdate && !TrustedUrlPolicy.isUpdateAssetUrl(info.apkUrl)) {
                    clear(context)
                    return CacheResult(null, Long.MAX_VALUE, false, true, "")
                }
                val age = now - ts
                // 读到磁盘缓存后同步回填内存，便于后续快速命中。
                memoryCache.set(MemoryEntry(info, ts, etag))
                return CacheResult(
                    info = info,
                    ageMs = age,
                    isStrong = age < STRONG_CACHE_MS && cachedVersion == currentVersion,
                    isStale = age >= STRONG_CACHE_MS,
                    etag = etag,
                )
            } catch (_: Exception) {
            }
        }

        return CacheResult(null, Long.MAX_VALUE, false, true, "")
    }

    /** 同时更新内存与磁盘缓存；磁盘写入使用 apply 避免阻塞主线程。 */
    fun put(
        context: Context,
        info: AppUpdateChecker.AppUpdateInfo,
        etag: String = "",
    ) {
        if (info.hasUpdate && !TrustedUrlPolicy.isUpdateAssetUrl(info.apkUrl)) return
        val now = System.currentTimeMillis()
        memoryCache.set(MemoryEntry(info, now, etag))
        // 磁盘侧只做轻量落盘，避免影响界面线程。
        try {
            prefs(context)
                .edit()
                .putString(
                    KEY_JSON,
                    JSONObject()
                        .apply {
                            put("latestVersion", info.latestVersion)
                            put("tagName", info.tagName)
                            put("releaseUrl", info.releaseUrl)
                            put("releaseNotes", info.releaseNotes)
                            put("publishedAt", info.publishedAt)
                            put("apkName", info.apkName)
                            put("apkUrl", info.apkUrl)
                            put("apkSha256", info.apkSha256)
                            put("hasUpdate", info.hasUpdate)
                        }.toString(),
                ).putLong(KEY_TIMESTAMP, now)
                .putString(KEY_ETAG, etag)
                .putString(KEY_CURRENT_VERSION, info.currentVersion)
                .apply()
        } catch (_: Exception) {
        }
    }

    fun clear(context: Context) {
        memoryCache.set(null)
        prefs(context).edit().clear().apply()
    }

    fun getEtag(context: Context): String {
        memoryCache.get()?.etag?.let { if (it.isNotBlank()) return it }
        return prefs(context).getString(KEY_ETAG, "").orEmpty()
    }

    private fun prefs(context: Context): SharedPreferences = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    // 轻量版本比较，避免为简单比较引入额外对象分配。
    private fun isVersionNewerFast(
        latest: String,
        current: String,
    ): Boolean {
        if (latest.isBlank() || current.isBlank() || latest == current) return false
        return try {
            compareVersionsFast(latest, current) > 0
        } catch (_: Exception) {
            false
        }
    }

    private fun compareVersionsFast(
        a: String,
        b: String,
    ): Int {
        val left = a.trim().removePrefix("v").split('.', '-', '_')
        val right = b.trim().removePrefix("v").split('.', '-', '_')
        val n = maxOf(left.size, right.size)
        for (i in 0 until n) {
            val l = left.getOrNull(i)?.filter { it.isDigit() }?.toIntOrNull() ?: 0
            val r = right.getOrNull(i)?.filter { it.isDigit() }?.toIntOrNull() ?: 0
            if (l != r) return l - r
        }
        return 0
    }
}
