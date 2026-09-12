package com.questtick.data

/*
 * 签到日历的业务日口径：统一按米游社业务时区（UTC+8）计算 dayKey，避免用户手动改机或跨时区旅行导致日期错乱。
 */
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** 每次读取设备当前系统时区，避免时区变化后缓存旧对象。 */
internal val SIGN_IN_TIME_ZONE: TimeZone
    get() = TimeZone.getDefault()

/** 按设备当前系统时区生成业务日键；语言切换不会影响日期边界。 */
internal fun dayKey(
    timestamp: Long,
    timeZone: TimeZone = TimeZone.getDefault(),
): String {
    val c = Calendar.getInstance(timeZone, Locale.ROOT).apply { timeInMillis = timestamp }
    return dayKey(c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH))
}

internal fun dayKey(
    year: Int,
    month: Int,
    day: Int,
): String = "%04d%02d%02d".format(Locale.US, year, month + 1, day)
