@file:Suppress(
    "detekt:InjectDispatcher",
    "detekt:TooGenericExceptionCaught",
    "detekt:TooManyFunctions",
)

package com.questtick.sign

/*
 * 应用内更新安装：下载 APK、校验 SHA-256，并调用系统安装器完成安装。
 */

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.provider.Settings
import android.os.Build
import androidx.core.content.FileProvider
import com.questtick.net.HttpMethod
import com.questtick.net.HttpRequest
import com.questtick.net.HttpRequestConfig
import com.questtick.net.HttpTransportException
import com.questtick.net.StreamingHttpTransport
import com.questtick.net.TrustedUrlPolicy
import com.questtick.core.runCatchingCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** 下载 GitHub Release 中的 APK，并调用系统安装器完成更新安装。 */
object AppUpdateInstaller {
    private const val DIR_NAME = "apk_updates"
    private const val APK_MIME = "application/vnd.android.package-archive"

    private const val KIB = 1024
    private const val DOWNLOAD_BUFFER_SIZE = 128 * KIB
    private const val PROGRESS_EMIT_INTERVAL_MS = 250L
    private const val MAX_APK_BYTES = 512 * KIB * KIB
    private const val FILE_OUTPUT_BUFFER_BYTES = 256 * KIB
    private const val CANDIDATE_RETRY_DELAY_MS = 400L
    private const val HTTP_SUCCESS_MIN = 200
    private const val HTTP_SUCCESS_MAX = 299

    private val preparedUrlCache = ConcurrentHashMap<String, List<String>>()

    fun cachedApk(
        context: Context,
        info: AppUpdateChecker.AppUpdateInfo,
    ): File? {
        if (info.hasUpdate && !TrustedUrlPolicy.isUpdateAssetUrl(info.apkUrl)) return null
        val target = targetFile(context, info)
        return if (!target.exists() || target.length() <= 0L) {
            null
        } else if (isApkValidForUpdate(context.applicationContext, info, target)) {
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
                file.isFile && file.name.endsWith(".tmp") -> {
                    file.delete()
                }

                file.isFile && file.name.endsWith(".apk") -> {
                    val apkVersion = versionFromFileName(file.name)
                    if (apkVersion.isBlank() || !AppUpdateChecker.isVersionNewer(apkVersion, currentVersion)) {
                        file.delete()
                    }
                }
            }
        }
    }

    /** 预计算并缓存受信任的下载地址；实际网络下载和 SHA-256 校验在 [downloadApk] 中完成。 */
    suspend fun prepareDownloadLinks(
        info: AppUpdateChecker.AppUpdateInfo,
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatchingCancellable {
                if (info.apkUrl.isBlank()) return@runCatchingCancellable
                require(TrustedUrlPolicy.isUpdateAssetUrl(info.apkUrl)) { "更新地址不受信任" }
                preparedUrlCache[cacheKey(info)] = downloadUrlCandidates(info.apkUrl)
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
                        val selected = downloadUrlCandidates(url)
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
            "DIRECT",
        ).joinToString("|")

    private class UpdateCandidateHttpException(
        val statusCode: Int,
    ) : java.io.IOException("下载失败：HTTP $statusCode")

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
                if (!e.failure.retryable || e.failure.outcomeUnknown || index == urls.lastIndex) {
                    cleanupAndThrow(tmp, e)
                }
                runCatching { tmp.delete() }
                delay(CANDIDATE_RETRY_DELAY_MS)
            } catch (e: UpdateCandidateHttpException) {
                if (index == urls.lastIndex) cleanupAndThrow(tmp, e)
                runCatching { tmp.delete() }
            } catch (e: Exception) {
                cleanupAndThrow(tmp, e)
            }
        }
        error("没有可用的更新下载地址")
    }

    private fun cleanupAndThrow(
        tmp: File,
        error: Exception,
    ): Nothing {
        runCatching { tmp.delete() }
        throw error
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
        tmp.outputStream().buffered(FILE_OUTPUT_BUFFER_BYTES).use { output ->
            streamingHttpTransport.executeStreaming(request) { response ->
                if (response.code !in HTTP_SUCCESS_MIN..HTTP_SUCCESS_MAX) {
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
        }
    }

    private fun downloadUrlCandidates(
        url: String,
    ): List<String> {
        if (!TrustedUrlPolicy.isUpdateAssetUrl(url)) return emptyList()
        return AppUpdateNetwork
            .buildCandidateUrls(url)
            .filter(TrustedUrlPolicy::isUpdateAssetUrl)
    }

    private fun targetFile(
        context: Context,
        info: AppUpdateChecker.AppUpdateInfo,
    ): File {
        val dir = File(context.applicationContext.cacheDir, DIR_NAME)
        val safeName =
            info.apkName
                .ifBlank { "MYS_Signin_${info.latestVersion}.apk" }
                .replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(dir, safeName)
    }

    private fun cleanupOldApks(
        dir: File,
        keepName: String,
    ) {
        dir.listFiles()?.forEach { file ->
            val isManagedFile = file.name.endsWith(".apk") || file.name.endsWith(".tmp")
            val isObsoleteManagedFile = file.isFile && file.name != keepName && isManagedFile
            if (isObsoleteManagedFile) {
                file.delete()
            }
        }
    }

    private fun isApkValidForUpdate(
        context: Context,
        info: AppUpdateChecker.AppUpdateInfo,
        file: File,
    ): Boolean =
        if (!isSha256Valid(info, file)) {
            false
        } else {
            val archiveInfo = archivePackageInfo(context, file)
            val currentInfo = currentPackageInfo(context)
            if (archiveInfo == null || currentInfo == null) {
                false
            } else {
                val apkVersion = normalizeVersion(archiveInfo.versionName.orEmpty())
                val expectedVersion = normalizeVersion(info.latestVersion)
                val versionNameCompatible =
                    apkVersion.isBlank() ||
                        expectedVersion.isBlank() ||
                        !AppUpdateChecker.isVersionNewer(expectedVersion, apkVersion)
                archiveInfo.packageName == context.packageName &&
                    hasMatchingSignature(currentInfo, archiveInfo) &&
                    longVersionCode(archiveInfo) > longVersionCode(currentInfo) &&
                    versionNameCompatible
            }
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
        return when {
            expected.isBlank() -> {
                // 无校验值时拒绝安装，防止供应链投毒
                // 仅在 DEBUG 构建允许降级以便本地测试
                com.questtick.BuildConfig.DEBUG
            }

            !expected.matches(Regex("^[a-fA-F0-9]{64}$")) -> {
                false
            }

            else -> {
                val actual = sha256(file)
                actual.equals(expected, ignoreCase = true)
            }
        }
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
