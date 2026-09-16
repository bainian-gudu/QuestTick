package com.questtick.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

private const val LIGHT_BRAND_COLOR = 0xFF2563EB
private const val LIGHT_BRAND_ALT_COLOR = 0xFF60A5FA
private const val LIGHT_TEXT_PRIMARY_COLOR = 0xFF111827
private const val LIGHT_TEXT_SECONDARY_COLOR = 0xFF6B7280
private const val LIGHT_HAIRLINE_COLOR = 0xFF93C5FD
private const val LIGHT_ACCENT_COLOR = 0xFF3B82F6
private const val DARK_BRAND_COLOR = 0xFF60A5FA
private const val DARK_BRAND_ALT_COLOR = 0xFF93C5FD
private const val DARK_BACKGROUND_COLOR = 0xFF0B1120
private const val DARK_CARD_COLOR = 0xFF111827
private const val DARK_CARD_ELEVATED_COLOR = 0xFF1F2937
private const val DARK_FIELD_COLOR = 0xFF182033
private const val DARK_TEXT_PRIMARY_COLOR = 0xFFE5E7EB
private const val DARK_TEXT_SECONDARY_COLOR = 0xFF9CA3AF
private const val DARK_HAIRLINE_COLOR = 0xFF334155

/** 应用主题调色板：浅色 / 深色共用同一套业务色语义。 */
@Immutable
data class AppUiThemePalette(
    val brand: Color,
    val brandAlt: Color,
    val backgroundTop: Color,
    val backgroundBottom: Color,
    val card: Color,
    val cardElevated: Color,
    val field: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val hairline: Color,
    val accent: Color,
)

object AppUiThemeCatalog {
    val Light =
        AppUiThemePalette(
            brand = Color(LIGHT_BRAND_COLOR),
            brandAlt = Color(LIGHT_BRAND_ALT_COLOR),
            backgroundTop = Color.White,
            backgroundBottom = Color.White,
            card = Color.White,
            cardElevated = Color.White,
            field = Color.White,
            textPrimary = Color(LIGHT_TEXT_PRIMARY_COLOR),
            textSecondary = Color(LIGHT_TEXT_SECONDARY_COLOR),
            hairline = Color(LIGHT_HAIRLINE_COLOR),
            accent = Color(LIGHT_ACCENT_COLOR),
        )

    val Dark =
        AppUiThemePalette(
            brand = Color(DARK_BRAND_COLOR),
            brandAlt = Color(DARK_BRAND_ALT_COLOR),
            backgroundTop = Color(DARK_BACKGROUND_COLOR),
            backgroundBottom = Color(DARK_BACKGROUND_COLOR),
            card = Color(DARK_CARD_COLOR),
            cardElevated = Color(DARK_CARD_ELEVATED_COLOR),
            field = Color(DARK_FIELD_COLOR),
            textPrimary = Color(DARK_TEXT_PRIMARY_COLOR),
            textSecondary = Color(DARK_TEXT_SECONDARY_COLOR),
            hairline = Color(DARK_HAIRLINE_COLOR),
            accent = Color(DARK_BRAND_COLOR),
        )

    /** OLED/AMOLED 模式：仅将大面积背景归零，卡片仍保留层次以维持可读性。 */
    val OledDark =
        Dark.copy(
            backgroundTop = Color.Black,
            backgroundBottom = Color.Black,
        )

    val Default = Light
}

val LocalAppUiTheme = staticCompositionLocalOf { AppUiThemeCatalog.Default }
