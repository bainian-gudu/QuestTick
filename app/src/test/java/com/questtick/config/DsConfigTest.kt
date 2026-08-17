package com.questtick.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DsConfigTest {
    @Test
    fun defaultConfigSignatureIsValid() {
        val config = DsConfig.Default

        assertTrue(config.isUsable())
        assertTrue(config.verify(ConfigVerifier.sha256Hex(config.canonicalString()), DsConfig.DEFAULT_RSA_SIGNATURE))
    }

    @Test
    fun defaultJsonRoundTripWithSha256AndRsaSignature() {
        val config = DsConfig.Default

        val parsed = DsConfig.fromJson(config.toJson(), requireSignature = true)

        assertEquals(config, parsed)
    }

    @Test
    fun invalidSha256Rejected() {
        val json = DsConfig.Default.toJson(sha256 = "bad", rsaSignature = DsConfig.DEFAULT_RSA_SIGNATURE)

        assertNull(DsConfig.fromJson(json, requireSignature = true))
    }

    @Test
    fun invalidRsaSignatureRejected() {
        val json = DsConfig.Default.toJson(rsaSignature = "bad")

        assertNull(DsConfig.fromJson(json, requireSignature = true))
    }

    @Test
    fun unsupportedAlgorithmRejected() {
        val config =
            DsConfig(
                version = "bad",
                minVersion = "1.0.0",
                salt = "test_salt_123456",
                algorithm = "unknown",
                updateTime = 1L,
            )

        assertFalse(config.isUsable())
    }

    @Test
    fun minVersionCompatibilityIsChecked() {
        val config = DsConfig.Default.copy(minVersion = "999.0.0")

        assertFalse(config.isUsable(currentAppVersion = "1.0.0"))
        assertTrue(ConfigVerifier.isVersionAtLeast("1.2.3", "1.2.0"))
    }

}
