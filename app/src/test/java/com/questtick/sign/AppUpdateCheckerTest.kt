package com.questtick.sign

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateCheckerTest {
    @Test
    fun `parse releases selects highest stable installable apk`() {
        val body =
            releasesJson(
                release("v1.0.2", asset("MYS_Signin_v1.0.2_signed.apk", sha = SHA_2)),
                release("v1.0.4", asset("MYS_Signin_v1.0.4_unsigned.apk", sha = SHA_4)),
                release("v1.0.3", asset("MYS_Signin_v1.0.3_signed.apk", sha = SHA_3)),
            )

        val info = AppUpdateChecker.parseReleasesJsonForTest(body, currentVersion = "1.0.1")

        assertTrue(info.hasUpdate)
        assertEquals("1.0.3", info.latestVersion)
        assertEquals("MYS_Signin_v1.0.3_signed.apk", info.apkName)
        assertEquals(SHA_3, info.apkSha256)
    }

    @Test
    fun `parse releases skips apk without digest`() {
        val body =
            releasesJson(
                release("v1.0.5", asset("MYS_Signin_v1.0.5_signed.apk", sha = "")),
                release("v1.0.4", asset("MYS_Signin_v1.0.4_signed.apk", sha = SHA_4)),
            )

        val info = AppUpdateChecker.parseReleasesJsonForTest(body, currentVersion = "1.0.1")

        assertEquals("1.0.4", info.latestVersion)
        assertEquals(SHA_4, info.apkSha256)
    }

    @Test
    fun `parse releases skips pre release suffix even if github flag is false`() {
        val body =
            releasesJson(
                release("v1.1.0-beta1", asset("MYS_Signin_v1.1.0-beta1_signed.apk", sha = SHA_BETA)),
                release("v1.0.9", asset("MYS_Signin_v1.0.9_signed.apk", sha = SHA_9)),
            )

        val info = AppUpdateChecker.parseReleasesJsonForTest(body, currentVersion = "1.0.1")

        assertEquals("1.0.9", info.latestVersion)
        assertEquals(SHA_9, info.apkSha256)
    }

    @Test
    fun `parse releases rejects apk from another repository`() {
        val untrustedAsset =
            asset("MYS_Signin_v1.0.5_signed.apk", sha = SHA_4)
                .replace(
                    "https://github.com/bainian-gudu/QuestTick/",
                    "https://github.com/attacker/QuestTick/",
                )
        val body = releasesJson(release("v1.0.5", untrustedAsset))

        val info = AppUpdateChecker.parseReleasesJsonForTest(body, currentVersion = "1.0.1")

        assertFalse(info.hasUpdate)
        assertEquals("", info.apkUrl)
    }

    @Test
    fun `parse releases replaces untrusted release page`() {
        val body =
            releasesJson(release("v1.0.5", asset("MYS_Signin_v1.0.5_signed.apk", sha = SHA_4)))
                .replace(
                    "https://github.com/bainian-gudu/QuestTick/releases/tag/v1.0.5",
                    "https://evil.example/fake-release",
                )

        val info = AppUpdateChecker.parseReleasesJsonForTest(body, currentVersion = "1.0.1")

        assertEquals("https://github.com/bainian-gudu/QuestTick/releases", info.releaseUrl)
    }

    @Test
    fun `parse releases returns no update when no installable release exists`() {
        val body =
            releasesJson(
                release("v1.0.5", asset("MYS_Signin_v1.0.4_signed.apk", sha = SHA_4)),
                release("v1.0.6", asset("MYS_Signin_v1.0.6_unsigned.apk", sha = SHA_6)),
                release("v1.0.7", asset("MYS_Signin_v1.0.7_signed.apk", sha = SHA_7), draft = true),
            )

        val info = AppUpdateChecker.parseReleasesJsonForTest(body, currentVersion = "1.0.1")

        assertFalse(info.hasUpdate)
        assertEquals("1.0.1", info.latestVersion)
        assertEquals("", info.apkUrl)
    }

    private fun releasesJson(vararg releases: String): String = releases.joinToString(prefix = "[", postfix = "]")

    private fun release(
        tag: String,
        asset: String,
        draft: Boolean = false,
        prerelease: Boolean = false,
    ): String =
        """
        {
          "tag_name": "$tag",
          "name": "$tag",
          "draft": $draft,
          "prerelease": $prerelease,
          "html_url": "https://github.com/bainian-gudu/QuestTick/releases/tag/$tag",
          "published_at": "2026-07-03T00:00:00Z",
          "body": "notes $tag",
          "assets": [$asset]
        }
        """.trimIndent()

    private fun asset(
        name: String,
        sha: String,
    ): String {
        val digest = if (sha.isBlank()) "" else "sha256:$sha"
        return """
            {
              "name": "$name",
              "browser_download_url": "https://github.com/bainian-gudu/QuestTick/releases/download/v1/$name",
              "digest": "$digest"
            }
            """.trimIndent()
    }

    private companion object {
        const val SHA_2 = "2222222222222222222222222222222222222222222222222222222222222222"
        const val SHA_3 = "3333333333333333333333333333333333333333333333333333333333333333"
        const val SHA_4 = "4444444444444444444444444444444444444444444444444444444444444444"
        const val SHA_6 = "6666666666666666666666666666666666666666666666666666666666666666"
        const val SHA_7 = "7777777777777777777777777777777777777777777777777777777777777777"
        const val SHA_9 = "9999999999999999999999999999999999999999999999999999999999999999"
        const val SHA_BETA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
