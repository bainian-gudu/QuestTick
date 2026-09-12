package com.questtick.sign

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
 *
 * salt 与算法由调用方按接口类型显式指定，不再依赖任何远程或本地动态配置。
 * 这些 salt 是米游社客户端公开常量，随客户端一起分发，不是密钥，因此不做混淆。
 */
object Ds {
    /** DS 算法版本。 */
    enum class Algorithm {
        MD5_V1,
        MD5_V2,
    }

    /** 通用 luna 接口使用的 salt（历史上由动态配置提供的默认 salt）。 */
    internal const val LUNA_SALT = "yUZ3s0Sna1IrSNfk29Vo6vRapdOyqyhB"

    /** 米游社 App 社区写接口（X6）使用的 salt。 */
    internal const val X6_SALT = "t0qEgfub6cvueAPgR5m9aQWWVciEer7v"

    /** 普通游戏签到 / 奖励等网页端接口使用的 salt。 */
    internal const val WEB_SALT = "d9200c84610886e8c874fc33c8f308b"

    private const val CHARS = "0123456789abcdefghijklmnopqrstuvwxyz"
    private const val RANDOM_V1_LENGTH = 6
    private const val V2_RANDOM_MIN = 100000
    private const val V2_RANDOM_MAX = 200000
    private const val V2_RANDOM_SPECIAL = "100000"
    private const val V2_RANDOM_REPLACEMENT = "642367"
    private const val MIN_SALT_LENGTH = 8

    private fun md5(input: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun randomString(length: Int): String = (1..length).map { CHARS[Random.nextInt(CHARS.length)] }.joinToString("")

    private fun randomV2(): String = Random.nextInt(V2_RANDOM_MIN, V2_RANDOM_MAX + 1).toString()

    /**
     * 生成用于当前请求的 DS。
     *
     * @param body POST 请求体 JSON 字符串（已按 key 字母序）；GET 请求传空字符串
     * @param query URL 查询字符串（不含 ?，已按 key 字母序）；无查询参数传空字符串
     * @param algorithm 算法版本
     * @param salt 接口对应的 salt
     */
    fun generate(
        body: String = "",
        query: String = "",
        algorithm: Algorithm = Algorithm.MD5_V1,
        salt: String = LUNA_SALT,
    ): String {
        val t = System.currentTimeMillis() / 1000
        val r =
            when (algorithm) {
                Algorithm.MD5_V1 -> randomString(RANDOM_V1_LENGTH)
                Algorithm.MD5_V2 -> randomV2()
            }
        return generate(t, r, body, query, algorithm, salt)
    }

    /** 米游社 App 社区写接口使用的 X6 DS。 */
    fun generateX6(
        body: String = "",
        query: String = "",
    ): String {
        val timestampSeconds = System.currentTimeMillis() / 1000
        val random = Random.nextInt(V2_RANDOM_MIN + 1, V2_RANDOM_MAX + 1).toString()
        return generateMd5V2(timestampSeconds, random, X6_SALT, body, query)
    }

    /** 普通游戏签到 / 奖励接口使用的网页端 md5_v1 DS。 */
    fun generateWeb(): String {
        val timestampSeconds = System.currentTimeMillis() / 1000
        val random = randomString(RANDOM_V1_LENGTH)
        return generateMd5V1(timestampSeconds, random, WEB_SALT)
    }

    /**
     * 使用指定时间戳与随机串生成 DS，便于单元测试和异常参数校验。
     *
     * @param timestampSeconds Unix 秒级时间戳
     * @param random 算法相关随机串：md5_v1 为 6 位 base36；md5_v2 为 100000..200000 的数字
     * @param body POST 请求体 JSON 字符串（已按 key 字母序）；GET 请求传空字符串
     * @param query URL 查询字符串（不含 ?，已按 key 字母序）；无查询参数传空字符串
     * @param algorithm 算法版本
     * @param salt 接口对应的 salt
     */
    fun generate(
        timestampSeconds: Long,
        random: String,
        body: String = "",
        query: String = "",
        algorithm: Algorithm = Algorithm.MD5_V1,
        salt: String = LUNA_SALT,
    ): String {
        require(timestampSeconds > 0) { "timestampSeconds must be positive" }
        require(salt.length >= MIN_SALT_LENGTH) { "DS salt is invalid" }
        return when (algorithm) {
            Algorithm.MD5_V1 -> {
                require(random.length == RANDOM_V1_LENGTH) { "random must be 6 characters" }
                require(random.all { it in CHARS }) { "random must only contain lowercase base36 characters" }
                generateMd5V1(timestampSeconds, random, salt)
            }

            Algorithm.MD5_V2 -> {
                val r = random.toIntOrNull()
                require(r != null && r in V2_RANDOM_MIN..V2_RANDOM_MAX) {
                    "md5_v2 random must be an integer in [$V2_RANDOM_MIN, $V2_RANDOM_MAX]"
                }
                generateMd5V2(timestampSeconds, random, salt, body, query)
            }
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
        val effectiveR = if (random == V2_RANDOM_SPECIAL) V2_RANDOM_REPLACEMENT else random
        val c = "salt=$salt&t=$timestampSeconds&r=$effectiveR&b=$body&q=$query"
        return "$timestampSeconds,$effectiveR,${md5(c)}"
    }

    /**
     * 将 [JSONObject] 序列化为 key 按字母序排列的 JSON 字符串，供 md5_v2 的 body 使用。
     */
    fun sortedJsonString(json: JSONObject): String = serializeJsonObject(json)

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

    private fun escapeJson(s: String): String {
        val sb = StringBuilder()
        for (c in s) {
            when (c) {
                '\\' -> {
                    sb.append("\\\\")
                }

                '\"' -> {
                    sb.append("\\\"")
                }

                '\b' -> {
                    sb.append("\\b")
                }

                '\u000C' -> {
                    sb.append("\\f")
                }

                '\n' -> {
                    sb.append("\\n")
                }

                '\r' -> {
                    sb.append("\\r")
                }

                '\t' -> {
                    sb.append("\\t")
                }

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
            null, JSONObject.NULL -> {
                "null"
            }

            is Boolean -> {
                value.toString()
            }

            is Number -> {
                // JSON 不允许 NaN / Infinity
                if ((value is Double && !value.isFinite()) || (value is Float && !value.isFinite())) {
                    "null"
                } else {
                    value.toString()
                }
            }

            is String -> {
                "\"${escapeJson(value)}\""
            }

            is JSONObject -> {
                serializeJsonObject(value)
            }

            is org.json.JSONArray -> {
                serializeJsonArray(value)
            }

            else -> {
                "\"${escapeJson(value.toString())}\""
            }
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
