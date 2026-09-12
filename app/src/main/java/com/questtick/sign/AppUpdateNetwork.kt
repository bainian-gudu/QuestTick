package com.questtick.sign

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.questtick.net.TrustedUrlPolicy

/** GitHub 官方更新网络策略：单一来源、缓存优先、按网络状况调整超时。 */
object AppUpdateNetwork {
    enum class NetLevel { FAST, NORMAL, SLOW, OFFLINE }

    fun networkLevel(context: Context): NetLevel =
        try {
            val cm =
                context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                    ?: return NetLevel.NORMAL
            val network = cm.activeNetwork ?: return NetLevel.OFFLINE
            val caps = cm.getNetworkCapabilities(network) ?: return NetLevel.NORMAL
            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetLevel.FAST

                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) -> NetLevel.FAST

                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetLevel.NORMAL

                else -> NetLevel.NORMAL
            }
        } catch (_: Exception) {
            NetLevel.NORMAL
        }

    fun adaptiveTimeout(
        context: Context,
        baseConnectMs: Long,
        baseReadMs: Long,
    ): Pair<Long, Long> =
        when (networkLevel(context)) {
            NetLevel.FAST -> baseConnectMs / 2 to baseReadMs / 2
            NetLevel.NORMAL -> baseConnectMs to baseReadMs
            NetLevel.SLOW -> baseConnectMs * 2 to baseReadMs * 2
            NetLevel.OFFLINE -> 800L to 1200L
        }

    /** 只返回官方 GitHub URL，供检查器和安装器统一处理候选地址。 */
    fun buildCandidateUrls(originalUrl: String): List<String> = originalUrl.takeIf(TrustedUrlPolicy::isUpdateSourceUrl)?.let { listOf(it) }.orEmpty()
}
