package com.questtick.data

import android.content.Context
import com.questtick.core.runCatchingCancellable
import com.questtick.net.HttpRequestConfig
import com.questtick.net.HttpTransport
import com.questtick.net.get
import com.questtick.net.TrustedUrlPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

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
    ): File? =
        withContext(Dispatchers.IO) {
            if (!TrustedUrlPolicy.isRewardIconUrl(url)) return@withContext null
            val appContext = context.applicationContext
            maybeCleanup(appContext)
            val file = targetFile(appContext, url)
            if (isUsableCacheFile(file)) return@withContext file

            val key = file.name
            val mutex = locks.getOrPut(key) { Mutex() }
            mutex.withLock {
                if (isUsableCacheFile(file)) return@withLock file
                runCatchingCancellable { downloadIconToCache(url, file, httpTransport) }.getOrNull()
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
    ): File? {
        if (!TrustedUrlPolicy.isRewardIconUrl(url)) return null
        file.parentFile?.mkdirs()
        val response =
            httpTransport.get(
                url = url,
                headers = mapOf("User-Agent" to "Mozilla/5.0"),
                config = HttpRequestConfig(maxResponseBytes = MAX_ICON_BYTES),
            )
        if (response.code !in 200..299) return null
        val bytes = response.bodyBytes()
        if (bytes.isEmpty() || bytes.size > MAX_ICON_BYTES) return null

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
            // 1. 删除过期文件
            val cutoff = now - MAX_AGE_MILLIS
            files.filter { it.lastModified() < cutoff }.forEach { it.delete() }
            // 2. 超过总大小时按 LRU 删除
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
