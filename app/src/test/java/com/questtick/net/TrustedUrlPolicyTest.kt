package com.questtick.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustedUrlPolicyTest {
    @Test
    fun `activity and reward resources use separate exact host lists`() {
        assertTrue(TrustedUrlPolicy.isActivityResourceUrl("https://act.mihoyo.com/bbs/event/index.html"))
        assertTrue(TrustedUrlPolicy.isActivityResourceUrl("https://webstatic.mihoyo.com/js/main.js"))
        assertFalse(TrustedUrlPolicy.isActivityResourceUrl("https://upload-bbs.miyoushe.com/upload/icon.png"))
        assertFalse(TrustedUrlPolicy.isActivityResourceUrl("https://act.mihoyo.com.evil.example/main.js"))
        assertFalse(TrustedUrlPolicy.isActivityResourceUrl("https://mihoyo.com/main.js"))
        assertFalse(TrustedUrlPolicy.isActivityResourceUrl("https://act.mihoyо.com/main.js"))

        assertTrue(TrustedUrlPolicy.isRewardIconUrl("https://upload-bbs.miyoushe.com/upload/icon.png"))
        assertTrue(TrustedUrlPolicy.isRewardIconUrl("https://upload-bbs.mihoyo.com/upload/icon.png"))
        assertTrue(TrustedUrlPolicy.isRewardIconUrl("https://uploadstatic.mihoyo.com/icon.webp"))
        assertFalse(TrustedUrlPolicy.isRewardIconUrl("https://act.mihoyo.com/icon.png"))
        assertFalse(TrustedUrlPolicy.isRewardIconUrl("https://upload-bbs.miyoushe.com.evil.example/icon.png"))
    }

    @Test
    fun `business requests only accept fixed service hosts`() {
        assertTrue(TrustedUrlPolicy.isBusinessRequestUrl("https://api-takumi.mihoyo.com/event/luna/info"))
        assertTrue(TrustedUrlPolicy.isBusinessRequestUrl("https://passport-api.mihoyo.com/account/status"))
        assertTrue(TrustedUrlPolicy.isBusinessRequestUrl("https://api-cloudgame.mihoyo.com/hk4e_cg_cn/wallet/wallet/get"))
        assertTrue(TrustedUrlPolicy.isBusinessRequestUrl("https://itunes.apple.com/cn/lookup?id=1470182559"))
        assertFalse(TrustedUrlPolicy.isBusinessRequestUrl("https://evil.example/event/luna/info"))
        assertFalse(TrustedUrlPolicy.isBusinessRequestUrl("https://api-takumi.mihoyo.com.evil.example/event/luna/info"))
        assertFalse(TrustedUrlPolicy.isBusinessRequestUrl("http://api-takumi.mihoyo.com/event/luna/info"))
    }

    @Test
    fun `common URL parser rejects unsafe authority and transport forms`() {
        val path = "/bbs/event/index.html"
        assertFalse(TrustedUrlPolicy.isActivityResourceUrl("http://act.mihoyo.com$path"))
        assertFalse(TrustedUrlPolicy.isActivityResourceUrl("https://act.mihoyo.com:444$path"))
        assertFalse(TrustedUrlPolicy.isActivityResourceUrl("https://user@act.mihoyo.com$path"))
        assertFalse(TrustedUrlPolicy.isActivityResourceUrl("https://user:pass@act.mihoyo.com$path"))
        assertFalse(TrustedUrlPolicy.isActivityResourceUrl("https://127.0.0.1$path"))
        assertFalse(TrustedUrlPolicy.isActivityResourceUrl("https://[::1]$path"))
        assertFalse(TrustedUrlPolicy.isActivityResourceUrl(" https://act.mihoyo.com$path"))
        assertFalse(TrustedUrlPolicy.isActivityResourceUrl("https://act.mihoyo.com\\@evil.example$path"))
        assertFalse(TrustedUrlPolicy.isActivityResourceUrl("https://act.mihoyo.com$path#fragment"))
        assertFalse(TrustedUrlPolicy.isActivityResourceUrl("https://act.mihoyo.com/$path\nhttps://evil.example"))
    }

    @Test
    fun `update metadata only accepts exact repository API`() {
        val latest = "https://api.github.com/repos/bainian-gudu/QuestTick/releases/latest"
        val releases = "https://api.github.com/repos/bainian-gudu/QuestTick/releases?per_page=20"
        assertTrue(TrustedUrlPolicy.isUpdateMetadataUrl(latest))
        assertTrue(TrustedUrlPolicy.isUpdateMetadataUrl(releases))
        assertFalse(TrustedUrlPolicy.isUpdateMetadataUrl("https://api.github.com/repos/other/QuestTick/releases/latest"))
        assertFalse(TrustedUrlPolicy.isUpdateMetadataUrl("https://api.github.com/repos/bainian-gudu/QuestTick/releases?per_page=100"))
        assertFalse(TrustedUrlPolicy.isUpdateMetadataUrl("https://api.github.com/repos/bainian-gudu/QuestTick/releases?per_page=20&x=1"))
        assertFalse(TrustedUrlPolicy.isUpdateMetadataUrl("https://api.github.com.evil.example/repos/bainian-gudu/QuestTick/releases/latest"))
    }

    @Test
    fun `update asset only accepts current repository release download`() {
        val asset =
            "https://github.com/bainian-gudu/QuestTick/releases/download/v1.2.3/QuestTick_v1.2.3_signed.apk"
        assertTrue(TrustedUrlPolicy.isUpdateAssetUrl(asset))
        assertFalse(TrustedUrlPolicy.isUpdateAssetUrl("https://github.com/other/QuestTick/releases/download/v1/app.apk"))
        assertFalse(TrustedUrlPolicy.isUpdateAssetUrl("https://github.com/bainian-gudu/QuestTick/archive/main.zip"))
        assertFalse(TrustedUrlPolicy.isUpdateAssetUrl("https://objects.githubusercontent.com/github-production-release-asset/app.apk"))
        assertFalse(TrustedUrlPolicy.isUpdateAssetUrl("https://github.com.evil.example/bainian-gudu/QuestTick/releases/download/v1/app.apk"))
    }

    @Test
    fun `trusted mirror must wrap one direct trusted source`() {
        val source =
            "https://github.com/bainian-gudu/QuestTick/releases/download/v1.2.3/QuestTick_v1.2.3_signed.apk"
        val mirror = "https://ghfast.top/$source"
        val metadataSource =
            "https://api.github.com/repos/bainian-gudu/QuestTick/releases?per_page=20"
        val metadataMirror = "https://ghproxy.net/$metadataSource"
        assertTrue(TrustedUrlPolicy.isUpdateAssetUrl(mirror))
        assertEquals(source, TrustedUrlPolicy.trustedMirrorSource(mirror))
        assertTrue(TrustedUrlPolicy.isUpdateMetadataUrl(metadataMirror))
        assertEquals(metadataSource, TrustedUrlPolicy.trustedMirrorSource(metadataMirror))

        assertFalse(TrustedUrlPolicy.isUpdateAssetUrl("https://evil.example/$source"))
        assertFalse(TrustedUrlPolicy.isUpdateAssetUrl("https://ghfast.top/https://evil.example/app.apk"))
        assertFalse(TrustedUrlPolicy.isUpdateAssetUrl("https://ghfast.top/https://ghproxy.net/$source"))
        assertFalse(TrustedUrlPolicy.isUpdateAssetUrl("https://ghfast.top.evil.example/$source"))
        assertNull(TrustedUrlPolicy.trustedMirrorSource(source))
    }

    @Test
    fun `update metadata redirects stay within the exact candidate source`() {
        val direct =
            "https://api.github.com/repos/bainian-gudu/QuestTick/releases?per_page=20"
        val latest =
            "https://api.github.com/repos/bainian-gudu/QuestTick/releases/latest"
        val mirror = "https://ghfast.top/$direct"

        assertTrue(TrustedUrlPolicy.isUpdateMetadataRedirect(direct, direct))
        assertTrue(TrustedUrlPolicy.isUpdateMetadataRedirect(mirror, mirror))
        assertFalse(TrustedUrlPolicy.isUpdateMetadataRedirect(direct, latest))
        assertFalse(TrustedUrlPolicy.isUpdateMetadataRedirect(direct, mirror))
        assertFalse(TrustedUrlPolicy.isUpdateMetadataRedirect(mirror, direct))
        assertFalse(
            TrustedUrlPolicy.isUpdateMetadataRedirect(
                direct,
                "https://api.github.com/repos/other/repository/releases?per_page=20",
            ),
        )
    }

    @Test
    fun `update asset redirects only allow official asset delivery or the original mirror`() {
        val source =
            "https://github.com/bainian-gudu/QuestTick/releases/download/v1.2.3/QuestTick_v1.2.3_signed.apk"
        val officialAsset =
            "https://release-assets.githubusercontent.com/github-production-release-asset/1337123825/asset.apk?token=signed"
        val mirror = "https://ghfast.top/$source"

        assertTrue(TrustedUrlPolicy.isUpdateAssetRedirect(source, source))
        assertTrue(TrustedUrlPolicy.isUpdateAssetRedirect(source, officialAsset))
        assertTrue(TrustedUrlPolicy.isUpdateAssetRedirect(mirror, mirror))
        assertFalse(TrustedUrlPolicy.isUpdateAssetRedirect(source, "http://release-assets.githubusercontent.com/file"))
        assertFalse(TrustedUrlPolicy.isUpdateAssetRedirect(source, "https://release-assets.githubusercontent.com/other/file"))
        assertFalse(TrustedUrlPolicy.isUpdateAssetRedirect(source, "https://objects.githubusercontent.com/file"))
        assertFalse(TrustedUrlPolicy.isUpdateAssetRedirect(mirror, source))
        assertFalse(TrustedUrlPolicy.isUpdateAssetRedirect(mirror, "https://ghproxy.net/$source"))
        assertFalse(TrustedUrlPolicy.isUpdateAssetRedirect(mirror, officialAsset))
    }

    @Test
    fun `release page only accepts current repository`() {
        assertTrue(TrustedUrlPolicy.isUpdateReleasePageUrl("https://github.com/bainian-gudu/QuestTick/releases"))
        assertTrue(TrustedUrlPolicy.isUpdateReleasePageUrl("https://github.com/bainian-gudu/QuestTick/releases/tag/v1.2.3"))
        assertFalse(TrustedUrlPolicy.isUpdateReleasePageUrl("https://github.com/other/QuestTick/releases"))
        assertFalse(TrustedUrlPolicy.isUpdateReleasePageUrl("https://github.com/bainian-gudu/QuestTick/issues"))
    }
}
