package com.questtick.sign

import com.questtick.core.throwIfCancellation
import com.questtick.net.HttpRequestConfig
import com.questtick.net.HttpTransport
import com.questtick.net.getIdempotent
import com.questtick.net.TrustedUrlPolicy
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

    /** 单个 JS 文件最多读取的字节数。 */
    private const val MAX_SCRIPT_BYTES = 2 * 1024 * 1024

    /** 最多尝试解析的 JS 文件数量。 */
    private const val MAX_SCRIPTS = 8
    private const val NAVIGATION_URL = "https://bbs-api.miyoushe.com/apihub/api/home/new"
    private const val NAVIGATION_HOST = "bbs-api.miyoushe.com"
    private const val NAVIGATION_PATH = "/apihub/api/home/new"
    private const val MAX_NAVIGATION_BYTES = 1024 * 1024

    /** 米游社首页导航中的游戏签到入口配置。 */
    private data class NavigationSource(val gids: Int, val host: String, val basePath: String, val allowActIdFilename: Boolean = false)

    private val NAVIGATION_SOURCES = mapOf(
        "Honkai2" to NavigationSource(3, "webstatic.mihoyo.com", "/bbs/event/signin/bh2/"),
        "Honkai3rd" to NavigationSource(1, "webstatic.mihoyo.com", "/bbs/event/signin/bh3/"),
        "TearsOfThemis" to NavigationSource(4, "webstatic.mihoyo.com", "/bbs/event/signin/nxx/"),
        "Genshin" to NavigationSource(2, "act.mihoyo.com", "/bbs/event/signin/hk4e/"),
        "StarRail" to NavigationSource(6, "act.mihoyo.com", "/bbs/event/signin/hkrpg/", true),
        "ZZZ" to NavigationSource(8, "act.mihoyo.com", "/bbs/event/signin/zzz/", true),
    )

    fun isValid(actId: String?): Boolean {
        val value = actId?.trim().orEmpty()
        return value.isNotEmpty() && VALID.matches(value)
    }

    /** 从 URL / HTML / JS 等文本中解析 act_id。 */
    fun parseFromText(text: String?): String? {
        val source = text ?: return null
        for (pattern in PATTERNS) {
            val candidate = pattern.find(source)?.groupValues?.getOrNull(1)?.trim()
            if (isValid(candidate)) return candidate
        }
        return null
    }

    /** 从 HTML 中提取 script src 的绝对地址；相对路径以页面 URL 为基准解析。 */
    fun extractScriptUrls(
        html: String,
        pageUrl: String,
    ): List<String> {
        if (!TrustedUrlPolicy.isActivityResourceUrl(pageUrl)) return emptyList()
        val base =
            try {
                java.net.URI(pageUrl)
            } catch (_: Exception) {
                return emptyList()
            }
        val urls = LinkedHashSet<String>()
        for (m in SCRIPT_SRC.findAll(html)) {
            try {
                val resolved = base.resolve(m.groupValues[1]).toString()
                if (TrustedUrlPolicy.isActivityResourceUrl(resolved)) urls.add(resolved)
            } catch (_: Exception) {
                // 跳过无法解析的 URL。
            }
        }
        return urls.toList()
    }

    /** 从米游社首页导航 JSON 严格提取当前游戏签到入口的 act_id。 */
    fun parseFromNavigation(body: String?, gameKey: String): String? {
        val source = NAVIGATION_SOURCES[gameKey] ?: return null
        val root = try { JSONObject(body ?: return null) } catch (_: Exception) { return null }
        if (root.optInt("retcode", Int.MIN_VALUE) != 0) return null
        val navigator = root.optJSONObject("data")?.optJSONArray("navigator") ?: return null
        val candidates = linkedSetOf<String>()
        var rejectedGamePath = false
        for (i in 0 until navigator.length()) {
            val path = navigator.optJSONObject(i)?.optString("app_path") ?: continue
            val candidate = parseNavigationPath(path, source)
            if (candidate != null) candidates += candidate
            else if (path.contains(source.basePath)) rejectedGamePath = true
        }
        if (rejectedGamePath || candidates.size != 1) return null
        return candidates.first()
    }

    private fun parseNavigationPath(raw: String, source: NavigationSource): String? {
        if (raw.isBlank() || raw != raw.trim() || raw.contains('%') || Regex("[\\s\\\\\\u0000-\\u001f\\u007f]").containsMatchIn(raw)) return null
        val uri = try { URI(raw) } catch (_: Exception) { return null }
        if (uri.scheme != "https" || (uri.port != -1 && uri.port != 443) || !uri.userInfo.isNullOrBlank() || !uri.rawFragment.isNullOrBlank() ||
            uri.host?.lowercase() != source.host.lowercase()) return null
        val query = uri.rawQuery ?: return null
        val pairs = query.split('&').filter { it.isNotEmpty() }
        val actPairs = pairs.filter { it.substringBefore('=') == "act_id" }
        if (actPairs.size != 1) return null
        val parts = actPairs.single().split('=')
        if (parts.size != 2 || !isValid(parts[1])) return null
        val candidate = parts[1]
        val accepted = setOf(source.basePath, source.basePath + "index.html") +
            if (source.allowActIdFilename) setOf(source.basePath + candidate + ".html") else emptySet()
        if (uri.path !in accepted) return null
        val filename = uri.path.removePrefix(source.basePath)
        val filenameId = Regex("^(e\\d{12,20})\\.html$").find(filename)?.groupValues?.get(1)
        if (filenameId != null && filenameId != candidate) return null
        return candidate
    }

    private suspend fun fetchFromNavigation(
        game: MysSignIn.GameConfig,
        httpTransport: HttpTransport,
        recordError: ((String) -> Unit)? = null,
    ): String? {
        val source = NAVIGATION_SOURCES[game.key] ?: return null
        val url = "$NAVIGATION_URL?gids=${source.gids}"
        return try {
            val response = httpTransport.getIdempotent(
                url,
                headers = mapOf("User-Agent" to Endpoints.USER_AGENT, "Accept" to "application/json"),
                config = HttpRequestConfig(maxResponseBytes = MAX_NAVIGATION_BYTES),
            )
            if (response.code !in 200..299) return null
            val final = response.finalUrl.ifBlank { url }
            val finalUri = URI(final)
            if (finalUri.scheme != "https" || finalUri.host?.lowercase() != NAVIGATION_HOST || finalUri.path != NAVIGATION_PATH || finalUri.rawQuery != "gids=${source.gids}") return null
            parseFromNavigation(response.body, game.key)
        } catch (e: Exception) {
            e.throwIfCancellation()
            recordError?.invoke("${game.name} act_id 导航获取失败: ${ErrorText.detailOf(e)}")
            android.util.Log.w("ActId", "导航 act_id 获取失败: ${game.key}", e)
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
    ): String? {
        // 优先从首页导航解析活动入口，失败时再尝试活动页本身。
        fetchFromNavigation(game, httpTransport, recordError)?.let { return it }
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
            if (!TrustedUrlPolicy.isActivityResourceUrl(finalUrl)) return null

            // 仅从受信活动域的最终 URL 解析，防止跨域跳转把不可信内容带入动态签到参数。
            parseFromText(finalUrl)?.let { return it }

            // 其次解析受信活动页的 HTML 正文。
            val html = res.body
            parseFromText(html)?.let { return it }

            // 最后解析页面引用的 JS 文件。
            val scriptUrls = extractScriptUrls(html, finalUrl)
            for (scriptUrl in scriptUrls.take(MAX_SCRIPTS)) {
                if (!TrustedUrlPolicy.isActivityResourceUrl(scriptUrl)) continue
                val actId =
                    try {
                        val js =
                            httpTransport.getIdempotent(
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
                        android.util.Log.d("ActId", "解析 JS 失败: $scriptUrl, ${e.javaClass.simpleName}: ${e.message}")
                        null
                    }
                if (actId != null) return actId
            }
            null
        } catch (e: Exception) {
            e.throwIfCancellation()
            recordError?.invoke("${game.name} act_id 获取失败: ${ErrorText.detailOf(e)}")
            android.util.Log.w("ActId", "获取最新 act_id 失败: ${game.actPage}", e)
            null
        }
    }
}
