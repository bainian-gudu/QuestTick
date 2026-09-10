package com.questtick.sign

import java.security.MessageDigest

/**
 * Passport 标识辅助工具。
 *
 * 从 Cookie / Token 中提取可脱敏展示的账号标识，并生成不可逆哈希，便于跨运行归并同一账号，
 * 同时避免在日志中输出完整 ID。
 */
object Passport {
    private val passportKeys =
        listOf(
            "account_id",
            "account_id_v2",
            "ltuid",
            "ltuid_v2",
            "login_uid",
            "uid",
            "user_id",
            "userid",
            "aid",
            "ai",
            "oi",
        )

    private fun isLikelyPassportId(value: String?): Boolean {
        val text = value?.trim().orEmpty()
        return text.isNotEmpty() && Regex("^\\d{5,20}$").matches(text)
    }

    private fun safeDecode(value: String): String =
        try {
            java.net.URLDecoder.decode(value, "UTF-8")
        } catch (_: Exception) {
            value
        }

    private fun extractFromKeyValueText(text: String?): String {
        val source = text ?: ""
        val map = HashMap<String, String>()
        val regex = Regex("(?:^|[;,\\s&])([A-Za-z0-9_-]+)=([^;,\\s&]+)")
        for (m in regex.findAll(source)) {
            val key = m.groupValues[1].trim().lowercase()
            val value = safeDecode(m.groupValues[2].trim())
            if (key.isNotEmpty() && value.isNotEmpty()) map[key] = value
        }
        for (key in passportKeys) {
            val value = map[key]
            if (isLikelyPassportId(value)) return value.orEmpty().trim()
        }
        return ""
    }

    fun extractPassportId(value: String?): String {
        if (value.isNullOrEmpty()) return ""
        val text = value.trim()
        if (isLikelyPassportId(text)) return text
        return extractFromKeyValueText(text)
    }

    /** UID 脱敏示例：123456789 -> 123****789。 */
    fun maskPassportId(value: String?): String {
        val text = value?.trim().orEmpty()
        if (text.isEmpty()) return ""
        return when {
            text.length <= 6 -> "${text.take(2)}****"
            text.length <= 8 -> "${text.take(3)}****${text.takeLast(2)}"
            else -> "${text.take(3)}****${text.takeLast(3)}"
        }
    }

    /** 生成不可逆 sha256 前缀，用于归并同一账号。 */
    fun hashPassportId(value: String?): String {
        val text = value?.trim().orEmpty()
        if (text.isEmpty()) return ""
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }

    data class SafeInfo(
        val passportHash: String,
        val passportMasked: String,
    )

    fun getSafePassportInfo(value: String?): SafeInfo {
        val id = extractPassportId(value)
        if (id.isEmpty()) return SafeInfo("", "")
        return SafeInfo(hashPassportId(id), maskPassportId(id))
    }
}
