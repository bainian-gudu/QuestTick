package com.questtick.i18n

import java.util.Locale
import org.junit.Assert.assertEquals
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
}
