package com.questtick

/** 全局 UI 数值格式化工具。 */

internal fun formatReadableBytes(bytes: Long): String {
    val value = bytes.coerceAtLeast(0L).toDouble()
    val units = listOf("B", "KB", "MB", "GB")
    var scaled = value
    var index = 0
    while (scaled >= 1024.0 && index < units.lastIndex) {
        scaled /= 1024.0
        index++
    }
    return if (index == 0) {
        "${scaled.toLong()} ${units[index]}"
    } else {
        "%.1f %s".format(scaled, units[index])
    }
}
