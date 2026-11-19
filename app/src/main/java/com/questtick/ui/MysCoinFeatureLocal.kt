package com.questtick.ui

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 米游币打卡功能是否对界面可见。
 *
 * 真正的判定在 mysCoinFeatureEnabled(settings) 里；这里只是把同一次设置快照传给 UI，
 * 避免每个卡片各自去读一遍设置或依赖进程级缓存。
 */
val LocalMysCoinFeatureEnabled = staticCompositionLocalOf { false }
