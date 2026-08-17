package com.questtick.i18n

import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 语言切换后不复用旧 Locale 的日期格式器。 */
private class LocaleAwareDateFormatter(
    private val factory: (Locale) -> DateFormat,
) {
    private val cache = ThreadLocal<Pair<String, DateFormat>>()

    fun format(timestamp: Long): String {
        val locale = Locale.getDefault()
        val tag = locale.toLanguageTag()
        val current = cache.get()
        val formatter =
            if (current?.first == tag) {
                current.second
            } else {
                factory(locale).also { cache.set(tag to it) }
            }
        return formatter.format(Date(timestamp))
    }
}

private val fullDateFormatter = LocaleAwareDateFormatter { DateFormat.getDateInstance(DateFormat.FULL, it) }
private val clockFormatter = LocaleAwareDateFormatter { SimpleDateFormat("HH:mm", it) }
private val secondFormatter = LocaleAwareDateFormatter { SimpleDateFormat("ss", it) }
private val shortDateTimeFormatter = LocaleAwareDateFormatter { SimpleDateFormat("MM-dd HH:mm", it) }

fun formatLocalizedFullDate(timestamp: Long): String = fullDateFormatter.format(timestamp)

fun formatLocalizedClock(timestamp: Long): String = clockFormatter.format(timestamp)

fun formatLocalizedSecond(timestamp: Long): String = secondFormatter.format(timestamp)

fun formatLocalizedShortDateTime(timestamp: Long): String = shortDateTimeFormatter.format(timestamp)
