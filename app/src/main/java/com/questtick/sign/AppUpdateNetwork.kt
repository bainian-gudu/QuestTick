package com.questtick.sign

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import com.questtick.net.TrustedUrlPolicy
import kotlinx.coroutines.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

/**
 * 更新模块的网络辅助工具。
 *
 * 负责网络类型判断、镜像排序、DNS 预热以及 VPN 检测缓存，
 * 让更新请求在不同网络环境下更容易快速收敛。
 */
object AppUpdateNetwork {
    // 预置镜像前缀；是否优先使用由运行时网络策略决定。
    val MIRRORS =
        listOf(
            "https://ghfast.top/", // 推荐主镜像
            "https://ghproxy.net/", // 备用1
            "https://mirror.ghproxy.com/", // 备用2
            "https://ghproxy.com/", // 备用3
            "", // 直连，空字符串表示不加前缀
        )

    enum class UpdateSource(val key: String, val displayName: String, val prefix: String?) {
        AUTO("AUTO", "自动选择", null),
        DIRECT("DIRECT", "GitHub 直连", ""),
        GHFAST("GHFAST", "ghfast.top", "https://ghfast.top/"),
        GHPROXY("GHPROXY", "ghproxy.net", "https://ghproxy.net/"),
        ;

        companion object {
            fun fromKey(value: String): UpdateSource = entries.firstOrNull { it.key == value.uppercase() } ?: AUTO
        }
    }

    @Volatile private var selectedSource = UpdateSource.AUTO

    fun setSource(key: String) {
        selectedSource = UpdateSource.fromKey(key)
    }

    fun sourceName(key: String): String = UpdateSource.fromKey(key).displayName

    fun currentSourceKey(): String = selectedSource.key

    // 为每个镜像记录成功率与平均延迟，用于动态排序。
    private val mirrorScore =
        ConcurrentHashMap<String, MirrorStat>().apply {
            MIRRORS.forEach { put(it, MirrorStat()) }
        }

    data class MirrorStat(
        @Volatile var success: Int = 0,
        @Volatile var fail: Int = 0,
        @Volatile var avgMs: Long = 9999L,
        @Volatile var lastUse: Long = 0L,
    ) {
        fun score(): Double {
            val total = success + fail
            if (total == 0) return 0.5
            val successRate = success.toDouble() / total
            val latencyScore = 1.0 - min(avgMs / 3000.0, 1.0)
            return successRate * 0.7 + latencyScore * 0.3
        }

        fun record(
            success: Boolean,
            latencyMs: Long,
        ) {
            if (success) {
                this.success++
                avgMs = if (avgMs == 9999L) latencyMs else (avgMs * 7 + latencyMs) / 8
            } else {
                fail++
            }
            lastUse = SystemClock.elapsedRealtime()
        }
    }

    @Volatile private var vpnCacheResult = false

    @Volatile private var vpnCacheTime = 0L
    private const val VPN_CACHE_TTL_MS = 60_000L

