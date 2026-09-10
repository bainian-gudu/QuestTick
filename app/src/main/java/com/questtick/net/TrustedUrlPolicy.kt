package com.questtick.net

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 动态网络地址的统一信任策略。
 *
 * 每种用途使用独立白名单，避免一个合法域名被扩大为所有网络功能的通行证。校验在发起请求前完成；
 * 重定向的逐跳校验由独立策略负责，不在这里以跟随重定向后的结果替代请求前检查。
 */
object TrustedUrlPolicy {
    private const val GITHUB_REPOSITORY_PATH = "/bainian-gudu/QuestTick"
    private const val GITHUB_API_REPOSITORY_PATH = "/repos$GITHUB_REPOSITORY_PATH"
    private const val GITHUB_RELEASE_ASSET_HOST = "release-assets.githubusercontent.com"
    private const val GITHUB_REPOSITORY_ID = "1337123825"
    private const val GITHUB_RELEASE_ASSET_PATH_PREFIX =
        "/github-production-release-asset/$GITHUB_REPOSITORY_ID/"

    private val businessRequestHosts =
        setOf(
            "api-takumi.mihoyo.com",
            "act-nap-api.mihoyo.com",
            "bbs-api.miyoushe.com",
            "passport-api.mihoyo.com",
            "hk4e-sdk.mihoyo.com",
            "hkrpg-sdk.mihoyo.com",
            "api-cloudgame.mihoyo.com",
            "cg-hkrpg-api.mihoyo.com",
            "ys.mihoyo.com",
            "sr.mihoyo.com",
            "itunes.apple.com",
        )

    private val timeSourceHosts = setOf("www.ntsc.ac.cn")

    private val activityResourceHosts =
        setOf(
            "act.mihoyo.com",
            "webstatic.mihoyo.com",
        )

    private val rewardIconHosts =
        setOf(
            "upload-bbs.miyoushe.com",
            "upload-bbs.mihoyo.com",
            "uploadstatic.mihoyo.com",
            "webstatic.mihoyo.com",
            "act-upload.mihoyo.com",
            "act-webstatic.mihoyo.com",
            "bbs-static.miyoushe.com",
            "fastcdn.mihoyo.com",
        )

    /** 通用业务请求只接受固定业务主机上的规范 HTTPS 地址；重定向时还必须保持同源。 */
    fun isBusinessRequestUrl(rawUrl: String): Boolean {
        val url = parseHttps(rawUrl) ?: return false
        return url.host in businessRequestHosts
    }

    /** 国家授时中心时间源，仅允许固定 HTTPS 主机。 */
    fun isTimeSourceUrl(rawUrl: String): Boolean {
        val url = parseHttps(rawUrl) ?: return false
        return url.host in timeSourceHosts
    }

    fun isSameOriginHttps(
        initialRawUrl: String,
        candidateRawUrl: String,
    ): Boolean {
        val initial = parseHttps(initialRawUrl) ?: return false
        val candidate = parseHttps(candidateRawUrl) ?: return false
        return initial.scheme == candidate.scheme &&
            initial.host == candidate.host &&
            initial.port == candidate.port
    }

    /** 活动页及其脚本只允许米哈游控制的 HTTPS 域名。 */
    fun isActivityResourceUrl(rawUrl: String): Boolean {
        val url = parseHttps(rawUrl) ?: return false
        return url.host in activityResourceHosts
    }

    /** 奖励图片只允许米哈游控制的 HTTPS 图片来源。 */
    fun isRewardIconUrl(rawUrl: String): Boolean {
        val url = parseHttps(rawUrl) ?: return false
        return url.host in rewardIconHosts
    }

    /** 更新地址只允许当前仓库的 GitHub 元数据或 APK 地址。 */
    fun isUpdateSourceUrl(rawUrl: String): Boolean = isUpdateMetadataUrl(rawUrl) || isUpdateAssetUrl(rawUrl)

    /** GitHub Release 元数据仅允许当前仓库的 API。 */
    fun isUpdateMetadataUrl(rawUrl: String): Boolean = isDirectUpdateMetadataUrl(rawUrl)

    /** APK 地址仅允许当前仓库的 GitHub Release。 */
    fun isUpdateAssetUrl(rawUrl: String): Boolean = isDirectUpdateAssetUrl(rawUrl)

    /** 更新元数据重定向必须保持在 GitHub 官方域名。 */
    fun isUpdateMetadataRedirect(
        initialRawUrl: String,
        candidateRawUrl: String,
    ): Boolean {
        if (!isUpdateMetadataUrl(initialRawUrl) || !isUpdateMetadataUrl(candidateRawUrl)) return false
        return isDirectUpdateMetadataUrl(candidateRawUrl) &&
            parseHttps(initialRawUrl)?.host == parseHttps(candidateRawUrl)?.host
    }

    /**
     * APK 直连允许 GitHub 跳转到官方发布资产域。
     */
    fun isUpdateAssetRedirect(
        initialRawUrl: String,
        candidateRawUrl: String,
    ): Boolean {
        if (!isUpdateAssetUrl(initialRawUrl)) return false
        val initial = parseHttps(initialRawUrl) ?: return false
        val candidate = parseHttps(candidateRawUrl) ?: return false
        if (isDirectUpdateAssetUrl(candidateRawUrl)) return candidate.host == initial.host
        return candidate.host == GITHUB_RELEASE_ASSET_HOST &&
            candidate.encodedPath.startsWith(GITHUB_RELEASE_ASSET_PATH_PREFIX)
    }

    /** 更新详情页只接受当前仓库的 GitHub HTTPS 页面。 */
    fun isUpdateReleasePageUrl(rawUrl: String): Boolean {
        val url = parseHttps(rawUrl) ?: return false
        return url.host == "github.com" &&
            (
                url.encodedPath == "$GITHUB_REPOSITORY_PATH/releases" ||
                    url.encodedPath.startsWith("$GITHUB_REPOSITORY_PATH/releases/")
            )
    }

    private fun isDirectUpdateMetadataUrl(rawUrl: String): Boolean {
        val url = parseHttps(rawUrl) ?: return false
        if (url.host != "api.github.com") return false
        val path = url.encodedPath
        return when (path) {
            "$GITHUB_API_REPOSITORY_PATH/releases/latest" -> url.query == null
            "$GITHUB_API_REPOSITORY_PATH/releases" ->
                url.queryParameterNames == setOf("per_page") && url.queryParameter("per_page") == "20"
            else -> false
        }
    }

    private fun isDirectUpdateAssetUrl(rawUrl: String): Boolean {
        val url = parseHttps(rawUrl) ?: return false
        return url.host == "github.com" &&
            url.encodedPath.startsWith("$GITHUB_REPOSITORY_PATH/releases/download/")
    }

    private fun parseHttps(rawUrl: String): HttpUrl? {
        if (rawUrl.isBlank() || rawUrl != rawUrl.trim()) return null
        if (rawUrl.any { it.isISOControl() || it.isWhitespace() } || '\\' in rawUrl) return null
        val url = rawUrl.toHttpUrlOrNull() ?: return null
        if (url.scheme != "https" || url.port != 443) return null
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) return null
        if (url.fragment != null) return null
        if (isIpLiteral(url.host)) return null
        return url
    }

    private fun normalizeHost(host: String): String = host.trim().trimEnd('.').lowercase()

    private fun isIpLiteral(host: String): Boolean {
        if (':' in host) return true
        val parts = host.split('.')
        return parts.size == 4 &&
            parts.all { part ->
                part.isNotEmpty() && part.all(Char::isDigit) && part.toIntOrNull() in 0..255
            }
    }
}
