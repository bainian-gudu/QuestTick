package com.questtick.sign

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppUpdateNetworkTest {
    @Test
    fun `source keys normalize to supported update channels`() {
        assertEquals(AppUpdateNetwork.UpdateSource.AUTO, AppUpdateNetwork.UpdateSource.fromKey("unknown"))
        assertEquals(AppUpdateNetwork.UpdateSource.GHFAST, AppUpdateNetwork.UpdateSource.fromKey("ghfast"))
        assertEquals("GitHub 直连", AppUpdateNetwork.sourceName("direct"))
    }

    @Test
    fun `mirror builder only wraps trusted update source`() {
        val source =
            "https://github.com/moon02222/MYS_Signin_Android/releases/download/v1.2.3/MYS_Signin_v1.2.3_signed.apk"

        assertEquals("https://ghfast.top/$source", AppUpdateNetwork.mirrorUrl(source, "https://ghfast.top/"))
        assertEquals(source, AppUpdateNetwork.mirrorUrl(source, ""))
        assertNull(AppUpdateNetwork.mirrorUrl("https://evil.example/app.apk", "https://ghfast.top/"))
        assertNull(AppUpdateNetwork.mirrorUrl(source, "https://ghfast.top.evil.example/"))
        assertNull(AppUpdateNetwork.mirrorUrl(source, "http://ghfast.top/"))
    }

    @Test
    fun `mirror builder rejects nested mirror and other repository`() {
        val source =
            "https://github.com/moon02222/MYS_Signin_Android/releases/download/v1.2.3/MYS_Signin_v1.2.3_signed.apk"
        val mirrored = "https://ghfast.top/$source"
        val otherRepository =
            "https://github.com/attacker/MYS_Signin_Android/releases/download/v1.2.3/MYS_Signin_v1.2.3_signed.apk"

        assertNull(AppUpdateNetwork.mirrorUrl(mirrored, "https://ghproxy.net/"))
        assertNull(AppUpdateNetwork.mirrorUrl(otherRepository, "https://ghfast.top/"))
    }
}
