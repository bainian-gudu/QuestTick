package com.questtick.sign

/*
 * act_id 的抓取与校验：解析活动页脚本或从导航接口提取，并对来源地址做白名单校验。
 */

import com.questtick.log.AppLog
import com.questtick.core.throwIfCancellation
import com.questtick.net.HttpRequestConfig
import com.questtick.net.HttpResponse
import com.questtick.net.HttpTransport
import com.questtick.net.getIdempotent
import com.questtick.net.TrustedUrlPolicy
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/**
 * 米游社 act_id 动态获取工具。
 *
 * 负责校验 act_id 格式，并从活动页最终 URL、HTML 或页面引用的 JS 中提取最新 act_id。
 * 是否需要刷新由 [ActIdInvalid] 判断；本工具获取失败时返回 null，不中断签到主流程。
 */
object ActId {
    /** act_id 格式：字母 e 后跟 12 到 20 位数字。 */
    private val VALID = Regex("^e\\d{12,20}$")

    /** 文本解析模式，按命中优先级排列。 */
    private val PATTERNS =
        listOf(
            Regex("[?&]act_id=([a-zA-Z0-9_-]+)"),
            Regex("act_id=([a-zA-Z0-9_-]+)"),
            Regex("[\"']act_id[\"']\\s*:\\s*[\"']([a-zA-Z0-9_-]+)[\"']"),
            Regex("[\"']actId[\"']\\s*:\\s*[\"']([a-zA-Z0-9_-]+)[\"']"),
            Regex("act_id[\"']?\\s*[:=]\\s*[\"']([a-zA-Z0-9_-]+)[\"']"),
            Regex("actId[\"']?\\s*[:=]\\s*[\"']([a-zA-Z0-9_-]+)[\"']"),
        )

