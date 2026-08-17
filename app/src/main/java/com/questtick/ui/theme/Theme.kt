package com.questtick.ui.theme

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

@Suppress("DEPRECATION")
@Composable
fun QuestTickTheme(
    themeMode: String = "SYSTEM",
    oledPureBlackEnabled: Boolean = false,
    dynamicColorEnabled: Boolean = false,
    content: @Composable () -> Unit,
) {
    val darkTheme =
        when (themeMode.uppercase()) {
            "LIGHT" -> false
            "DARK" -> true
            else -> isSystemInDarkTheme()
        }
    val palette = if (darkTheme) {
        if (oledPureBlackEnabled) AppUiThemeCatalog.OledDark else AppUiThemeCatalog.Dark
    } else {
        AppUiThemeCatalog.Light
    }
    val dynamicScheme = if (dynamicColorEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val context = LocalView.current.context
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        null
    }
    val colorScheme = (dynamicScheme ?: if (darkTheme) {
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
        }).let { scheme ->
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

    CompositionLocalProvider(LocalAppUiTheme provides palette) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AppTypography,
            content = content,
        )
    }
}
