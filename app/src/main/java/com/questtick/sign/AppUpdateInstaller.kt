package com.questtick.sign

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.provider.Settings
import android.os.Build
import android.os.SystemClock
import androidx.core.content.FileProvider
import com.questtick.net.HttpMethod
import com.questtick.net.HttpRequest
import com.questtick.net.HttpRequestConfig
import com.questtick.net.HttpTransportException
import com.questtick.net.StreamingHttpTransport
import com.questtick.net.TrustedUrlPolicy
import com.questtick.core.runCatchingCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** 下载 GitHub Release 中的 APK，并调用系统安装器完成更新安装。 */
object AppUpdateInstaller {
    private const val DIR_NAME = "apk_updates"
    private const val APK_MIME = "application/vnd.android.package-archive"

    // 下载与测速参数统一收敛在这里，便于后续按网络表现调节。
    private const val SPEED_TEST_BYTES = 16 * 1024
    private const val SPEED_TEST_TIMEOUT_MS = 800L
    private const val DOWNLOAD_BUFFER_SIZE = 128 * 1024
    private const val PROGRESS_EMIT_INTERVAL_MS = 250L
    private const val MAX_APK_BYTES = 512 * 1024 * 1024

    private val preparedUrlCache = ConcurrentHashMap<String, List<String>>()

    fun cachedApk(
        context: Context,
        info: AppUpdateChecker.AppUpdateInfo,
    ): File? {
        if (info.hasUpdate && !TrustedUrlPolicy.isUpdateAssetUrl(info.apkUrl)) return null
        val target = targetFile(context, info)
        if (!target.exists() || target.length() <= 0L) return null
        return if (isApkValidForUpdate(context.applicationContext, info, target)) {
            target
        } else {
            target.delete()
            null
        }
    }

    private fun versionFromFileName(fileName: String): String =
        Regex("[vV]?(\\d+(?:\\.\\d+)*(?:[._-][A-Za-z0-9]+)?)")
            .find(fileName)
            ?.groupValues
            ?.getOrNull(1)
            .orEmpty()

    fun cleanupInstalledApks(
        context: Context,
        currentVersion: String,
    ) {
        val dir = File(context.applicationContext.cacheDir, DIR_NAME)
        dir.listFiles()?.forEach { file ->
            when {
                file.isFile && file.name.endsWith(".tmp") -> file.delete()
                file.isFile && file.name.endsWith(".apk") -> {
                    val apkVersion = versionFromFileName(file.name)
                    if (apkVersion.isBlank() || !AppUpdateChecker.isVersionNewer(apkVersion, currentVersion)) {
                        file.delete()
                    }
                }
            }
        }
    }

    suspend fun prepareDownloadLinks(
        context: Context,
        info: AppUpdateChecker.AppUpdateInfo,
        streamingHttpTransport: StreamingHttpTransport,
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatchingCancellable {
                if (info.apkUrl.isBlank()) return@runCatchingCancellable
                require(TrustedUrlPolicy.isUpdateAssetUrl(info.apkUrl)) { "更新地址不受信任" }
                val appContext = context.applicationContext
                val enforceSha = isSha256Enforced(info)
                preparedUrlCache[cacheKey(info)] =
                    if (AppUpdateNetwork.isVpnActive(appContext)) {
                        downloadUrlCandidates(appContext, info.apkUrl, enforceSha)
                    } else {
                        selectFastestUrls(downloadUrlCandidates(appContext, info.apkUrl, enforceSha), streamingHttpTransport)
                    }
            }
        }

    suspend fun downloadApk(
        context: Context,
        info: AppUpdateChecker.AppUpdateInfo,
        streamingHttpTransport: StreamingHttpTransport,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): Result<File> =
        withContext(Dispatchers.IO) {
            runCatchingCancellable {
                cachedApk(context, info)?.let {
                    onProgress(it.length(), it.length())
                    return@runCatchingCancellable it
                }

                val url = info.apkUrl.ifBlank { error("未找到 APK 下载地址") }
                require(TrustedUrlPolicy.isUpdateAssetUrl(url)) { "更新地址不受信任" }
                val appContext = context.applicationContext
                val target = targetFile(appContext, info)
                val dir = target.parentFile?.apply { mkdirs() } ?: error("无法创建更新缓存目录")
                cleanupOldApks(dir, keepName = target.name)
                val tmp = File(dir, "${target.name}.tmp")

                val candidates =
                    preparedUrlCache[cacheKey(info)] ?: run {
                        val enforceSha = isSha256Enforced(info)
                        val selected =
                            if (AppUpdateNetwork.isVpnActive(appContext)) {
                                downloadUrlCandidates(appContext, url, enforceSha)
                            } else {
                                selectFastestUrls(
                                    downloadUrlCandidates(appContext, url, enforceSha),
                                    streamingHttpTransport,
                                )
                            }
                        preparedUrlCache[cacheKey(info)] = selected
                        selected
                    }
                downloadFromCandidates(
                    urls = candidates,
                    tmp = tmp,
                    onProgress = onProgress,
                    streamingHttpTransport = streamingHttpTransport,
                )

                if (tmp.length() <= 0L) {
                    tmp.delete()
                    error("下载失败：APK 文件为空")
                }

                if (!isApkValidForUpdate(appContext, info, tmp)) {
                    tmp.delete()
                    error("下载校验失败：安装包版本与更新公告不匹配")
                }

                if (target.exists()) target.delete()
                if (!tmp.renameTo(target)) {
                    tmp.copyTo(target, overwrite = true)
                    tmp.delete()
                }
                target
            }
        }

