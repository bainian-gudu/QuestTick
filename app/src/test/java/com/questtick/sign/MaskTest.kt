package com.questtick.sign

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MaskTest {
    @Test
    fun sensitiveMasksStandaloneTokenWithoutThrowing() {
        val masked = Mask.sensitive("token=abcdef cookie_token=secret account_id=123456789")

        assertTrue(masked.contains("token=***"))
        assertTrue(masked.contains("cookie_token=***"))
        assertTrue(masked.contains("account_id=***"))
        assertFalse(masked.contains("abcdef"))
        assertFalse(masked.contains("secret"))
    }
}
