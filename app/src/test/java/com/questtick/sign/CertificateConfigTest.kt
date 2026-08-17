package com.questtick.sign

import com.questtick.core.security.CertificateConfig
import com.questtick.core.security.PinningManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CertificateConfigTest {
    @Test
    fun officialApiPinsKeepThreePinsForRotation() {
        val configs = CertificateConfig.officialApiPins

        assertEquals(2, configs.size)
        configs.forEach { config ->
            assertTrue(config.host.isNotBlank())
            assertTrue(config.pins.size >= 3)
            assertTrue(config.pins.all { it.startsWith(CertificateConfig.PIN_PREFIX) })
        }
    }

    @Test
    fun pinningManagerBuildsCertificatePinner() {
        val pinner = PinningManager.provideCertificatePinner()

        assertNotNull(pinner)
    }
}