    private val SCRIPT_SRC = Regex("<script[^>]+src=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
    private val UNSAFE_NAVIGATION_CHARS = Regex("[\\s\\\\\\u0000-\\u001f\\u007f]")

    /** 单个 JS 文件最多读取的字节数。 */
    private const val MAX_SCRIPT_BYTES = 2 * 1024 * 1024

    /** 最多尝试解析的 JS 文件数量。 */
    private const val MAX_SCRIPTS = 8
    private const val NAVIGATION_URL = "https://bbs-api.miyoushe.com/apihub/api/home/new"
    private const val NAVIGATION_HOST = "bbs-api.miyoushe.com"
    private const val NAVIGATION_PATH = "/apihub/api/home/new"
    private const val MAX_NAVIGATION_BYTES = 1024 * 1024
    private const val DEFAULT_HTTPS_PORT = 443
    private const val HTTP_SUCCESS_MIN = 200
    private const val HTTP_SUCCESS_MAX = 299
    private const val GID_HONKAI2 = 3
    private const val GID_TEARS_OF_THEMIS = 4
    private const val GID_STAR_RAIL = 6
    private const val GID_ZZZ = 8

    /** 米游社首页导航中的游戏签到入口配置。 */
    private data class NavigationSource(
        val gids: Int,
        val host: String,
        val basePath: String,
        val allowActIdFilename: Boolean = false,
    )

    private val NAVIGATION_SOURCES =
        mapOf(
            "Honkai2" to NavigationSource(GID_HONKAI2, "webstatic.mihoyo.com", "/bbs/event/signin/bh2/"),
            "Honkai3rd" to NavigationSource(1, "webstatic.mihoyo.com", "/bbs/event/signin/bh3/"),
            "TearsOfThemis" to NavigationSource(GID_TEARS_OF_THEMIS, "webstatic.mihoyo.com", "/bbs/event/signin/nxx/"),
            "Genshin" to NavigationSource(2, "act.mihoyo.com", "/bbs/event/signin/hk4e/"),
            "StarRail" to NavigationSource(GID_STAR_RAIL, "act.mihoyo.com", "/bbs/event/signin/hkrpg/", true),
            "ZZZ" to NavigationSource(GID_ZZZ, "act.mihoyo.com", "/bbs/event/signin/zzz/", true),
        )

    private object NavigationParser {
        fun parseFromNavigation(
            body: String?,
            gameKey: String,
        ): String? =
            NAVIGATION_SOURCES[gameKey]?.let { source ->
                parseNavigationRoot(body)
                    ?.takeIf { it.optInt("retcode", Int.MIN_VALUE) == 0 }
                    ?.optJSONObject("data")
                    ?.optJSONArray("navigator")
                    ?.let { parseNavigationCandidate(it, source) }
            }

        private fun parseNavigationRoot(body: String?): JSONObject? =
            body?.let {
                try {
                    JSONObject(it)
                } catch (_: Exception) {
                    null
                }
            }

        private fun parseNavigationCandidate(
            navigator: JSONArray,
            source: NavigationSource,
        ): String? {
            val candidates = linkedSetOf<String>()
            var rejectedGamePath = false
            for (i in 0 until navigator.length()) {
                val path = navigator.optJSONObject(i)?.optString("app_path") ?: continue
                val candidate = parseNavigationPath(path, source)
                if (candidate != null) {
                    candidates += candidate
                } else if (path.contains(source.basePath)) {
                    rejectedGamePath = true
                }
            }
            return candidates.singleOrNull().takeUnless { rejectedGamePath }
        }

        private fun parseNavigationPath(
            raw: String,
            source: NavigationSource,
        ): String? =
            parseNavigationUri(raw)
                ?.takeIf { it.scheme == "https" }
                ?.takeIf { it.port == -1 || it.port == DEFAULT_HTTPS_PORT }
                ?.takeIf { it.userInfo.isNullOrBlank() && it.rawFragment.isNullOrBlank() }
                ?.takeIf { it.host?.lowercase() == source.host.lowercase() }
                ?.let { extractNavigationCandidate(it, source) }

        private fun parseNavigationUri(raw: String): URI? =
            raw
                .takeUnless { hasInvalidNavigationEncoding(it) || UNSAFE_NAVIGATION_CHARS.containsMatchIn(it) }
                ?.let {
                    try {
                        URI(it)
                    } catch (_: Exception) {
                        null
                    }
                }

        private fun extractNavigationCandidate(
            uri: URI,
            source: NavigationSource,
        ): String? =
            uri.rawQuery
                ?.split('&')
                ?.filter { it.isNotEmpty() }
                ?.filter { it.substringBefore('=') == "act_id" }
                ?.singleOrNull()
                ?.split('=')
                ?.takeIf { it.size == 2 }
                ?.get(1)
                ?.takeIf(ActId::isValid)
                ?.takeIf { isAcceptedNavigationPath(uri, source, it) }

        private fun isAcceptedNavigationPath(
            uri: URI,
            source: NavigationSource,
            candidate: String,
        ): Boolean {
            val accepted =
                setOf(source.basePath, source.basePath + "index.html") +
                    if (source.allowActIdFilename) setOf(source.basePath + candidate + ".html") else emptySet()
            val filename = uri.path.removePrefix(source.basePath)
            val filenameId = Regex("^(e\\d{12,20})\\.html$").find(filename)?.groupValues?.get(1)
            return uri.path in accepted && (filenameId == null || filenameId == candidate)
        }

        private fun hasInvalidNavigationEncoding(raw: String): Boolean = raw.isBlank() || raw != raw.trim() || raw.contains('%')

        fun isValidNavigationResponse(
            response: HttpResponse,
            fallbackUrl: String,
            source: NavigationSource,
        ): Boolean {
            if (response.code !in HTTP_SUCCESS_MIN..HTTP_SUCCESS_MAX) return false
            val final = response.finalUrl.ifBlank { fallbackUrl }
            val finalUri = URI(final)
            return finalUri.scheme == "https" &&
                finalUri.host?.lowercase() == NAVIGATION_HOST &&
                finalUri.path == NAVIGATION_PATH &&
                finalUri.rawQuery == "gids=${source.gids}"
        }
    }

    fun isValid(actId: String?): Boolean {
        val value = actId?.trim().orEmpty()
        return value.isNotEmpty() && VALID.matches(value)
    }

    /** 从 URL / HTML / JS 等文本中解析 act_id。 */
    fun parseFromText(text: String?): String? =
        text?.let { source ->
            PATTERNS.firstNotNullOfOrNull { pattern ->
                pattern
                    .find(source)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.trim()
                    ?.takeIf(::isValid)
            }
        }

    /** 从 HTML 中提取 script src 的绝对地址；相对路径以页面 URL 为基准解析。 */
    fun extractScriptUrls(
        html: String,
        pageUrl: String,
    ): List<String> =
        if (!TrustedUrlPolicy.isActivityResourceUrl(pageUrl)) {
            emptyList()
        } else {
            val base =
                try {
                    java.net.URI(pageUrl)
                } catch (_: Exception) {
                    null
                }
            if (base == null) {
                emptyList()
            } else {
                val urls = LinkedHashSet<String>()
                for (m in SCRIPT_SRC.findAll(html)) {
                    resolveActivityScriptUrl(base, m.groupValues[1])?.let(urls::add)
                }
                urls.toList()
            }
        }

    private fun resolveActivityScriptUrl(
        base: URI,
        raw: String,
    ): String? =
        try {
            base.resolve(raw).toString().takeIf(TrustedUrlPolicy::isActivityResourceUrl)
        } catch (_: Exception) {
            // 跳过无法解析的 URL。
            null
        }

    /** 从米游社首页导航 JSON 严格提取当前游戏签到入口的 act_id。 */
    fun parseFromNavigation(
        body: String?,
        gameKey: String,
    ): String? = NavigationParser.parseFromNavigation(body, gameKey)

    private suspend fun fetchFromNavigation(
        game: MysSignIn.GameConfig,
        httpTransport: HttpTransport,
        recordError: ((String) -> Unit)? = null,
    ): String? =
        NAVIGATION_SOURCES[game.key]?.let { source ->
            val url = "$NAVIGATION_URL?gids=${source.gids}"
            try {
                val response =
                    httpTransport.getIdempotent(
                        url,
                        headers = mapOf("User-Agent" to Endpoints.USER_AGENT, "Accept" to "application/json"),
                        config = HttpRequestConfig(maxResponseBytes = MAX_NAVIGATION_BYTES),
                    )
                response
                    .takeIf { NavigationParser.isValidNavigationResponse(it, url, source) }
                    ?.let { NavigationParser.parseFromNavigation(it.body, game.key) }
            } catch (e: Exception) {
                e.throwIfCancellation()
                recordError?.invoke("${game.name} act_id 导航获取失败: ${ErrorText.detailOf(e)}")
                AppLog.w("ActId", "导航 act_id 获取失败: ${game.key}", e)
                null
            }
        }

    /**
     * 动态获取最新 act_id：先解析重定向后的最终 URL，再解析 HTML，最后解析引用的 JS 文件。
     *
     * @return 解析成功返回 act_id，失败返回 null。
     */
    suspend fun fetchLatest(
        game: MysSignIn.GameConfig,
        httpTransport: HttpTransport,
        recordError: ((String) -> Unit)? = null,
    ): String? =
        // 优先从首页导航解析活动入口，失败时再尝试活动页本身。
        fetchFromNavigation(game, httpTransport, recordError)
            ?: fetchFromActivityPage(game, httpTransport, recordError)

    private suspend fun fetchFromActivityPage(
        game: MysSignIn.GameConfig,
        httpTransport: HttpTransport,
        recordError: ((String) -> Unit)? = null,
    ): String? {
        if (!TrustedUrlPolicy.isActivityResourceUrl(game.actPage)) {
            recordError?.invoke("${game.name} act_id 地址不受信任: ${game.actPage}")
            return null
        }
        return try {
            val headers =
                mapOf(
                    "User-Agent" to Endpoints.USER_AGENT,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                )
            // 活动页 HTML 同样限制读取大小，避免异常页面占用过多内存。
            val res = httpTransport.getIdempotent(game.actPage, headers, config = HttpRequestConfig(maxResponseBytes = MAX_SCRIPT_BYTES))

            val finalUrl = res.finalUrl.ifBlank { game.actPage }
            fetchActIdFromTrustedPage(game, finalUrl, res.body, httpTransport, recordError)
        } catch (e: Exception) {
            e.throwIfCancellation()
            recordError?.invoke("${game.name} act_id 获取失败: ${ErrorText.detailOf(e)}")
            AppLog.w("ActId", "获取最新 act_id 失败: ${game.actPage}", e)
            null
        }
    }

    private suspend fun fetchActIdFromTrustedPage(
        game: MysSignIn.GameConfig,
        finalUrl: String,
        html: String,
        httpTransport: HttpTransport,
        recordError: ((String) -> Unit)? = null,
    ): String? =
        if (!TrustedUrlPolicy.isActivityResourceUrl(finalUrl)) {
            null
        } else {
            // 依次解析最终 URL、HTML 和页面引用的 JS，避免跨域跳转带入不可信参数。
            parseFromText(finalUrl)
                ?: parseFromText(html)
                ?: fetchActIdFromScripts(game, finalUrl, html, httpTransport, recordError)
        }

    private suspend fun fetchActIdFromScripts(
        game: MysSignIn.GameConfig,
        pageUrl: String,
        html: String,
        httpTransport: HttpTransport,
        recordError: ((String) -> Unit)? = null,
    ): String? =
        extractScriptUrls(html, pageUrl)
            .take(MAX_SCRIPTS)
            .firstNotNullOfOrNull { scriptUrl ->
                if (!TrustedUrlPolicy.isActivityResourceUrl(scriptUrl)) {
                    null
                } else {
                    try {
                        val js =
                            httpTransport
                                .getIdempotent(
                                    scriptUrl,
                                    mapOf(
                                        "User-Agent" to Endpoints.USER_AGENT,
                                        "Accept" to "*/*",
                                        "Referer" to game.actPage,
                                    ),
                                    config = HttpRequestConfig(maxResponseBytes = MAX_SCRIPT_BYTES),
                                ).body
                        parseFromText(js)
                    } catch (e: Exception) {
                        e.throwIfCancellation()
                        recordError?.invoke("${game.name} act_id 脚本解析失败: ${ErrorText.detailOf(e)}")
                        AppLog.d("ActId", "解析 JS 失败: $scriptUrl, ${e.javaClass.simpleName}: ${e.message}")
                        null
                    }
                }
            }
}
