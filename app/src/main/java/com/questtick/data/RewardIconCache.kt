package com.questtick.data

import android.content.Context
import com.questtick.core.throwIfCancellation
import com.questtick.net.HttpRequestConfig
import com.questtick.net.HttpTransport
import com.questtick.net.get
import com.questtick.net.TrustedUrlPolicy
import com.questtick.sign.ErrorText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 签到奖励图标本地缓存。
 *
 * 签到接口只返回远程图片地址，UI 会优先读取本地缓存；首次缺失时再下载，避免反复请求远端资源。
 */
object RewardIconCache {
    private const val DIR_NAME = "reward_icons"
    private const val MAX_ICON_BYTES = 2 * 1024 * 1024
    private const val MAX_CACHE_BYTES = 10L * 1024 * 1024 // 10MB 上限
    private const val MAX_AGE_MILLIS = 30L * 24 * 60 * 60 * 1000 // 30天
    private val locks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()

    @Volatile private var lastCleanup = 0L

    suspend fun cachedFile(
        context: Context,
        url: String,
    ): File? =
        withContext(Dispatchers.IO) {
            if (!TrustedUrlPolicy.isRewardIconUrl(url)) return@withContext null
            val appContext = context.applicationContext
            maybeCleanup(appContext)
            cachedFileBlocking(appContext, url)
        }

    suspend fun getOrDownload(
        context: Context,
        url: String,
        httpTransport: HttpTransport,
        onFailure: ((String) -> Unit)? = null,
    ): File? =
        withContext(Dispatchers.IO) {
            if (!TrustedUrlPolicy.isRewardIconUrl(url)) {
                onFailure?.invoke("奖励图片地址为空或不受信任: $url")
                return@withContext null
            }
            val appContext = context.applicationContext
            maybeCleanup(appContext)
            val file = targetFile(appContext, url)
            if (isUsableCacheFile(file)) return@withContext file

            val key = file.name
            val mutex = locks.getOrPut(key) { Mutex() }
            mutex.withLock {
                if (isUsableCacheFile(file)) return@withLock file
                try {
                    downloadIconToCache(url, file, httpTransport, onFailure)
                } catch (e: Exception) {
                    e.throwIfCancellation()
                    onFailure?.invoke("奖励图片下载异常: ${ErrorText.detailOf(e)}")
                    null
                }
            }
        }

    private fun cachedFileBlocking(
        context: Context,
        url: String,
    ): File? {
        val file = targetFile(context, url)
        return file.takeIf(::isUsableCacheFile)
    }

    private fun isUsableCacheFile(file: File): Boolean {
        val usable = file.exists() && file.length() > 0L
        if (usable) {
            runCatching { file.setLastModified(System.currentTimeMillis()) }
        }
        return usable
    }

    private suspend fun downloadIconToCache(
        url: String,
        file: File,
        httpTransport: HttpTransport,
        onFailure: ((String) -> Unit)? = null,
    ): File? {
        if (!TrustedUrlPolicy.isRewardIconUrl(url)) return null
        file.parentFile?.mkdirs()
        // 米游社部分 CDN 会在查询参数中附加缩略图处理；优先尝试去掉处理参数的原图地址。
        for (candidate in highResolutionCandidates(url)) {
            val response =
                runCatching {
                    httpTransport.get(
                        url = candidate,
                        headers = mapOf("User-Agent" to "Mozilla/5.0"),
                        config = HttpRequestConfig(maxResponseBytes = MAX_ICON_BYTES),
                    )
                }.getOrElse { error ->
                    onFailure?.invoke("奖励图片请求异常 candidate=$candidate: ${ErrorText.detailOf(error)}")
                    continue
                }
            if (response.code !in 200..299) {
                onFailure?.invoke("奖励图片请求失败 candidate=$candidate http=${response.code}")
                continue
            }
            val bytes = response.bodyBytes()
            if (bytes.isEmpty() || bytes.size > MAX_ICON_BYTES) {
                onFailure?.invoke("奖励图片响应无效 candidate=$candidate bytes=${bytes.size}")
                continue
            }

            val tmp = File(file.parentFile, "${file.name}.tmp")
            runCatching { tmp.delete() }
            try {
                tmp.outputStream().buffered().use { output -> output.write(bytes) }
                if (!tmp.renameTo(file)) {
                    tmp.copyTo(file, overwrite = true)
                    tmp.delete()
                }
                return file.takeIf(::isUsableCacheFile)
            } finally {
                if (tmp.exists()) runCatching { tmp.delete() }
            }
        }
        onFailure?.invoke("奖励图片所有高清候选地址均下载失败: $url")
        return null
    }

    private fun highResolutionCandidates(url: String): List<String> {
        val parsed = url.toHttpUrlOrNull() ?: return listOf(url)
        val processingParameters = setOf("x-oss-process", "imageMogr2/thumbnail", "thumbnail", "resize", "format", "quality")
        val original =
            parsed
                .newBuilder()
                .apply {
                    parsed.queryParameterNames
                        .filter { it in processingParameters }
                        .forEach { removeAllQueryParameters(it) }
                }.build()
                .toString()
        return listOf(original, url).distinct().filter(TrustedUrlPolicy::isRewardIconUrl)
    }

    private fun targetFile(
        context: Context,
        url: String,
    ): File {
        val dir = File(context.applicationContext.filesDir, DIR_NAME)
        return File(dir, sha256(url) + extensionOf(url))
    }

    private fun extensionOf(url: String): String {
        val path = url.substringBefore('?').lowercase()
        return when {
            path.endsWith(".jpg") || path.endsWith(".jpeg") -> ".jpg"
            path.endsWith(".webp") -> ".webp"
            path.endsWith(".gif") -> ".gif"
            path.endsWith(".png") -> ".png"
            else -> ".png"
        }
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    // 定期按过期时间和最近访问顺序清理缓存，避免目录无限增长。
    private fun maybeCleanup(context: Context) {
        val now = System.currentTimeMillis()
        // 最多每天清理一次，避免频繁触发额外 IO。
        if (now - lastCleanup < 24 * 60 * 60 * 1000L) return
        lastCleanup = now
        try {
            val dir = File(context.applicationContext.filesDir, DIR_NAME)
            if (!dir.exists()) return
            val files = dir.listFiles() ?: return
            val cutoff = now - MAX_AGE_MILLIS
            files.filter { it.lastModified() < cutoff }.forEach { it.delete() }
            val remaining = dir.listFiles() ?: return
            var total = remaining.sumOf { it.length() }
            if (total > MAX_CACHE_BYTES) {
                remaining.sortedBy { it.lastModified() }.forEach { f ->
                    if (total <= MAX_CACHE_BYTES) return@forEach
                    total -= f.length()
                    f.delete()
                }
            }
        } catch (_: Exception) {
        }
    }
}
