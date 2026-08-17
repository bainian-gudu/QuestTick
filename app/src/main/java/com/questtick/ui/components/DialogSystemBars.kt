package com.questtick.ui.components

import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.questtick.ui.theme.LocalAppUiTheme

/**
 * 为 Compose Dialog 的独立窗口同步状态栏与导航栏颜色。
 *
 * Dialog 不共享主 Activity 的 Edge-to-Edge 窗口设置，默认可能出现黑色系统栏；在 Dialog 内容开头
 * 调用本函数，可让系统栏与应用纯白背景保持一致。
 */
@Suppress("DEPRECATION")
@Composable
fun DialogSystemBars(
    statusBarColor: Color? = null,
    navigationBarColor: Color? = null,
    lightIcons: Boolean = true,
) {
    val view = LocalView.current
    if (view.isInEditMode) return
    val theme = LocalAppUiTheme.current
    val resolvedStatusBarColor = statusBarColor ?: theme.backgroundTop
    val resolvedNavigationBarColor = navigationBarColor ?: theme.backgroundBottom

    SideEffect {
        val window = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        window.setBackgroundDrawable(ColorDrawable(Color.Transparent.toArgb()))
        window.setDimAmount(0f)
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = resolvedStatusBarColor.toArgb()
        window.navigationBarColor = resolvedNavigationBarColor.toArgb()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = lightIcons
            isAppearanceLightNavigationBars = lightIcons
        }
    }
}
