package com.questtick.sign

import android.content.Context
import com.questtick.log.AppLog
import com.questtick.core.runCatchingCancellable
import com.questtick.net.HttpTransport
import com.questtick.net.getIdempotent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 云游戏 App 版本自动获取。
 *
 * 从 Apple App Store 的官方 Lookup 接口获取云游戏客户端版本。
 * 云游戏网页和业务接口中的版本字段并不稳定，App Store 是与米游社版本
 * 获取相同、可验证且不会触发业务接口风控的来源。
 */
object CloudAppVersionFetcher {
    private const val LOOKUP_BASE_URL = "https://itunes.apple.com/cn/lookup?id="
    private const val CLOUD_YS_APP_ID = "1569029742"
    private const val CLOUD_SR_APP_ID = "6475038985"
    private val VERSION_PATTERN = Regex("""^\d+(?:\.\d+){1,3}$""")

    data class CloudVersions(
        val cloudYs: String,
        val cloudSr: String,
    )

    suspend fun fetchAll(httpTransport: HttpTransport): Result<CloudVersions> =
        runCatchingCancellable {
            val ys = fetchCloudYs(httpTransport).getOrThrow()
            val sr = fetchCloudSr(httpTransport).getOrThrow()
            CloudVersions(ys, sr)
        }

    suspend fun fetchCloudYs(httpTransport: HttpTransport): Result<String> = fetchAppStoreVersion(CLOUD_YS_APP_ID, httpTransport)

    suspend fun fetchCloudSr(httpTransport: HttpTransport): Result<String> = fetchAppStoreVersion(CLOUD_SR_APP_ID, httpTransport)

    private suspend fun fetchAppStoreVersion(
        appId: String,
        httpTransport: HttpTransport,
    ): Result<String> =
        withContext(Dispatchers.IO) {
            runCatchingCancellable {
                val response =
                    httpTransport.getIdempotent(
                        "$LOOKUP_BASE_URL$appId",
                        headers =
                            mapOf(
                                "User-Agent" to Endpoints.effectiveUserAgent(""),
                                "Accept" to "application/json, text/plain, */*",
                                "Accept-Language" to "zh-CN,zh;q=0.9",
                            ),
                    )
                if (response.code !in 200..299) error("HTTP ${response.code}")
                val version =
                    response
                        .json()
                        .optJSONArray("results")
                        ?.optJSONObject(0)
                        ?.optString("version")
                        .orEmpty()
                        .trim()
                if (!VERSION_PATTERN.matches(version)) error("未解析到有效版本号")
                version
            }
        }
}

/**
 * 云游戏版本配置仓库。
 *
 * 按固定时间间隔（3 天）进行网络刷新：
 * 3 天内不再重复请求，超过 3 天或从未获取过时才发起网络请求；
 * 获取到的新版本会持久化到本地。
 * 手动获取/自定义版本通过 [update] 直接写入，会同步刷新 3 天间隔起点。
 */
object CloudVersionRepository {
    private const val TAG = "CloudVersionRepository"

    // 固定刷新间隔：3 天。小米云游戏版本基本 3 天就会过期一次，所以缩短为 3 天。
    private const val REFRESH_INTERVAL_DAYS = 3
    private const val MS_PER_DAY = 24 * 60 * 60 * 1000L

    @Volatile var currentYs: String = "6.7.0"
        private set

    @Volatile var currentSr: String = "4.3.0"
        private set

    // 用户在设置页输入的自定义版本，优先级高于自动获取的版本。
    // 不写入 cloud_version SP，避免与自动获取的版本混淆；每次启动 / 运行前从 SettingsStore 重新应用。
    @Volatile var overrideYs: String = ""
        private set

    @Volatile var overrideSr: String = ""
        private set

    /** 返回实际生效的云原神版本（自定义 > 自动获取/默认）。 */
    fun effectiveYs(): String = overrideYs.takeIf { it.isNotBlank() } ?: currentYs

    /** 返回实际生效的云崩铁版本（自定义 > 自动获取/默认）。 */
    fun effectiveSr(): String = overrideSr.takeIf { it.isNotBlank() } ?: currentSr

    /**
     * 应用用户在设置页输入的自定义版本。
     * 传入空字符串表示清除覆盖；传入 null 表示不修改；非法格式会被忽略。
     */
    @Synchronized
    fun applyOverrides(
        ys: String?,
        sr: String?,
    ) {
        overrideYs =
            when {
                ys == null -> overrideYs
                ys.isBlank() -> ""
                ys.matches(Regex("""\d+\.\d+\.\d+""")) -> ys
                else -> overrideYs
            }
        overrideSr =
            when {
                sr == null -> overrideSr
                sr.isBlank() -> ""
                sr.matches(Regex("""\d+\.\d+\.\d+""")) -> sr
                else -> overrideSr
            }
    }

    // 每个游戏独立记录最后刷新时间，避免只有单游戏 Token 时另一游戏无意义的刷新把公共时间推后。
    private var lastFetchTimeMsYs: Long = 0L
    private var lastFetchTimeMsSr: Long = 0L

