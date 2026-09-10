package com.questtick.config

import com.questtick.BuildConfig
import org.json.JSONObject

/** DS 配置发布通道，用于稳定 / 灰度 / 开发配置隔离。 */
enum class DsConfigChannel(
    val key: String,
) {
    STABLE("stable"),
    BETA("beta"),
    DEV("dev"),
    ;

    companion object {
        fun parse(value: String?): DsConfigChannel = entries.firstOrNull { it.key == value } ?: STABLE
    }
}

/** DS 签名动态配置。 */
data class DsConfig(
    val version: String,
    val minVersion: String,
    val salt: String,
    val algorithm: String,
    val updateTime: Long,
    val channel: DsConfigChannel = DsConfigChannel.STABLE,
) {
    fun isUsable(currentAppVersion: String = BuildConfig.VERSION_NAME): Boolean =
        version.isNotBlank() &&
            minVersion.isNotBlank() &&
            ConfigVerifier.isVersionAtLeast(currentAppVersion, minVersion) &&
            salt.length >= MIN_SALT_LENGTH &&
            algorithm in SUPPORTED_ALGORITHMS &&
            updateTime >= 0L

    fun toJson(
        sha256: String = ConfigVerifier.sha256Hex(canonicalString()),
        rsaSignature: String = if (this == Default) DEFAULT_RSA_SIGNATURE else "",
    ): JSONObject =
        JSONObject().apply {
            put("version", version)
            put("minVersion", minVersion)
            put("channel", channel.key)
            put("salt", salt)
            put("algorithm", algorithm)
            put("updateTime", updateTime)
            put("sha256", sha256)
            put("rsaSignature", rsaSignature)
        }

    fun canonicalString(): String = "$version|$minVersion|${channel.key}|$salt|$algorithm|$updateTime"

    fun verify(
        sha256: String,
        rsaSignature: String,
    ): Boolean = isUsable() && ConfigVerifier.verify(this, sha256, rsaSignature)

    companion object {
        const val DEFAULT_VERSION = "2026.06.24-default"
        const val DEFAULT_MIN_VERSION = "1.0.0"

        // Salt 不再硬编码在源码中，通过 BuildConfig 注入并做简单反混淆
        // 可通过 gradle -PdsSalt=xxx 或环境变量 DS_SALT 覆盖
        val DEFAULT_SALT: String by lazy {
            try {
                String(android.util.Base64.decode(BuildConfig.DS_SALT_OBF, android.util.Base64.DEFAULT))
                    .reversed()
            } catch (_: Exception) {
                // 极端降级兜底，避免崩溃（正常不会走到）
                "yUZ3s0Sna1IrSNfk29Vo6vRapdOyqyhB"
            }
        }
        const val DEFAULT_ALGORITHM = "md5_v1"
        const val ALGORITHM_MD5_V1 = "md5_v1"
        const val ALGORITHM_MD5_V2 = "md5_v2"
        const val DEFAULT_UPDATE_TIME = 1_782_230_400_000L
        const val DEFAULT_RSA_SIGNATURE =
            "aeYf655BVkL7U8iU1Myqkeqn4rPaJtSgJYMSS16LuZRU6thZedH+YuE6b5I+vYPcQI6OW3lw7FyzBAwo6aU3SqJYuRXRlo1L" +
                "kHH734NWmE2FUp+Naz11vXo0eoz2VM6pCosybN0cy9aI5zq5gMdhS/wKp2Kq/m/8KmyjYbjzcYn4LMRY+bRFBwYhz5wjGdid" +
                "Tttj0ZskwKRt1QDqB3s/ABuxfDVBhHaEUUkhAJqbM1daLdu3/czTVRS5X3nNGKhfj/VSjOds+BsoaPPn2wVCpg/Y55du+659" +
                "Grz4mWTgbw38PPgBKHXNMdvCIxKyz/FepkoTqAXQwXoXGTejOeVKEvziOuBiwqAjLg/iP/URRt18IuZNLCLjsAqbsNfyS8GO" +
                "bI1v2OoZ2X76UGeJos3qBFWRsCU7TUArsjjfppeWcxNKwjw/mo4Haxbe/1NFRcCuzeVtOAZePWdaZOp5xDnczH3DKhjvKp/7" +
                "57lHfvJ2SftAQgOMmvzObPbz38idB/+9ZQlnwm023NI5RE9PSaiesWFqNhmzpJVCI+bfkUfCEHxDcPykqsY4s/uOUukbDDCn" +
                "2QnuZmkFjNlie+/FfTOKwDjqRCeqoYyDfubRw1OVRreU1kEfxTW9K+isNM1OfY0ts89DiBgkOmu/n19W7HkERqTSlPtCKKlT" +
                "sIfCIgOqbWs="
        val Default =
            DsConfig(
                version = DEFAULT_VERSION,
                minVersion = DEFAULT_MIN_VERSION,
                salt = DEFAULT_SALT,
                algorithm = DEFAULT_ALGORITHM,
                updateTime = DEFAULT_UPDATE_TIME,
                channel = DsConfigChannel.STABLE,
            )

        val SUPPORTED_ALGORITHMS = setOf(ALGORITHM_MD5_V1, ALGORITHM_MD5_V2)

        private const val MIN_SALT_LENGTH = 8

        fun fromJson(
            json: JSONObject,
            requireSignature: Boolean = true,
            selectedChannel: DsConfigChannel = DsConfigChannel.STABLE,
            allowStableFallback: Boolean = true,
            currentAppVersion: String = BuildConfig.VERSION_NAME,
        ): DsConfig? {
            if (json.has(
                    "configs",
                )
            ) {
                return selectFromEnvelope(json, requireSignature, selectedChannel, allowStableFallback, currentAppVersion)
            }
            return parseSingle(json, requireSignature, currentAppVersion)
                ?.takeIf { it.channel == selectedChannel || (allowStableFallback && it.channel == DsConfigChannel.STABLE) }
        }

        fun parse(
            raw: String,
            requireSignature: Boolean = true,
            selectedChannel: DsConfigChannel = DsConfigChannel.STABLE,
            allowStableFallback: Boolean = true,
            currentAppVersion: String = BuildConfig.VERSION_NAME,
        ): DsConfig? =
            try {
                fromJson(JSONObject(raw), requireSignature, selectedChannel, allowStableFallback, currentAppVersion)
            } catch (_: Exception) {
                null
            }

        private fun selectFromEnvelope(
            json: JSONObject,
            requireSignature: Boolean,
            selectedChannel: DsConfigChannel,
            allowStableFallback: Boolean,
            currentAppVersion: String,
        ): DsConfig? {
            val arr = json.optJSONArray("configs") ?: return null
            val parsed =
                (0 until arr.length()).mapNotNull { index ->
                    arr.optJSONObject(index)?.let { parseSingle(it, requireSignature, currentAppVersion) }
                }
            return parsed.firstOrNull { it.channel == selectedChannel }
                ?: if (allowStableFallback) parsed.firstOrNull { it.channel == DsConfigChannel.STABLE } else null
        }

        private fun parseSingle(
            json: JSONObject,
            requireSignature: Boolean,
            currentAppVersion: String,
        ): DsConfig? {
            val config =
                DsConfig(
                    version = json.optString("version"),
                    minVersion = json.optString("minVersion", DEFAULT_MIN_VERSION),
                    salt = json.optString("salt"),
                    algorithm = json.optString("algorithm", DEFAULT_ALGORITHM),
                    updateTime = json.optLong("updateTime", 0L),
                    channel = DsConfigChannel.parse(json.optString("channel", DsConfigChannel.STABLE.key)),
                )
            if (!config.isUsable(currentAppVersion)) return null
            if (requireSignature && !config.verify(json.optString("sha256"), json.optString("rsaSignature"))) return null
            return config
        }
    }
}