    fun isVpnActive(context: Context): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - vpnCacheTime < VPN_CACHE_TTL_MS) {
            return vpnCacheResult
        }
        val result = isVpnActiveUncached(context)
        vpnCacheResult = result
        vpnCacheTime = now
        return result
    }

    private fun isVpnActiveUncached(context: Context): Boolean {
        return try {
            val cm =
                context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                    ?: return false
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        } catch (_: Exception) {
            false
        }
    }

    enum class NetLevel { FAST, NORMAL, SLOW, OFFLINE }

    fun networkLevel(context: Context): NetLevel {
        return try {
            val cm =
                context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                    ?: return NetLevel.NORMAL
            val network = cm.activeNetwork ?: return NetLevel.OFFLINE
            val caps = cm.getNetworkCapabilities(network) ?: return NetLevel.NORMAL
            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetLevel.FAST
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetLevel.FAST
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                    if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) {
                        NetLevel.FAST
                    } else if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                        NetLevel.NORMAL
                    } else {
                        NetLevel.SLOW
                    }
                }
                else -> NetLevel.NORMAL
            }
        } catch (_: Exception) {
            NetLevel.NORMAL
        }
    }

    fun adaptiveTimeout(
        context: Context,
        baseConnectMs: Long,
        baseReadMs: Long,
    ): Pair<Long, Long> {
        return when (networkLevel(context)) {
            NetLevel.FAST -> baseConnectMs / 2 to baseReadMs / 2
            NetLevel.NORMAL -> baseConnectMs to baseReadMs
            NetLevel.SLOW -> baseConnectMs * 2 to baseReadMs * 2
            NetLevel.OFFLINE -> 800L to 1200L
        }
    }

    fun getRankedMirrorUrls(
        originalUrl: String,
        context: Context,
    ): List<String> {
        if (!TrustedUrlPolicy.isUpdateSourceUrl(originalUrl)) return emptyList()
        val isVpn = isVpnActive(context)
        val scored =
            MIRRORS.mapNotNull { prefix ->
                val fullUrl = mirrorUrl(originalUrl, prefix) ?: return@mapNotNull null
                val stat = mirrorScore[prefix]
                val score = stat?.score() ?: 0.5
                val adjustedScore = if (isVpn && prefix.isBlank()) score + 0.5 else score
                fullUrl to adjustedScore
            }.sortedByDescending { it.second }.map { it.first }
        return scored.filter(TrustedUrlPolicy::isUpdateSourceUrl).distinct()
    }

    fun reportMirrorResult(
        context: Context,
        url: String,
        success: Boolean,
        latencyMs: Long,
    ) {
        if (!TrustedUrlPolicy.isUpdateSourceUrl(url)) return
        // 仅信任固定镜像精确主机，避免前缀文本匹配把恶意 URL 计入合法镜像。
        val prefix =
            MIRRORS
                .filter { it.isNotBlank() }
                .firstOrNull { mirror ->
                    val mirrorHost = runCatching { java.net.URI(mirror).host.orEmpty() }.getOrDefault("")
                    TrustedUrlPolicy.trustedMirrorSource(url) != null &&
                        java.net.URI(url).host.equals(mirrorHost, ignoreCase = true)
                } ?: ""
        val stat = mirrorScore[prefix] ?: MirrorStat()
        stat.record(success, latencyMs)
        mirrorScore[prefix] = stat
        AppUpdateCache.saveMirrorScores(context, mirrorScore)
    }

    suspend fun warmUp(context: Context) {
        try {
            val savedScores = AppUpdateCache.getMirrorScores(context)
            if (savedScores.isNotEmpty()) {
                mirrorScore.putAll(savedScores)
            }
            // Use a global scope for DNS prewarming to not block the startup flow
            CoroutineScope(Dispatchers.IO).launch {
                warmUpDns()
            }
        } catch (_: Exception) {
        }
    }

    suspend fun warmUpDns() =
        withContext(Dispatchers.IO) {
            val hosts =
                listOf(
                    "api.github.com",
                    "github.com",
                    "objects.githubusercontent.com",
                    "ghfast.top",
                )
            coroutineScope {
                hosts.map { host ->
                    async {
                        withTimeoutOrNull(800L) {
                            runCatching { InetAddress.getAllByName(host) }
                        }
                    }
                }.awaitAll()
            }
        }

    fun mirrorUrl(
        url: String,
        mirrorPrefix: String,
    ): String? {
        if (!TrustedUrlPolicy.isUpdateSourceUrl(url)) return null
        if (mirrorPrefix.isBlank()) return url
        val prefixUri = runCatching { java.net.URI(mirrorPrefix) }.getOrNull() ?: return null
        if (prefixUri.scheme != "https" || !TrustedUrlPolicy.isUpdateMirrorHost(prefixUri.host.orEmpty())) return null
        val candidate = mirrorPrefix + url
        return candidate.takeIf(TrustedUrlPolicy::isUpdateSourceUrl)
    }

    fun buildCandidateUrls(
        context: Context,
        originalUrl: String,
    ): List<String> {
        if (!TrustedUrlPolicy.isUpdateSourceUrl(originalUrl)) return emptyList()
        val isVpn = isVpnActive(context)
        val candidates = mutableListOf<String>()
        val configured = selectedSource
        val orderedMirrors = when {
            configured.prefix != null -> listOf(configured.prefix)
            isVpn -> listOf("") + MIRRORS.filter { it.isNotBlank() }
            else -> MIRRORS.sortedByDescending { mirrorScore[it]?.score() ?: 0.5 }
        }
        for (prefix in orderedMirrors) {
            val url = mirrorUrl(originalUrl, prefix) ?: continue
            if (TrustedUrlPolicy.isUpdateSourceUrl(url) && url !in candidates) candidates.add(url)
        }
        if (TrustedUrlPolicy.isUpdateSourceUrl(originalUrl) && originalUrl !in candidates) candidates.add(originalUrl)
        return candidates.take(5)
    }
}
