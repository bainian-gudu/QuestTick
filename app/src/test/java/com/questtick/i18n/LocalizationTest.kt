package com.questtick.i18n

import java.util.Locale
import com.questtick.i18n.languages.enTranslations
import com.questtick.i18n.languages.jaTranslations
import com.questtick.i18n.languages.koTranslations
import com.questtick.i18n.languages.zhHantTranslations
import com.questtick.i18n.languages.zhHansTranslations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalizationTest {
    @Test
    fun `unsupported device locale falls back to English`() {
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromDeviceLocale(Locale.forLanguageTag("fr-FR")))
    }

    @Test
    fun `traditional Chinese regions map to traditional Chinese`() {
        assertEquals(AppLanguage.TRADITIONAL_CHINESE, AppLanguage.fromDeviceLocale(Locale.forLanguageTag("zh-TW")))
        assertEquals(AppLanguage.TRADITIONAL_CHINESE, AppLanguage.fromDeviceLocale(Locale.forLanguageTag("zh-HK")))
        assertEquals(AppLanguage.TRADITIONAL_CHINESE, AppLanguage.fromDeviceLocale(Locale.forLanguageTag("zh-Hant")))
    }

    @Test
    fun `manual language overrides device locale`() {
        assertEquals(AppLanguage.JAPANESE, AppLanguage.resolve("JA", Locale.SIMPLIFIED_CHINESE))
    }

    @Test
    fun `official game names use localized forms`() {
        assertEquals("Genshin Impact", localizeText("原神", AppLanguage.ENGLISH))
        assertEquals("崩壊：スターレイル", localizeText("崩坏：星穹铁道", AppLanguage.JAPANESE))
        assertEquals("붕괴: 넥서스 아니마", localizeText("崩坏：因缘精灵", AppLanguage.KOREAN))
    }

    @Test
    fun `language option names use the selected interface language`() {
        assertEquals("Traditional Chinese", localizeText("繁体中文", AppLanguage.ENGLISH))
        assertEquals("繁體中文", localizeText("繁体中文", AppLanguage.TRADITIONAL_CHINESE))
    }

    @Test
    fun `dynamic task count is localized without mixed language`() {
        assertEquals("12 tasks", localizeText("12 项任务", AppLanguage.ENGLISH))
        assertEquals("12 件のタスク", localizeText("12 项任务", AppLanguage.JAPANESE))
        assertEquals("12개 작업", localizeText("12 项任务", AppLanguage.KOREAN))
    }

    @Test
    fun `traditional templates preserve custom account label`() {
        assertEquals(
            "確定刪除「开发号」嗎？此操作無法復原。",
            localizeText("确定删除「开发号」吗？该操作不可恢复。", AppLanguage.TRADITIONAL_CHINESE),
        )
    }

    @Test
    fun `unknown external text is not guessed or rewritten`() {
        val external = "服务器返回：账号昵称开发号"
        assertEquals(external, localizeText(external, AppLanguage.ENGLISH))
        assertEquals(external, localizeText(external, AppLanguage.TRADITIONAL_CHINESE))
    }

    @Test
    fun `every language catalog has the same complete key set`() {
        val catalogs = listOf(zhHansTranslations, zhHantTranslations, enTranslations, jaTranslations, koTranslations)
        val keys = catalogs.first().keys
        catalogs.drop(1).forEach { catalog ->
            assertEquals("language catalog keys must stay aligned", keys, catalog.keys)
        }
        catalogs.forEach { catalog ->
            catalog.forEach { (key, value) ->
                assertTrue("blank translation for key: $key", value.isNotBlank())
            }
        }
    }

    @Test
    fun `static catalog translations are reachable through the public lookup`() {
        assertEquals(zhHansTranslations.size, exactTranslations.size)
        zhHansTranslations.keys.forEach { key ->
            assertEquals(enTranslations.getValue(key), localizeText(key, AppLanguage.ENGLISH))
            assertEquals(jaTranslations.getValue(key), localizeText(key, AppLanguage.JAPANESE))
            assertEquals(koTranslations.getValue(key), localizeText(key, AppLanguage.KOREAN))
        }
    }

    @Test
    fun `traditional Chinese uses natural UI terminology`() {
        assertEquals("首頁", localizeText("主页", AppLanguage.TRADITIONAL_CHINESE))
        assertEquals("帳號", localizeText("账号", AppLanguage.TRADITIONAL_CHINESE))
        assertEquals("重新載入", localizeText("重新加载", AppLanguage.TRADITIONAL_CHINESE))
        assertEquals("掃描 QR Code 登入", localizeText("扫码登录", AppLanguage.TRADITIONAL_CHINESE))
        assertTrue(zhHantTranslations.keys.containsAll(listOf("主页", "账号", "设置", "扫码登录")))
    }

    @Test
    fun `traditional Chinese localizes runtime log group title`() {
        assertEquals(
            "執行日誌 · 12:34",
            localizeText("运行日志 · 12:34", AppLanguage.TRADITIONAL_CHINESE),
        )
    }

    @Test
    fun `multi-line messages resolve through dictionary templates`() {
        assertEquals(
            "Current version: 1.0.0\nNew version: 1.1.0",
            localizeText("当前版本：1.0.0\n新版本：1.1.0", AppLanguage.ENGLISH),
        )
        assertEquals(
            "Open the Miyoushe app → tap the QR icon in the upper-left\n" +
                "Scan the QR code above and confirm the login on your phone 空灵",
            localizeText(
                "请打开「米游社」App → 点击左上角扫码图标\n" +
                    "扫描上方二维码并在手机端确认登录 空灵",
                AppLanguage.ENGLISH,
            ),
        )
        assertEquals(
            "📱 小米 detected. Enable auto-start as well:\n步骤",
            localizeText("📱 检测到 小米 设备，建议同时开启自启动权限：\n步骤", AppLanguage.ENGLISH),
        )
    }

    @Test
    fun `log group titles follow localized log type`() {
        assertEquals("Warning logs · 12:34", localizeText("警告日志 · 12:34", AppLanguage.ENGLISH))
        assertEquals("資訊日誌 · 12:34", localizeText("信息日志 · 12:34", AppLanguage.TRADITIONAL_CHINESE))
        assertEquals("디버그 로그 · 12:34", localizeText("调试日志 · 12:34", AppLanguage.KOREAN))
    }

    @Test
    fun `dynamic interface summaries use dictionary templates`() {
        assertEquals("Custom: Device ID", localizeText("已自定义：Device ID", AppLanguage.ENGLISH))
        assertEquals(
            "目前：雲原神 5.1 / 雲崩鐵 3.0",
            localizeText("当前：云原神 5.1 / 云崩铁 3.0", AppLanguage.TRADITIONAL_CHINESE),
        )
        assertEquals(
            "エラー：2/10件・1グループ",
            localizeText("错误：2/10 条 · 1 组", AppLanguage.JAPANESE),
        )
    }

    @Test
    fun `traditional Chinese has no character conversion fallback`() {
        val unknown = "未收录的简体文本"
        assertEquals(unknown, localizeText(unknown, AppLanguage.TRADITIONAL_CHINESE))
    }
}
