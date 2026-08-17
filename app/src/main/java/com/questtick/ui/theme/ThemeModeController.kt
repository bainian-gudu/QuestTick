package com.questtick.ui.theme

import android.app.UiModeManager
import android.content.Context
import android.os.Build

/** 将应用内外观模式同步给系统，确保 Android 12+ 启动闪屏也能使用正确日夜间资源。 */
object ThemeModeController {
    fun syncApplicationNightMode(
        context: Context,
        themeMode: String,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val uiModeManager = context.getSystemService(UiModeManager::class.java) ?: return
        val mode =
            when (themeMode.uppercase()) {
                "LIGHT" -> UiModeManager.MODE_NIGHT_NO
                "DARK" -> UiModeManager.MODE_NIGHT_YES
                else -> UiModeManager.MODE_NIGHT_AUTO
            }
        runCatching { uiModeManager.setApplicationNightMode(mode) }
    }
}
