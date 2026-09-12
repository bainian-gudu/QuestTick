package com.questtick.ui.theme

/*
 * 应用主题：Material3 配色方案与动态取色，统一浅色 / 深色与自定义主题色的呈现。
 */
import android.app.Activity
import android.os.Build
import android.graphics.drawable.ColorDrawable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

val TextSecondary = Color(0xFF6B7280)
val SuccessGreen = Color(0xFF34D399)
val WarnAmber = Color(0xFFF6B24A)
val DangerRed = Color(0xFFF65B6B)
val InfoBlue = Color(0xFF5AA8F0)

private val AppTypography = Typography()

/** 将主题色以低比例混入基础色，保留文字对比度与原有层次。 */
private fun blendColor(
    base: Color,
    tint: Color,
    amount: Float,
): Color {
    val factor = amount.coerceIn(0f, 1f)
    return Color(
        red = base.red + (tint.red - base.red) * factor,
        green = base.green + (tint.green - base.green) * factor,
        blue = base.blue + (tint.blue - base.blue) * factor,
        alpha = base.alpha,
    )
}

@Suppress("DEPRECATION")
@Composable
fun QuestTickTheme(
    themeMode: String = "SYSTEM",
    oledPureBlackEnabled: Boolean = false,
    dynamicColorEnabled: Boolean = false,
    customThemeColor: String = "",
    customThemeSecondaryColor: String = "",
    customThemeTertiaryColor: String = "",
    content: @Composable () -> Unit,
) {
    val darkTheme =
        when (themeMode.uppercase()) {
            "LIGHT" -> false
            "DARK" -> true
            else -> isSystemInDarkTheme()
        }
    val palette =
        if (darkTheme) {
            if (oledPureBlackEnabled) AppUiThemeCatalog.OledDark else AppUiThemeCatalog.Dark
        } else {
            AppUiThemeCatalog.Light
        }
    val customColor =
        runCatching {
            if (customThemeColor.isBlank()) null else Color(android.graphics.Color.parseColor(customThemeColor))
        }.getOrNull()
    val customSecondaryColor =
        runCatching {
            if (customThemeSecondaryColor.isBlank()) customColor else Color(android.graphics.Color.parseColor(customThemeSecondaryColor))
        }.getOrNull()
    val customTertiaryColor =
        runCatching {
            if (customThemeTertiaryColor.isBlank()) customColor else Color(android.graphics.Color.parseColor(customThemeTertiaryColor))
        }.getOrNull()
    // 动态配色优先；自定义颜色只在动态配色关闭时参与主题计算，设置值本身仍会保留。
    val useCustomColor = customColor != null && !dynamicColorEnabled
    val dynamicScheme =
        if (dynamicColorEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val context = LocalView.current.context
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else {
            null
        }
    val colorScheme =
        (
            dynamicScheme ?: if (darkTheme) {
                darkColorScheme(
                    primary = palette.brand,
                    onPrimary = Color(0xFF0B1120),
                    primaryContainer = palette.cardElevated,
                    onPrimaryContainer = palette.textPrimary,
                    secondary = palette.brandAlt,
                    onSecondary = Color(0xFF0B1120),
                    background = palette.backgroundBottom,
                    onBackground = palette.textPrimary,
                    surface = palette.card,
                    onSurface = palette.textPrimary,
                    surfaceVariant = palette.field,
                    onSurfaceVariant = palette.textSecondary,
                    outline = palette.hairline,
                    outlineVariant = palette.hairline,
                    error = DangerRed,
                    onError = Color.White,
                )
            } else {
                lightColorScheme(
                    primary = palette.brand,
                    onPrimary = Color.White,
                    primaryContainer = palette.cardElevated,
                    onPrimaryContainer = palette.textPrimary,
                    secondary = palette.brandAlt,
                    onSecondary = Color.White,
                    background = palette.backgroundBottom,
                    onBackground = palette.textPrimary,
                    surface = palette.card,
                    onSurface = palette.textPrimary,
                    surfaceVariant = palette.field,
                    onSurfaceVariant = palette.textSecondary,
                    outline = palette.hairline,
                    outlineVariant = palette.hairline,
                    error = DangerRed,
                    onError = Color.White,
                )
            }
        ).let { scheme ->
            if (useCustomColor) {
                // 自定义色同时映射到背景、卡片、输入区域和边框，保证全局观感一致。
                val customBackground =
                    if (darkTheme) {
                        blendColor(scheme.background, customColor, 0.08f)
                    } else {
                        blendColor(scheme.background, customColor, 0.035f)
                    }
                scheme.copy(
                    primary = customColor,
                    secondary = customSecondaryColor ?: customColor,
                    tertiary = customTertiaryColor ?: customColor,
                    primaryContainer = blendColor(scheme.primaryContainer, customColor, if (darkTheme) 0.32f else 0.16f),
                    background = customBackground,
                    surface = blendColor(scheme.surface, customColor, if (darkTheme) 0.14f else 0.06f),
                    surfaceVariant = blendColor(scheme.surfaceVariant, customColor, if (darkTheme) 0.2f else 0.1f),
                    outline = blendColor(scheme.outline, customColor, 0.28f),
                    outlineVariant = blendColor(scheme.outlineVariant, customColor, 0.22f),
                )
            } else {
                scheme
            }
        }.let { scheme ->
            if (darkTheme && oledPureBlackEnabled) {
                scheme.copy(
                    background = palette.backgroundBottom,
                    surface = palette.card,
                    surfaceVariant = palette.field,
                )
            } else {
                scheme
            }
        }

    // Dynamic color must reach components that use the app palette directly (cards and bottom bar),
    // not only Material components. Preserve the same resolved surface hierarchy everywhere.
    val resolvedPalette =
        if (dynamicScheme != null || useCustomColor) {
            AppUiThemePalette(
                brand = colorScheme.primary,
                brandAlt = colorScheme.secondary,
                backgroundTop = colorScheme.background,
                backgroundBottom = colorScheme.background,
                card = colorScheme.surface,
                cardElevated = colorScheme.surfaceVariant,
                field = colorScheme.surfaceVariant,
                textPrimary = colorScheme.onSurface,
                textSecondary = colorScheme.onSurfaceVariant,
                hairline = colorScheme.outline,
                accent = colorScheme.tertiary,
            )
        } else {
            palette
        }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            ThemeModeController.syncApplicationNightMode(view.context, themeMode)
            val window = (view.context as Activity).window
            // Use the resolved Material background so dynamic colors also reach the cutout/status and navigation areas.
            window.setBackgroundDrawable(ColorDrawable(colorScheme.background.toArgb()))
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(LocalAppUiTheme provides resolvedPalette) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AppTypography,
            content = content,
        )
    }
}