    fun openInstaller(
        context: Context,
        apkFile: File,
    ): Result<Unit> =
        runCatching {
            val appContext = context.applicationContext
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                !appContext.packageManager.canRequestPackageInstalls()
            ) {
                val settingsIntent =
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${appContext.packageName}"),
                    ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                appContext.startActivity(settingsIntent)
                error("请允许本应用安装未知来源应用后重试")
            }

            val uri =
                FileProvider.getUriForFile(
                    appContext,
                    "${appContext.packageName}.fileprovider",
                    apkFile,
                )
            val installIntent =
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, APK_MIME)
                    putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            appContext.startActivity(installIntent)
        }

    private fun cacheKey(info: AppUpdateChecker.AppUpdateInfo): String =
        listOf(
            info.latestVersion,
            info.apkUrl,
            info.apkSha256,
            AppUpdateNetwork.currentSourceKey(),
        ).joinToString("|")

    private suspend fun selectFastestUrls(
        urls: List<String>,
        streamingHttpTransport: StreamingHttpTransport,
    ): List<String> =
        coroutineScope {
            if (urls.size <= 1) return@coroutineScope urls
            val results =
                urls.mapIndexed { index, url ->
                    async(Dispatchers.IO) {
                        SpeedTestResult(
                            url = url,
                            index = index,
                            bytesPerSecond = measureDownloadSpeed(url, streamingHttpTransport),
                        )
                    }
                }.awaitAll()
            if (results.all { it.bytesPerSecond <= 0L }) {
                urls
            } else {
                results
                    .sortedWith(
                        compareByDescending<SpeedTestResult> { it.bytesPerSecond }
                            .thenBy { it.index },
                    )
                    .map { it.url }
            }
        }

    private data class SpeedTestResult(
        val url: String,
        val index: Int,
        val bytesPerSecond: Long,
    )

    private class UpdateCandidateHttpException(
        val statusCode: Int,
    ) : java.io.IOException("下载失败：HTTP $statusCode")

    // 以小样本和短超时快速估计各镜像的可用速度。
    private suspend fun measureDownloadSpeed(
        url: String,
        streamingHttpTransport: StreamingHttpTransport,
    ): Long =
        withContext(Dispatchers.IO) {
            if (!TrustedUrlPolicy.isUpdateAssetUrl(url)) return@withContext 0L
            runCatchingCancellable {
                val request =
                    HttpRequest(
                        method = HttpMethod.GET,
                        url = url,
                        headers =
                            mapOf(
                                "User-Agent" to "MYS-Signin-Android/2.0",
                                "Accept" to "*/*",
                                "Range" to "bytes=0-${SPEED_TEST_BYTES - 1}",
                                "Cache-Control" to "max-age=30",
                                "Connection" to "keep-alive",
                            ),
                        config =
                            HttpRequestConfig(
                                // 即使候选不支持 Range，也只采样前 16 KiB 后主动关闭；上限约束完整候选大小。
                                maxResponseBytes = MAX_APK_BYTES,
                                connectTimeoutMillis = 800,
                                readTimeoutMillis = 800,
                                writeTimeoutMillis = 800,
                                callTimeoutMillis = 1_200,
                            ),
                    )
                val startedAt = SystemClock.elapsedRealtime()
                val bytesRead =
                    streamingHttpTransport.executeStreaming(request) { response ->
                        if (response.code !in 200..299) return@executeStreaming 0L
                        val buffer = ByteArray(32 * 1024)
                        var readTotal = 0L
                        while (readTotal < SPEED_TEST_BYTES) {
                            val elapsed = SystemClock.elapsedRealtime() - startedAt
                            if (elapsed > SPEED_TEST_TIMEOUT_MS) break
                            if (readTotal == 0L && elapsed > 400) return@executeStreaming 0L
                            val remaining = (SPEED_TEST_BYTES - readTotal).toInt().coerceAtMost(buffer.size)
                            val read = response.read(buffer, 0, remaining)
                            if (read <= 0) break
                            readTotal += read
                        }
                        readTotal
                    }
                val elapsedMs = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(1L)
                val speed = bytesRead * 1000L / elapsedMs
                val ttfb = elapsedMs - (bytesRead * elapsedMs / SPEED_TEST_BYTES.coerceAtLeast(1))
                when {
                    ttfb < 150 -> speed * 12 / 10
                    ttfb > 400 -> speed * 7 / 10
                    else -> speed
                }
            }.getOrDefault(0L)
        }

    private suspend fun downloadFromCandidates(
        urls: List<String>,
        tmp: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
        streamingHttpTransport: StreamingHttpTransport,
    ) {
        for ((index, url) in urls.withIndex()) {
            try {
                if (tmp.exists()) tmp.delete()
                downloadOnce(url, tmp, onProgress, streamingHttpTransport)
                return
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: HttpTransportException) {
                runCatching { tmp.delete() }
                if (!e.failure.retryable || e.failure.outcomeUnknown || index == urls.lastIndex) throw e
                delay(400L)
            } catch (e: UpdateCandidateHttpException) {
                runCatching { tmp.delete() }
                if (index == urls.lastIndex) throw e
            } catch (e: Exception) {
                runCatching { tmp.delete() }
                throw e
            }
        }
        error("没有可用的更新下载地址")
    }

    private suspend fun downloadOnce(
        url: String,
        tmp: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
        streamingHttpTransport: StreamingHttpTransport,
    ) = withContext(Dispatchers.IO) {
        require(TrustedUrlPolicy.isUpdateAssetUrl(url)) { "更新地址不受信任" }
        val request =
            HttpRequest(
                method = HttpMethod.GET,
                url = url,
                headers =
                    mapOf(
                        "User-Agent" to "MYS-Signin-Android/2.0",
                        "Accept" to "application/octet-stream,*/*",
                        "Cache-Control" to "max-age=0",
                        "Connection" to "keep-alive",
                        "Accept-Encoding" to "identity",
                    ),
                config =
                    HttpRequestConfig(
                        maxResponseBytes = MAX_APK_BYTES,
                        connectTimeoutMillis = 3_000,
                        readTimeoutMillis = 15_000,
                        writeTimeoutMillis = 15_000,
                        callTimeoutMillis = 60_000,
                    ),
            )
        val output = tmp.outputStream().buffered(256 * 1024)
        var failure: Throwable? = null
        try {
            streamingHttpTransport.executeStreaming(request) { response ->
                if (response.code !in 200..299) {
                    throw UpdateCandidateHttpException(response.code)
                }
                val totalBytes = response.contentLength.coerceAtLeast(0L)
                var lastProgressEmit = System.currentTimeMillis()
                onProgress(0L, totalBytes)
                val downloadedBytes =
                    response.copyTo(output, DOWNLOAD_BUFFER_SIZE) { copied ->
                        val now = System.currentTimeMillis()
                        if (now - lastProgressEmit >= PROGRESS_EMIT_INTERVAL_MS || copied == totalBytes) {
                            onProgress(copied, totalBytes)
                            lastProgressEmit = now
                        }
                    }
                output.flush()
                onProgress(downloadedBytes, totalBytes.coerceAtLeast(downloadedBytes))
                Unit
            }
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            try {
                output.close()
            } catch (closeError: java.io.IOException) {
                if (failure is kotlinx.coroutines.CancellationException) {
                    failure.addSuppressed(closeError)
                    throw failure
                }
                failure?.let(closeError::addSuppressed)
                throw closeError
            }
        }
    }

    // 生成候选下载地址，优先交给镜像策略模块排序。
    private fun downloadUrlCandidates(
        context: Context,
        url: String,
        enforceSha256: Boolean = true,
    ): List<String> {
        if (!TrustedUrlPolicy.isUpdateAssetUrl(url)) return emptyList()
        return try {
            val candidates = AppUpdateNetwork.buildCandidateUrls(context, url)
                .filter(TrustedUrlPolicy::isUpdateAssetUrl)
            if (enforceSha256) {
                candidates
            } else {
                // 无 SHA256 校验时：仅允许官方域名，禁止第三方加速镜像
                // 防止镜像投毒无法被发现
                candidates.filter { candidateUrl ->
                    TrustedUrlPolicy.isUpdateAssetUrl(candidateUrl) &&
                        TrustedUrlPolicy.trustedMirrorSource(candidateUrl) == null
                }.ifEmpty { listOf(url) }
            }
        } catch (_: Exception) {
            // 镜像策略异常时退回直连
            listOf(url)
        }
    }

    private fun targetFile(
        context: Context,
        info: AppUpdateChecker.AppUpdateInfo,
    ): File {
        val dir = File(context.applicationContext.cacheDir, DIR_NAME)
        val safeName =
            info.apkName.ifBlank { "MYS_Signin_${info.latestVersion}.apk" }
                .replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(dir, safeName)
    }

    private fun cleanupOldApks(
        dir: File,
        keepName: String,
    ) {
        dir.listFiles()?.forEach { file ->
            if (file.isFile && file.name != keepName && (file.name.endsWith(".apk") || file.name.endsWith(".tmp"))) {
                file.delete()
            }
        }
    }

    private fun isApkValidForUpdate(
        context: Context,
        info: AppUpdateChecker.AppUpdateInfo,
        file: File,
    ): Boolean {
        if (!isSha256Valid(info, file)) return false
        val archiveInfo = archivePackageInfo(context, file) ?: return false
        if (archiveInfo.packageName != context.packageName) return false

        val currentInfo = currentPackageInfo(context) ?: return false
        if (!hasMatchingSignature(currentInfo, archiveInfo)) return false

        val apkVersionCode = longVersionCode(archiveInfo)
        val currentVersionCode = longVersionCode(currentInfo)
        if (apkVersionCode <= currentVersionCode) return false

        val apkVersion = normalizeVersion(archiveInfo.versionName.orEmpty())
        val expectedVersion = normalizeVersion(info.latestVersion)
        val versionNameCompatible =
            apkVersion.isBlank() || expectedVersion.isBlank() ||
                !AppUpdateChecker.isVersionNewer(expectedVersion, apkVersion)
        return versionNameCompatible
    }

    private fun archivePackageInfo(
        context: Context,
        file: File,
    ): PackageInfo? =
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageArchiveInfo(
                    file.absolutePath,
                    PackageManager.PackageInfoFlags.of(packageSignatureFlags()),
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageArchiveInfo(file.absolutePath, packageSignatureFlags().toInt())
            }
        } catch (_: Exception) {
            null
        }

    private fun currentPackageInfo(context: Context): PackageInfo? =
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(packageSignatureFlags()),
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, packageSignatureFlags().toInt())
            }
        } catch (_: Exception) {
            null
        }

    private fun packageSignatureFlags(): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES.toLong()
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES.toLong()
        }

    private fun hasMatchingSignature(
        current: PackageInfo,
        archive: PackageInfo,
    ): Boolean {
        val currentDigests = certificateDigests(current)
        val archiveDigests = certificateDigests(archive)
        return currentDigests.isNotEmpty() && archiveDigests.any { it in currentDigests }
    }

    private fun certificateDigests(info: PackageInfo): Set<String> {
        val signatures =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val signingInfo = info.signingInfo ?: return emptySet()
                if (signingInfo.hasMultipleSigners()) {
                    signingInfo.apkContentsSigners.orEmpty().asList()
                } else {
                    signingInfo.signingCertificateHistory.orEmpty().asList()
                }
            } else {
                @Suppress("DEPRECATION")
                info.signatures.orEmpty().asList()
            }
        return signatures.mapTo(linkedSetOf()) { signature -> certificateDigest(signature) }
    }

    private fun certificateDigest(signature: Signature): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun longVersionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }

    private fun normalizeVersion(version: String): String =
        Regex("""\d+(?:\.\d+)*""")
            .find(version.trim().removePrefix("v").removePrefix("V"))
            ?.value
            .orEmpty()

    private fun isSha256Valid(
        info: AppUpdateChecker.AppUpdateInfo,
        file: File,
    ): Boolean {
        val expected = info.apkSha256
        if (expected.isBlank()) {
            // 无校验值时拒绝安装，防止供应链投毒
            // 仅在 DEBUG 构建允许降级以便本地测试
            return com.questtick.BuildConfig.DEBUG
        }
        if (!expected.matches(Regex("^[a-fA-F0-9]{64}$"))) return false
        val actual = sha256(file)
        return actual.equals(expected, ignoreCase = true)
    }

    /**
     * 判断当前更新信息是否携带有效的 SHA-256 校验值
     * 用于决定是否允许使用第三方镜像加速
     */
    private fun isSha256Enforced(info: AppUpdateChecker.AppUpdateInfo): Boolean {
        return info.apkSha256.isNotBlank() && info.apkSha256.matches(Regex("^[a-fA-F0-9]{64}$"))
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
