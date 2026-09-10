package com.questtick.config

import android.util.Base64
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/** DS 配置完整性校验：SHA-256 摘要 + RSA 签名。 */
object ConfigVerifier {
    fun sha256Hex(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun verifySha256(
        config: DsConfig,
        expectedSha256: String,
    ): Boolean {
        val expected = expectedSha256.removePrefix("sha256:").trim()
        return expected.isNotBlank() && expected.equals(sha256Hex(config.canonicalString()), ignoreCase = true)
    }

    fun verifyRsa(
        config: DsConfig,
        signatureBase64: String,
    ): Boolean {
        if (signatureBase64.isBlank()) return false
        return try {
            val keyBytes = decodeBase64(PUBLIC_KEY_DER_BASE64)
            val publicKey = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(keyBytes))
            val verifier = Signature.getInstance("SHA256withRSA")
            verifier.initVerify(publicKey)
            verifier.update(config.canonicalString().toByteArray(Charsets.UTF_8))
            verifier.verify(decodeBase64(signatureBase64))
        } catch (_: Exception) {
            false
        }
    }

    fun verify(
        config: DsConfig,
        sha256: String,
        rsaSignature: String,
    ): Boolean = verifySha256(config, sha256) && verifyRsa(config, rsaSignature)

    /**
     * Base64 解码兼容实现。
     *
     * java.util.Base64 只有 API 26+ 才可直接调用；这里通过反射优先使用 JVM/新系统实现，
     * 在 Android 7.x 等低版本设备上回退到 android.util.Base64，避免 minSdk 24 运行时崩溃。
     */
    private fun decodeBase64(value: String): ByteArray {
        val normalized = value.trim()
        return try {
            val decoder =
                Class
                    .forName("java.util.Base64")
                    .getMethod("getDecoder")
                    .invoke(null)
            decoder.javaClass
                .getMethod("decode", String::class.java)
                .invoke(decoder, normalized) as ByteArray
        } catch (_: Throwable) {
            Base64.decode(normalized, Base64.DEFAULT)
        }
    }

    fun isVersionAtLeast(
        current: String,
        minimum: String,
    ): Boolean {
        val cur = current.versionParts()
        val min = minimum.versionParts()
        val max = maxOf(cur.size, min.size)
        for (i in 0 until max) {
            val a = cur.getOrElse(i) { 0 }
            val b = min.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return true
    }

    private fun String.versionParts(): List<Int> =
        Regex("\\d+")
            .findAll(this)
            .map { it.value.toIntOrNull() ?: 0 }
            .toList()
            .ifEmpty { listOf(0) }

    /** App 内置 DS 配置公钥，仅用于验签；私钥不随应用发布。 */
    private const val PUBLIC_KEY_DER_BASE64 =
        "MIICIjANBgkqhkiG9w0BAQEFAAOCAg8AMIICCgKCAgEAnenIhi2RUN7q1hLvwBQn08gEMDl0IAq9ar8QPIi+BPP3VKFJjWbc" +
            "h9xPj+3o/KvMILYulTagfvIecrz8abDHLANnV/tTICbRbCMIrXsfg8V0piqPiSQGN72MX+e9DcQyhBHNM+woWjiV1CK122qE" +
            "XkvKwQ3vZOAF5dLtKyfR8iFzyxG3tSwa/xzowkw+cGc9flcdYjAx1ODi7+NbAbabZ06Ikbl/4NY5VHuVAEGflj/GCakkzwMd" +
            "IASNf9xlQj0tIvVtEvL85RhohE/aelx24QSxRLrl/Pv8Z184gkg76Edw1F0BfsMr416NjwyQz0Sk7m7b5F+PWU/GKhg2xkRR" +
            "2d2wDRw30rq+nsKyupJ9XizE2aIBLjmcv2XCC8cS5XqRu205OLzzA4b1ohoYYDlpZ2NRFGFSg3lqNW1m8RINGV8tET1AJv8/" +
            "lI4KEnFuebtTvyYszx39YXHK0g2fKzg5AWKZcHwY1sgrk/M7WcJBZmIMJJA4zIUJVNpY48qc3RU70TEpaM/27yqX7nAIwBLJ" +
            "GiDH/7EPPIv22ImVRLFHpGbI9mZYXeHqRaX5dn4RIs1fI6sEVU44N4h4DwY5O7+IkziCb6oQpQCT+q9JlWSoqLKgu+/R1JO/" +
            "h5bm6Pj6+MWdnf2SegxfH/cSsXfzkc4vRRMPoFMOnUnj3WwGamhB/ucCAwEAAQ=="
}
