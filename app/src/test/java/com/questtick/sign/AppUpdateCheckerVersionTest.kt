package com.questtick.sign

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateCheckerVersionTest {

    @Test
    fun `newer semantic version is detected`() {
        assertTrue(AppUpdateChecker.isVersionNewer("1.2.0", "1.1.9"))
        assertTrue(AppUpdateChecker.isVersionNewer("2.0", "1.9.9"))
    }

    @Test
    fun `same logical version is not newer`() {
        assertFalse(AppUpdateChecker.isVersionNewer("1.2", "1.2.0"))
        assertFalse(AppUpdateChecker.isVersionNewer("v1.2.3", "1.2.3"))
    }

    @Test
    fun `older version is not newer`() {
        assertFalse(AppUpdateChecker.isVersionNewer("1.9.9", "2.0.0"))
    }
}
