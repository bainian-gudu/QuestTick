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
    private const val GITHUB_REPOSITORY_PATH = "/moon02222/MYS_Signin_Android"
    private const val GITHUB_API_REPOSITORY_PATH = "/repos$GITHUB_REPOSITORY_PATH"
    private const val GITHUB_RELEASE_ASSET_HOST = "release-assets.githubusercontent.com"
    private const val GITHUB_REPOSITORY_ID = "1284672256"
    private const val GITHUB_RELEASE_ASSET_PATH_PREFIX =
        "/github-production-release-asset/$GITHUB_REPOSITORY_ID/"

    private val updateMirrorHosts =
        setOf(
            "ghfast.top",
            "ghproxy.net",
            "mirror.ghproxy.com",
            "ghproxy.com",
        )

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

    /** 更新候选源必须是当前仓库元数据或 APK 地址，镜像不能封装任意外部地址。 */
    fun isUpdateSourceUrl(rawUrl: String): Boolean =
        isUpdateMetadataUrl(rawUrl) || isUpdateAssetUrl(rawUrl)

    /** GitHub Release 元数据仅允许当前仓库的 API；受信镜像必须封装同一原始地址。 */
    fun isUpdateMetadataUrl(rawUrl: String): Boolean =
        isDirectUpdateMetadataUrl(rawUrl) ||
            trustedMirrorSource(rawUrl)?.let(::isDirectUpdateMetadataUrl) == true

    /** APK 地址仅允许当前仓库的 GitHub Release；受信镜像必须封装同一原始地址。 */
    fun isUpdateAssetUrl(rawUrl: String): Boolean =
        isDirectUpdateAssetUrl(rawUrl) ||
            trustedMirrorSource(rawUrl)?.let(::isDirectUpdateAssetUrl) == true

    /** 更新元数据每一跳都必须保持在同一个受信候选源中，禁止镜像与 GitHub 之间交叉跳转。 */
    fun isUpdateMetadataRedirect(
        initialRawUrl: String,
        candidateRawUrl: String,
    ): Boolean {
        if (!isUpdateMetadataUrl(initialRawUrl) || !isUpdateMetadataUrl(candidateRawUrl)) return false
        return redirectSourceIdentity(initialRawUrl) == redirectSourceIdentity(candidateRawUrl)
    }

    /**
     * APK 直连允许 GitHub 跳转到官方发布资产域；镜像候选只允许在原镜像源内跳转，不能转交其他镜像或任意站点。
     */
    fun isUpdateAssetRedirect(
        initialRawUrl: String,
        candidateRawUrl: String,
    ): Boolean {
        if (!isUpdateAssetUrl(initialRawUrl)) return false
        val initial = parseHttps(initialRawUrl) ?: return false
        val candidate = parseHttps(candidateRawUrl) ?: return false
        val wrappedSource = trustedMirrorSource(initialRawUrl)
        if (wrappedSource != null) {
            return candidate.host == initial.host && trustedMirrorSource(candidateRawUrl) == wrappedSource
        }
        if (isDirectUpdateAssetUrl(candidateRawUrl)) return candidate.host == initial.host
        return candidate.host == GITHUB_RELEASE_ASSET_HOST &&
            candidate.encodedPath.startsWith(GITHUB_RELEASE_ASSET_PATH_PREFIX)
    }

    /** 更新详情页只接受当前仓库的 GitHub HTTPS 页面。 */
    fun isUpdateReleasePageUrl(rawUrl: String): Boolean {
        val url = parseHttps(rawUrl) ?: return false
        return url.host == "github.com" &&
            (url.encodedPath == "$GITHUB_REPOSITORY_PATH/releases" ||
                url.encodedPath.startsWith("$GITHUB_REPOSITORY_PATH/releases/"))
    }

    /** 返回受信镜像内封装的 GitHub 原始地址；直连或非法镜像返回 null。 */
    fun trustedMirrorSource(rawUrl: String): String? {
        val mirrorUrl = parseHttps(rawUrl) ?: return null
        if (mirrorUrl.host !in updateMirrorHosts) return null

        val canonical = mirrorUrl.toString()
        val prefix = "https://${mirrorUrl.host}/"
        if (!canonical.startsWith(prefix)) return null
        val source = canonical.removePrefix(prefix)
        if (!source.startsWith("https://")) return null
        // 禁止镜像再次封装镜像，保持候选地址结构唯一且可审计。
        val sourceUrl = parseHttps(source) ?: return null
        if (sourceUrl.host in updateMirrorHosts) return null
        return source
    }

    fun isUpdateMirrorHost(host: String): Boolean = normalizeHost(host) in updateMirrorHosts

    private fun redirectSourceIdentity(rawUrl: String): String? {
        val url = parseHttps(rawUrl) ?: return null
        val source = trustedMirrorSource(rawUrl)
        return if (source == null) {
            "${url.host}|${url.encodedPath}|${url.encodedQuery.orEmpty()}"
        } else {
            "${url.host}|$source"
        }
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
        return parts.size == 4 && parts.all { part ->
            part.isNotEmpty() && part.all(Char::isDigit) && part.toIntOrNull() in 0..255
        }
    }
}
