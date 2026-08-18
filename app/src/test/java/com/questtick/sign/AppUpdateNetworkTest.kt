package com.questtick.sign

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateNetworkTest {
    @Test
    fun `update source is always official github`() {
        val source = "https://api.github.com/repos/bainian-gudu/QuestTick/releases/latest"
        assertEquals(listOf(source), AppUpdateNetwork.buildCandidateUrls(source))
    }

    @Test
    fun `untrusted update url has no candidate`() {
        assertTrue(AppUpdateNetwork.buildCandidateUrls("https://evil.example/app.apk").isEmpty())
    }
}
