@file:Suppress(
    "detekt:InjectDispatcher",
    "detekt:LongParameterList",
    "detekt:TooGenericExceptionCaught",
    "detekt:TooManyFunctions",
)

package com.questtick.sign

/*
 * 应用更新检查：并发请求 GitHub Release API，解析最新版本号、更新说明与 APK 资产。
 */

import android.content.Context
import com.questtick.net.HttpRequestConfig
import com.questtick.net.HttpTransport
import com.questtick.net.getIdempotent
import com.questtick.net.TrustedUrlPolicy
import com.questtick.core.runCatchingCancellable
import com.questtick.core.throwIfCancellation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max

/**
 * 通过 GitHub Release 检查应用版本。
 *
 * 检查流程优先命中本地缓存，未命中时再请求 GitHub 官方 API，
 * 以兼顾弱网场景下的响应速度与可用性。
 */
object AppUpdateChecker {
    private const val LATEST_RELEASE_API = "https://api.github.com/repos/bainian-gudu/QuestTick/releases/latest"
    private const val RELEASES_API = "https://api.github.com/repos/bainian-gudu/QuestTick/releases?per_page=20"
    private const val RELEASES_PAGE = "https://github.com/bainian-gudu/QuestTick/releases"
    private val VERSION_PATTERN = Regex("""\d+(?:\.\d+)*""")
    private val PRE_RELEASE_PATTERN =
        Regex(
            listOf(
                """(?:^|[._+-])(?:alpha|beta|rc|preview|dev|snapshot)\d*(?=$|[._+-])""",
                """(?<=\d)(?:alpha|beta|rc|preview|dev|snapshot)\d*(?=$|[._+-])""",
            ).joinToString("|"),
            RegexOption.IGNORE_CASE,
        )
    private val SHA256_PATTERN = Regex("""^[a-fA-F0-9]{64}$""")
    private const val SHORT_TIMEOUT_MS = 2_000L
    private const val MEDIUM_TIMEOUT_MS = 5_000L
    private const val LONG_TIMEOUT_MS = 10_000L
    private const val FAST_CONNECT_TIMEOUT_MS = 800L
    private const val NORMAL_CONNECT_TIMEOUT_MS = 1_500L
    private const val NORMAL_READ_TIMEOUT_MS = 3_000L
    private const val SLOW_CONNECT_TIMEOUT_MS = 2_500L
    private const val OFFLINE_CONNECT_TIMEOUT_MS = 500L
    private const val OFFLINE_READ_TIMEOUT_MS = 800L
    private const val HTTP_STATUS_NOT_MODIFIED = 304
    private const val HTTP_SUCCESS_MIN = 200
    private const val HTTP_SUCCESS_MAX = 299
    private const val CALL_TIMEOUT_MARGIN_MS = 300L

    private fun getDynamicTimeout(level: AppUpdateNetwork.NetLevel): Long =
        when (level) {
            AppUpdateNetwork.NetLevel.FAST -> SHORT_TIMEOUT_MS
            AppUpdateNetwork.NetLevel.NORMAL -> MEDIUM_TIMEOUT_MS
            AppUpdateNetwork.NetLevel.SLOW -> LONG_TIMEOUT_MS
            AppUpdateNetwork.NetLevel.OFFLINE -> SHORT_TIMEOUT_MS
        }

    data class AppUpdateInfo(
        val currentVersion: String,
        val latestVersion: String,
        val tagName: String,
        val releaseUrl: String,
        val releaseNotes: String,
        val publishedAt: String,
        val apkName: String,
        val apkUrl: String,
        val apkSha256: String,
        val hasUpdate: Boolean,
    )

    private data class ApkAsset(
        val name: String,
        val url: String,
        val sha256: String,
    )

    private data class FetchBodyResult(
        val body: String,
        val etag: String,
    )

    /**
     * 强制走网络获取最新发布信息，不读取本地强缓存；适合手动检查与自动更新提示。
     */
    suspend fun checkFresh(
        context: Context,
        currentVersion: String,
        httpTransport: HttpTransport,
    ): AppUpdateInfo {
        val (info, _) = fetchFreshReleases(context, currentVersion, httpTransport)
        // releases 列表与 /latest 的 ETag 不能共用，避免污染旧快速路径的条件请求。
        AppUpdateCache.put(context, info, etag = "")
        return info
    }

