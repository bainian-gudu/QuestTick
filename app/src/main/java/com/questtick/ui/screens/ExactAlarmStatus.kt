package com.questtick.ui.screens

import android.os.Build

/**
 * 精确闹钟权限状态。
 *
 * Android 12 起可以在系统设置里单独授予/回收该权限；未授权时只能退回宽松闹钟，
 * 触发时刻可能被系统推迟数分钟，因此设置页需要把状态显式展示出来。
 */
internal enum class ExactAlarmStatus {
    UNSUPPORTED,
    GRANTED,
    DENIED,
}

internal fun exactAlarmStatus(
    sdkInt: Int,
    canScheduleExactAlarms: Boolean,
): ExactAlarmStatus =
    when {
        sdkInt < Build.VERSION_CODES.S -> ExactAlarmStatus.UNSUPPORTED
        canScheduleExactAlarms -> ExactAlarmStatus.GRANTED
        else -> ExactAlarmStatus.DENIED
    }