    private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
        val sp = context.getSharedPreferences("signin_cloud_version", Context.MODE_PRIVATE)
        sp.getString("cloud_ys_version", null)?.takeIf { it.isNotBlank() }?.let { currentYs = it }
        sp.getString("cloud_sr_version", null)?.let { if (it.isNotBlank()) currentSr = it }
        lastFetchTimeMsYs = sp.getLong("last_fetch_time_ms_ys", 0L)
        lastFetchTimeMsSr = sp.getLong("last_fetch_time_ms_sr", 0L)
    }

    @Synchronized
    fun update(
        ys: String?,
        sr: String?,
        context: Context? = null,
    ) {
        val now = System.currentTimeMillis()
        // 每次写入（无论是手动获取还是自动刷新）都刷新最后拉取时间，
        // 保证 3 天内不会重复请求。
        ys?.takeIf { it.matches(Regex("""\d+\.\d+\.\d+""")) }?.let {
            currentYs = it
            lastFetchTimeMsYs = now
        }
        sr?.takeIf { it.matches(Regex("""\d+\.\d+\.\d+""")) }?.let {
            currentSr = it
            lastFetchTimeMsSr = now
        }
        persist(context)
    }

    private fun persist(context: Context?) {
        val ctx = context ?: appContext ?: return
        ctx
            .getSharedPreferences("signin_cloud_version", Context.MODE_PRIVATE)
            .edit()
            .putString("cloud_ys_version", currentYs)
            .putString("cloud_sr_version", currentSr)
            .putLong("last_fetch_time_ms_ys", lastFetchTimeMsYs)
            .putLong("last_fetch_time_ms_sr", lastFetchTimeMsSr)
            .apply()
    }

    /**
     * 仅刷新云原神版本。
     * 若 3 天内已刷新过，直接返回本地当前版本；否则请求网络并持久化。
     */
    suspend fun refreshYs(httpTransport: HttpTransport): Result<String> {
        val ctx = appContext ?: return Result.failure(IllegalStateException("CloudVersionRepository not initialized"))
        if (isWithinRefreshInterval(lastFetchTimeMsYs)) {
            AppLog.d(TAG, "skip ys refresh: within ${REFRESH_INTERVAL_DAYS}-day interval, currentYs=$currentYs")
            return Result.success(currentYs)
        }
        AppLog.d(TAG, "refresh ys: currentYs=$currentYs")
        return CloudAppVersionFetcher
            .fetchCloudYs(httpTransport)
            .onSuccess { v ->
                update(v, null, ctx)
                AppLog.d(TAG, "ys refreshed: $v")
            }.onFailure { e ->
                recordFetchAttempt(ys = true, timestampMs = System.currentTimeMillis(), context = ctx)
                AppLog.w(TAG, "ys refresh failed", e)
            }
    }

    /**
     * 仅刷新云崩铁版本。
     * 若 3 天内已刷新过，直接返回本地当前版本；否则请求网络并持久化。
     */
    suspend fun refreshSr(httpTransport: HttpTransport): Result<String> {
        val ctx = appContext ?: return Result.failure(IllegalStateException("CloudVersionRepository not initialized"))
        if (isWithinRefreshInterval(lastFetchTimeMsSr)) {
            AppLog.d(TAG, "skip sr refresh: within ${REFRESH_INTERVAL_DAYS}-day interval, currentSr=$currentSr")
            return Result.success(currentSr)
        }
        AppLog.d(TAG, "refresh sr: currentSr=$currentSr")
        return CloudAppVersionFetcher
            .fetchCloudSr(httpTransport)
            .onSuccess { v ->
                update(null, v, ctx)
                AppLog.d(TAG, "sr refreshed: $v")
            }.onFailure { e ->
                recordFetchAttempt(ys = false, timestampMs = System.currentTimeMillis(), context = ctx)
                AppLog.w(TAG, "sr refresh failed", e)
            }
    }

    /**
     * 按 3 天间隔刷新两个远程版本。
     * 各自独立判断间隔，只有需要时才发起网络请求。
     */
    suspend fun refreshRemote(httpTransport: HttpTransport): Result<CloudAppVersionFetcher.CloudVersions> {
        val ysResult = refreshYs(httpTransport)
        val srResult = refreshSr(httpTransport)
        val ys = ysResult.getOrElse { return Result.failure(it) }
        val sr = srResult.getOrElse { return Result.failure(it) }
        return Result.success(CloudAppVersionFetcher.CloudVersions(ys, sr))
    }

    private fun isWithinRefreshInterval(lastFetchTimeMs: Long): Boolean {
        if (lastFetchTimeMs <= 0) return false
        val intervalMs = REFRESH_INTERVAL_DAYS * MS_PER_DAY
        return (System.currentTimeMillis() - lastFetchTimeMs) < intervalMs
    }

    @Synchronized
    private fun recordFetchAttempt(
        ys: Boolean,
        timestampMs: Long,
        context: Context,
    ) {
        if (ys) lastFetchTimeMsYs = timestampMs else lastFetchTimeMsSr = timestampMs
        persist(context)
    }
}