    /**
     * 优先使用缓存，其次走并发网络检查；适合只需要快速兜底的场景。
     */
    suspend fun checkFast(
        context: Context,
        currentVersion: String,
        httpTransport: HttpTransport,
    ): AppUpdateInfo {
        val cache = AppUpdateCache.getFast(context, currentVersion)
        if (cache.info != null && cache.isStrong) {
            // 强缓存命中时直接返回，并在后台静默刷新。
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    refreshNow(context, currentVersion, cache.etag, httpTransport)
                } catch (error: Exception) {
                    error.throwIfCancellation()
                }
            }
            return cache.info
        }

        // 缓存不足时仅请求 GitHub 官方 API。
        return try {
            val res = fetchRace(context, currentVersion, cache.etag, httpTransport)
            AppUpdateCache.put(context, res.first, res.second)
            res.first
        } catch (e: Exception) {
            e.throwIfCancellation()
            // 网络失败时尽量回退到已有缓存，避免界面完全无结果。
            cache.info ?: throw e
        }
    }

    /**
     * 异步入口，必须在非主线程协程中调用（调用方负责切 IO）。
     */
    suspend fun check(
        context: Context,
        currentVersion: String,
        httpTransport: HttpTransport,
    ): Result<AppUpdateInfo> =
        runCatchingCancellable {
            val cache = AppUpdateCache.getFast(context, currentVersion)
            val level = AppUpdateNetwork.networkLevel(context)
            val timeout = getDynamicTimeout(level)

            withTimeoutOrNull(timeout) {
                checkFresh(context, currentVersion, httpTransport)
            } ?: (cache.info ?: throw java.net.SocketTimeoutException("update check timeout"))
        }

    // -------- GitHub 官方 API 请求 --------
    private suspend fun fetchRace(
        context: Context,
        currentVersion: String,
        etag: String,
        httpTransport: HttpTransport,
    ): Pair<AppUpdateInfo, String> =
        fetchBodyRace(
            context = context,
            originalUrl = LATEST_RELEASE_API,
            etag = etag,
            allowHttpCache = true,
            httpTransport = httpTransport,
        ) { body -> parseJson(JSONObject(body), currentVersion) }

    private suspend fun fetchFreshReleases(
        context: Context,
        currentVersion: String,
        httpTransport: HttpTransport,
    ): Pair<AppUpdateInfo, String> {
        val appContext = context.applicationContext
        val (connectMs, readMs) = updateTimeouts(appContext)
        return try {
            val body = fetchBodyOnce(RELEASES_API, "", connectMs, readMs, allowHttpCache = false, httpTransport = httpTransport)
            parseReleasesJson(body.body, currentVersion) to body.etag
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            fetchBodyRace(
                context = appContext,
                originalUrl = RELEASES_API,
                etag = "",
                allowHttpCache = false,
                httpTransport = httpTransport,
            ) { body -> parseReleasesJson(body, currentVersion) }
        }
    }

    private suspend fun fetchBodyRace(
        context: Context,
        originalUrl: String,
        etag: String,
        allowHttpCache: Boolean,
        httpTransport: HttpTransport,
        parse: (String) -> AppUpdateInfo,
    ): Pair<AppUpdateInfo, String> {
        require(TrustedUrlPolicy.isUpdateMetadataUrl(originalUrl)) { "untrusted update metadata URL" }
        val appContext = context.applicationContext
        val (connectMs, readMs) = updateTimeouts(appContext)
        val response = fetchBodyOnce(originalUrl, etag, connectMs, readMs, allowHttpCache, httpTransport)
        return parse(response.body) to response.etag
    }

    private fun updateTimeouts(context: Context): Pair<Long, Long> =
        when (AppUpdateNetwork.networkLevel(context.applicationContext)) {
            AppUpdateNetwork.NetLevel.FAST -> FAST_CONNECT_TIMEOUT_MS to SHORT_TIMEOUT_MS
            AppUpdateNetwork.NetLevel.NORMAL -> NORMAL_CONNECT_TIMEOUT_MS to NORMAL_READ_TIMEOUT_MS
            AppUpdateNetwork.NetLevel.SLOW -> SLOW_CONNECT_TIMEOUT_MS to MEDIUM_TIMEOUT_MS
            AppUpdateNetwork.NetLevel.OFFLINE -> OFFLINE_CONNECT_TIMEOUT_MS to OFFLINE_READ_TIMEOUT_MS
        }

    private suspend fun refreshNow(
        context: Context,
        currentVersion: String,
        etag: String,
        httpTransport: HttpTransport,
    ) {
        try {
            val (info, newEtag) = fetchRace(context, currentVersion, etag, httpTransport)
            AppUpdateCache.put(context, info, newEtag)
        } catch (error: Exception) {
            error.throwIfCancellation()
        }
    }

    private suspend fun fetchBodyOnce(
        url: String,
        etag: String,
        connectMs: Long,
        readMs: Long,
        allowHttpCache: Boolean,
        httpTransport: HttpTransport,
    ): FetchBodyResult =
        withContext(Dispatchers.IO) {
            require(TrustedUrlPolicy.isUpdateMetadataUrl(url)) { "untrusted update metadata URL" }
            val cacheControl = if (allowHttpCache) "max-age=60" else "no-cache, no-store, max-age=0"
            val headers =
                linkedMapOf(
                    "Accept" to "application/vnd.github+json",
                    "User-Agent" to "MYS-Signin-Android",
                    "X-GitHub-Api-Version" to "2022-11-28",
                    "Cache-Control" to cacheControl,
                ).apply {
                    if (!allowHttpCache) put("Pragma", "no-cache")
                    if (etag.isNotBlank()) put("If-None-Match", etag)
                }
            val response =
                httpTransport.getIdempotent(
                    url = url,
                    headers = headers,
                    config =
                        HttpRequestConfig(
                            maxResponseBytes = MAX_METADATA_BYTES,
                            connectTimeoutMillis = connectMs,
                            readTimeoutMillis = readMs,
                            callTimeoutMillis = connectMs + readMs + CALL_TIMEOUT_MARGIN_MS,
                        ),
                )
            if (response.code == HTTP_STATUS_NOT_MODIFIED) throw NotModified()
            if (response.code !in HTTP_SUCCESS_MIN..HTTP_SUCCESS_MAX) error("HTTP ${response.code}")
            if (response.body.isBlank()) error("empty")
            FetchBodyResult(response.body, response.headerValues("ETag").singleOrNull().orEmpty())
        }

    private class NotModified : Exception()

    // -------- 解析 --------
    internal fun parseReleasesJsonForTest(
        body: String,
        currentVersion: String,
    ): AppUpdateInfo = parseReleasesJson(body, currentVersion)

    private fun parseReleasesJson(
        body: String,
        currentVersion: String,
    ): AppUpdateInfo {
        val arr = JSONArray(body)
        var best: JSONObject? = null
        var bestVersion = ""
        var bestPublishedAt = ""
        for (i in 0 until arr.length()) {
            val release = arr.optJSONObject(i)
            val rawVersion = release?.optString("tag_name").orEmpty().ifBlank { release?.optString("name").orEmpty() }
            val version = normalizeVersion(rawVersion)
            val candidate =
                release?.takeIf {
                    !it.optBoolean("draft") &&
                        !it.optBoolean("prerelease") &&
                        !isLikelyPreReleaseVersion(rawVersion) &&
                        version.isNotBlank()
                }
            if (candidate != null && findApkAsset(candidate.optJSONArray("assets"), version) != null) {
                val publishedAt = candidate.optString("published_at")
                val shouldReplace =
                    best == null ||
                        compareVersions(version, bestVersion) > 0 ||
                        (compareVersions(version, bestVersion) == 0 && publishedAt > bestPublishedAt)
                if (shouldReplace) {
                    best = candidate
                    bestVersion = version
                    bestPublishedAt = publishedAt
                }
            }
        }
        return best?.let { parseJson(it, currentVersion) } ?: noReleaseInfo(currentVersion)
    }

    private fun noReleaseInfo(currentVersion: String): AppUpdateInfo =
        AppUpdateInfo(
            currentVersion = currentVersion,
            latestVersion = currentVersion,
            tagName = "",
            releaseUrl = RELEASES_PAGE,
            releaseNotes = "",
            publishedAt = "",
            apkName = "",
            apkUrl = "",
            apkSha256 = "",
            hasUpdate = false,
        )

    private fun parseJson(
        json: JSONObject,
        currentVersion: String,
    ): AppUpdateInfo {
        val tagName = json.optString("tag_name").trim()
        val rawVersion = tagName.ifBlank { json.optString("name") }
        return if (json.optBoolean("draft") ||
            json.optBoolean("prerelease") ||
            isLikelyPreReleaseVersion(rawVersion)
        ) {
            noReleaseInfo(currentVersion)
        } else {
            val latestVersion = normalizeVersion(rawVersion)
            if (latestVersion.isBlank()) error("no version")
            findApkAsset(json.optJSONArray("assets"), latestVersion)?.let { apk ->
                val releaseUrl =
                    json
                        .optString("html_url")
                        .takeIf(TrustedUrlPolicy::isUpdateReleasePageUrl)
                        ?: RELEASES_PAGE
                AppUpdateInfo(
                    currentVersion = currentVersion,
                    latestVersion = latestVersion,
                    tagName = tagName,
                    releaseUrl = releaseUrl,
                    releaseNotes = json.optString("body").trim(),
                    publishedAt = json.optString("published_at").trim(),
                    apkName = apk.name,
                    apkUrl = apk.url,
                    apkSha256 = apk.sha256,
                    hasUpdate = isVersionNewer(latestVersion, currentVersion),
                )
            } ?: noReleaseInfo(currentVersion)
        }
    }

    private fun findApkAsset(
        assets: JSONArray?,
        expectedVersion: String,
    ): ApkAsset? {
        if (assets == null) return null
        val normalizedExpected = normalizeVersion(expectedVersion)
        val candidates = mutableListOf<ApkAsset>()
        for (i in 0 until assets.length()) {
            val o = assets.optJSONObject(i)
            val name = o?.optString("name").orEmpty()
            val url = o?.optString("browser_download_url").orEmpty()
            val isApkAsset =
                o != null &&
                    name.endsWith(".apk", true) &&
                    TrustedUrlPolicy.isUpdateAssetUrl(url) &&
                    !name.contains("unsigned", true)
            if (isApkAsset) {
                val sha =
                    o
                        .optString("digest")
                        .removePrefix("sha256:")
                        .lowercase()
                        .takeIf { SHA256_PATTERN.matches(it) }
                        .orEmpty()
                if (sha.isNotBlank()) {
                    candidates.add(ApkAsset(name, url, sha))
                }
            }
        }
        return candidates.firstOrNull { asset ->
            VERSION_PATTERN.findAll(asset.name).any { match ->
                compareVersions(normalizeVersion(match.value), normalizedExpected) == 0
            }
        }
    }

    fun isVersionNewer(
        latest: String,
        current: String,
    ): Boolean = compareVersions(latest, current) > 0

    private fun normalizeVersion(raw: String): String {
        val normalized = raw.trim().removePrefix("v").removePrefix("V")
        return VERSION_PATTERN.find(normalized)?.value.orEmpty()
    }

    private fun isLikelyPreReleaseVersion(raw: String): Boolean = PRE_RELEASE_PATTERN.containsMatchIn(raw)

    private fun compareVersions(
        a: String,
        b: String,
    ): Int {
        val l = a.split('.', '-', '_').mapNotNull { it.filter { ch -> ch.isDigit() }.toIntOrNull() }
        val r = b.split('.', '-', '_').mapNotNull { it.filter { ch -> ch.isDigit() }.toIntOrNull() }
        val n = max(l.size, r.size)
        for (i in 0 until n) {
            val lv = l.getOrElse(i) { 0 }
            val rv = r.getOrElse(i) { 0 }
            if (lv != rv) return lv.compareTo(rv)
        }
        return 0
    }

    private const val MAX_METADATA_BYTES = 4 * 1024 * 1024
}
