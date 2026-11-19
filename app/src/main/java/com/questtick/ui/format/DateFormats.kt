package com.questtick.ui.format

// 界面与通知里统一使用的固定日期时间格式。
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val fullDateFormatter = DateFormat.getDateInstance(DateFormat.FULL, Locale.CHINA)
private val clockFormatter = SimpleDateFormat("HH:mm", Locale.CHINA)
private val secondFormatter = SimpleDateFormat("ss", Locale.CHINA)
private val shortDateTimeFormatter = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)

fun formatFullDate(timestamp: Long): String = fullDateFormatter.format(Date(timestamp))

fun formatClock(timestamp: Long): String = clockFormatter.format(Date(timestamp))

fun formatSecond(timestamp: Long): String = secondFormatter.format(Date(timestamp))

fun formatShortDateTime(timestamp: Long): String = shortDateTimeFormatter.format(Date(timestamp))
