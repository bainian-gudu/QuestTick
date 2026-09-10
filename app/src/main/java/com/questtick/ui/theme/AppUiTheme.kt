package com.questtick.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

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
            brand = Color(0xFF2563EB),
            brandAlt = Color(0xFF60A5FA),
            backgroundTop = Color.White,
            backgroundBottom = Color.White,
            card = Color.White,
            cardElevated = Color.White,
            field = Color.White,
            textPrimary = Color(0xFF111827),
            textSecondary = Color(0xFF6B7280),
            hairline = Color(0xFF93C5FD),
            accent = Color(0xFF3B82F6),
        )

    val Dark =
        AppUiThemePalette(
            brand = Color(0xFF60A5FA),
            brandAlt = Color(0xFF93C5FD),
            backgroundTop = Color(0xFF0B1120),
            backgroundBottom = Color(0xFF0B1120),
            card = Color(0xFF111827),
            cardElevated = Color(0xFF1F2937),
            field = Color(0xFF182033),
            textPrimary = Color(0xFFE5E7EB),
            textSecondary = Color(0xFF9CA3AF),
            hairline = Color(0xFF334155),
            accent = Color(0xFF60A5FA),
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
