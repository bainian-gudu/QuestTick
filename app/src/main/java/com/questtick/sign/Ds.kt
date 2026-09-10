package com.questtick.sign

import com.questtick.config.DsConfig
import com.questtick.config.DsConfigRepository
import org.json.JSONObject
import java.security.MessageDigest
import kotlin.random.Random

/**
 * 米游社 DS 签名生成。
 *
 * 支持 md5_v1 与 md5_v2：
 * - md5_v1：`salt=<salt>&t=<t>&r=<r>`
 * - md5_v2：`salt=<salt>&t=<t>&r=<r>&b=<body>&q=<query>`
 * 其中 body 为 JSON 字符串（按 key 字母序），query 为 URL 查询字符串（按 key 字母序）。
 * salt 与 algorithm 由 [DsConfigRepository] 提供；远程配置不可用时会回退到本地默认值。
 */
object Ds {
    private const val CHARS = "0123456789abcdefghijklmnopqrstuvwxyz"
    private const val BBS_X6_SALT = "t0qEgfub6cvueAPgR5m9aQWWVciEer7v"

    // 普通 luna 网页接口使用独立的 Web DS，不读取米游币 X6 动态配置。
    private const val WEB_SALT = "d9200c84610886e8c874fc33c8f308b"

    private fun md5(input: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun randomString(length: Int): String = (1..length).map { CHARS[Random.nextInt(CHARS.length)] }.joinToString("")

    /**
     * 生成用于当前请求的 DS。
     *
     * @param body POST 请求体 JSON 字符串（已按 key 字母序）；GET 请求传空字符串
     * @param query URL 查询字符串（不含 ?，已按 key 字母序）；无查询参数传空字符串
     * @param config DS 配置
     */
    fun generate(
        body: String = "",
        query: String = "",
        config: DsConfig = DsConfigRepository.current,
    ): String {
        val t = System.currentTimeMillis() / 1000
        val r =
            when (config.algorithm) {
                DsConfig.ALGORITHM_MD5_V1 -> randomString(6)
                DsConfig.ALGORITHM_MD5_V2 -> {
                    val raw = Random.nextInt(100000, 200001)
                    if (raw == 100000) "642367" else raw.toString()
                }
                else -> throw IllegalArgumentException("unsupported DS algorithm: ${config.algorithm}")
            }
        return generate(t, r, body, query, config)
    }

    /** 米游社 App 社区写接口使用的 X6 DS。 */
    fun generateX6(
        body: String = "",
        query: String = "",
    ): String {
        val timestampSeconds = System.currentTimeMillis() / 1000
        val random = Random.nextInt(100001, 200001).toString()
        return generateMd5V2(timestampSeconds, random, BBS_X6_SALT, body, query)
    }

    /** 普通游戏签到/奖励接口使用的网页端 md5_v1 DS。 */
    fun generateWeb(): String {
        val timestampSeconds = System.currentTimeMillis() / 1000
        val random = randomString(6)
        return generateMd5V1(timestampSeconds, random, WEB_SALT)
    }

    /**
     * 使用指定时间戳与随机串生成 DS，便于单元测试和异常参数校验。
     *
     * @param timestampSeconds Unix 秒级时间戳
     * @param random 算法相关随机串：md5_v1 为 6 位 base36；md5_v2 为 100001..200000 的数字
     * @param body POST 请求体 JSON 字符串（已按 key 字母序）；GET 请求传空字符串
     * @param query URL 查询字符串（不含 ?，已按 key 字母序）；无查询参数传空字符串
     * @param config DS 配置
     */
    fun generate(
        timestampSeconds: Long,
        random: String,
        body: String = "",
        query: String = "",
        config: DsConfig = DsConfigRepository.current,
    ): String {
        require(timestampSeconds > 0) { "timestampSeconds must be positive" }
        require(config.isUsable()) { "DS config is invalid" }
        return when (config.algorithm) {
            DsConfig.ALGORITHM_MD5_V1 -> {
                require(random.length == 6) { "random must be 6 characters" }
                require(random.all { it in CHARS }) { "random must only contain lowercase base36 characters" }
                generateMd5V1(timestampSeconds, random, config.salt)
            }
            DsConfig.ALGORITHM_MD5_V2 -> {
                val r = random.toIntOrNull()
                require(r != null && r in 100000..200000) { "md5_v2 random must be an integer in [100000, 200000]" }
                generateMd5V2(timestampSeconds, random, config.salt, body, query)
            }
            else -> throw IllegalArgumentException("unsupported DS algorithm: ${config.algorithm}")
        }
    }

    private fun generateMd5V1(
        timestampSeconds: Long,
        random: String,
        salt: String,
    ): String {
        val c = "salt=$salt&t=$timestampSeconds&r=$random"
        return "$timestampSeconds,$random,${md5(c)}"
    }

    private fun generateMd5V2(
        timestampSeconds: Long,
        random: String,
        salt: String,
        body: String,
        query: String,
    ): String {
        // 社区规范：r = 100000 时需替换为 642367
        val effectiveR = if (random == "100000") "642367" else random
        val c = "salt=$salt&t=$timestampSeconds&r=$effectiveR&b=$body&q=$query"
        return "$timestampSeconds,$effectiveR,${md5(c)}"
    }

    /**
     * 将 [JSONObject] 序列化为 key 按字母序排列的 JSON 字符串，供 md5_v2 的 body 使用。
     */
    fun sortedJsonString(json: JSONObject): String = serializeJsonObject(json)

    private fun serializeJsonObject(json: JSONObject): String {
        val sortedKeys =
            json
                .keys()
                .asSequence()
                .sorted()
                .toList()
        val sb = StringBuilder()
        sb.append("{")
        sortedKeys.forEachIndexed { index, key ->
            if (index > 0) sb.append(",")
            sb.append("\"${escapeJson(key)}\":")
            sb.append(serializeValue(json.opt(key)))
        }
        sb.append("}")
        return sb.toString()
    }

    /**
     * 对查询参数按 key 字母序排序后重新拼接，供 md5_v2 的 q 使用。
     */
    fun sortedQueryString(query: String): String {
        if (query.isBlank()) return ""
        return query
            .split("&")
            .mapNotNull { part ->
                val idx = part.indexOf('=')
                if (idx > 0) part.substring(0, idx) to part.substring(idx + 1) else null
            }.sortedBy { it.first }
            .joinToString("&") { "${it.first}=${it.second}" }
    }

    private fun escapeJson(s: String): String {
        val sb = StringBuilder()
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '\"' -> sb.append("\\\"")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    if (c < ' ') {
                        sb.append(String.format("\\u%04x", c.code))
                    } else {
                        sb.append(c)
                    }
                }
            }
        }
        return sb.toString()
    }

    private fun serializeValue(value: Any?): String =
        when (value) {
            null, JSONObject.NULL -> "null"
            is Boolean -> value.toString()
            is Number -> {
                // JSON 不允许 NaN / Infinity
                if ((value is Double && !value.isFinite()) || (value is Float && !value.isFinite())) {
                    "null"
                } else {
                    value.toString()
                }
            }
            is String -> "\"${escapeJson(value)}\""
            is JSONObject -> serializeJsonObject(value)
            is org.json.JSONArray -> serializeJsonArray(value)
            else -> "\"${escapeJson(value.toString())}\""
        }

    private fun serializeJsonArray(array: org.json.JSONArray): String {
        val sb = StringBuilder()
        sb.append("[")
        for (i in 0 until array.length()) {
            if (i > 0) sb.append(",")
            val v = if (array.isNull(i)) null else array.opt(i)
            sb.append(serializeValue(v))
        }
        sb.append("]")
        return sb.toString()
    }
}
