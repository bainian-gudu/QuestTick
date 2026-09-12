package com.questtick.sign

import android.content.Context
import com.questtick.log.AppLog
import com.questtick.net.HttpTransport

/**
 * 米游社 App 版本本地缓存仓库。
 *
 * 从 App Store 获取的最新版本会持久化到本地；自动更新每 3 天最多执行一次，
 * 避免每次签到都请求 iTunes 接口。自定义版本（来自设置页）优先级高于缓存版本。
 */
object MysAppVersionRepository {
    private const val TAG = "MysAppVersionRepository"
    private const val SP_NAME = "signin_mys_app_version"
    private const val KEY_VERSION = "current_version"
    private const val KEY_LAST_FETCH_TIME = "last_fetch_time_ms"
    private const val REFRESH_INTERVAL_MS = 3L * 24 * 60 * 60 * 1000

    private val VERSION_PATTERN = Regex("""^\d+(?:\.\d+){1,3}$""")

    @Volatile
    var currentVersion: String = Endpoints.APP_VERSION
        private set

    // 以下两个字段在主线程 initialize() 写入、在后台协程（refreshIfNeeded / persist）读取，
    // 因此需要 @Volatile 建立跨线程可见性。缺少可见性时后台线程可能读到过期的 null 或旧值：
    // persist() 会静默跳过导致新版本丢失，而 lastFetchTimeMs 已在内存中推进，
    // 接下来 3 天内都不会再次刷新——错误会持续一个完整刷新周期。
    @Volatile private var lastFetchTimeMs: Long = 0L

    @Volatile private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
        val sp = context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
        sp.getString(KEY_VERSION, null)?.takeIf { it.isNotBlank() }?.let { currentVersion = it }
        lastFetchTimeMs = sp.getLong(KEY_LAST_FETCH_TIME, 0L)
    }

    /** 返回实际生效版本：自定义版本 > 缓存版本 > 内置默认值。 */
    fun effectiveVersion(customVersion: String): String = customVersion.takeIf { it.isNotBlank() } ?: currentVersion

    /**
     * 若本地缓存已过期（超过 3 天）或从未获取，则请求 App Store 最新版本并持久化；
     * 否则直接返回缓存版本。
     */
    suspend fun refreshIfNeeded(httpTransport: HttpTransport): Result<String> {
        val now = System.currentTimeMillis()
        if (lastFetchTimeMs > 0L && (now - lastFetchTimeMs) < REFRESH_INTERVAL_MS) {
            AppLog.d(TAG, "skip fetch: within three days, currentVersion=$currentVersion")
            return Result.success(currentVersion)
        }

        val result = MysAppVersionFetcher.fetchLatest(httpTransport)
        return result
            .onSuccess { version ->
                update(version)
                AppLog.d(TAG, "fetched and stored: $version")
            }.onFailure { e ->
                // 失败也计入本轮自动获取，避免每次签到都重复请求版本源。
                lastFetchTimeMs = now
                persist(null)
                AppLog.w(TAG, "fetch failed", e)
            }
    }

    /** 强制立即从 App Store 获取最新版本并更新本地缓存。 */
    suspend fun forceRefresh(httpTransport: HttpTransport): Result<String> =
        MysAppVersionFetcher
            .fetchLatest(httpTransport)
            .onSuccess { version ->
                update(version)
                AppLog.d(TAG, "force refreshed and stored: $version")
            }.onFailure { e ->
                AppLog.w(TAG, "force refresh failed", e)
            }

    /** 直接写入缓存版本，并刷新缓存时间。 */
    fun update(
        version: String,
        context: Context? = null,
    ) {
        if (!VERSION_PATTERN.matches(version)) {
            AppLog.w(TAG, "invalid version, ignore: $version")
            return
        }
        currentVersion = version
        lastFetchTimeMs = System.currentTimeMillis()
        persist(context)
    }

    private fun persist(context: Context?) {
        val ctx = context ?: appContext ?: return
        ctx
            .getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_VERSION, currentVersion)
            .putLong(KEY_LAST_FETCH_TIME, lastFetchTimeMs)
            .apply()
    }
}
