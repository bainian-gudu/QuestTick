package com.questtick.ui.components

/*
 * 签到月历卡片：把任务级的签到状态按月聚合为每日成败，并附状态图例与当月汇总。
 */
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import com.questtick.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.questtick.data.SIGN_IN_TIME_ZONE
import com.questtick.data.SignInCalendarDay
import com.questtick.data.dayKey
import com.questtick.ui.theme.DangerRed
import com.questtick.ui.theme.LocalAppUiTheme
import com.questtick.ui.theme.SuccessGreen
import com.questtick.ui.theme.WarnAmber
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

private const val MILLIS_PER_DAY = 86_400_000L

/** 首页签到日历：按月展示每日签到状态。 */
@Composable
fun SignInCalendarCard(
    days: List<SignInCalendarDay>,
    currentMillis: Long,
    modifier: Modifier = Modifier,
) {
    val theme = LocalAppUiTheme.current
    val timeZone = SIGN_IN_TIME_ZONE
    val localDay = Math.floorDiv(currentMillis + timeZone.getOffset(currentMillis), MILLIS_PER_DAY)
    val currentDays by rememberUpdatedState(days)
    val calendar by
        remember(localDay, timeZone.id) {
            derivedStateOf {
                buildCalendarUi(currentDays, currentMillis, timeZone)
            }
        }

    PanelCard(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "签到日历",
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Black,
                    textAlign = TextAlign.Center,
                )
                Text(
                    calendar.monthTitle,
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CalendarSummaryChip("本月 ${calendar.signedDays} 天", theme.accent)
                    CalendarSummaryChip("连续 ${calendar.streakDays} 天", MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Spacer(Modifier.height(12.dp))
            CalendarLegend()
            Spacer(Modifier.height(12.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("一", "二", "三", "四", "五", "六", "日").forEach { label ->
                    Text(
                        label,
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                calendar.rows.forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { cell ->
                            CalendarDayCell(cell = cell, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarLegend() {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LegendItem("成功", SuccessGreen)
        LegendItem("部分", WarnAmber)
        LegendItem("失败", DangerRed)
        LegendItem("未同步", MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LegendItem(
    label: String,
    color: Color,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Spacer(Modifier.size(4.dp))
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
    }
}

@Composable
private fun CalendarSummaryChip(
    text: String,
    color: Color,
) {
    StatusBadge(
        text = text,
        color = color,
        shape = CircleShape,
        horizontalPadding = 10.dp,
        verticalPadding = 4.dp,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        backgroundAlpha = 0.12f,
        borderAlpha = 0.22f,
    )
}

@Composable
private fun CalendarDayCell(
    cell: CalendarDayUi?,
    modifier: Modifier = Modifier,
) {
    if (cell == null) {
        Spacer(modifier.height(34.dp))
        return
    }

    val theme = LocalAppUiTheme.current
    val statusColor =
        when (cell.status) {
            DayStatus.Success -> SuccessGreen
            DayStatus.Partial -> WarnAmber
            DayStatus.Failed -> DangerRed
            DayStatus.None -> MaterialTheme.colorScheme.onSurfaceVariant
        }
    val bgColor =
        when (cell.status) {
            DayStatus.None -> Color.White.copy(alpha = if (cell.isToday) 0.08f else 0.035f)
            else -> statusColor.copy(alpha = 0.18f)
        }
    val borderColor =
        when {
            cell.isToday -> theme.accent.copy(alpha = 0.85f)
            cell.status != DayStatus.None -> statusColor.copy(alpha = 0.36f)
            else -> theme.hairline.copy(alpha = 0.65f)
        }

    Box(
        modifier
            .height(34.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(11.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "${cell.day}",
            color = if (cell.status == DayStatus.None) MaterialTheme.colorScheme.onSurfaceVariant else statusColor,
            fontSize = 13.sp,
            fontWeight = if (cell.isToday || cell.status != DayStatus.None) FontWeight.Bold else FontWeight.Medium,
        )
        if (cell.taskCount > 0) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 4.dp)
                    .size(width = 12.dp, height = 2.dp)
                    .clip(CircleShape)
                    .background(statusColor.copy(alpha = 0.95f)),
            )
        }
    }
}

@Immutable
private data class CalendarUi(
    val monthTitle: String,
    val signedDays: Int,
    val streakDays: Int,
    val rows: List<List<CalendarDayUi?>>,
)

@Immutable
private data class CalendarDayUi(
    val day: Int,
    val status: DayStatus,
    val taskCount: Int,
    val isToday: Boolean,
)

private enum class DayStatus { None, Success, Partial, Failed }

private fun buildCalendarUi(
    days: List<SignInCalendarDay>,
    currentMillis: Long,
    timeZone: TimeZone,
): CalendarUi {
    // Keep one timezone snapshot for the whole render. A device timezone change during
    // recomposition must not make the month, today key, and cell keys disagree.
    val base = Calendar.getInstance(timeZone, Locale.getDefault()).apply { timeInMillis = currentMillis }
    val year = base.get(Calendar.YEAR)
    val month = base.get(Calendar.MONTH)
    val todayKey = dayKey(currentMillis, timeZone)

    val statusByDay =
        days.associate { day ->
            val status =
                when (SignInCalendarDay.normalizeStatus(day.status)) {
                    SignInCalendarDay.STATUS_SIGNED -> DayStatus.Success
                    SignInCalendarDay.STATUS_PARTIAL -> DayStatus.Partial
                    SignInCalendarDay.STATUS_FAILED -> DayStatus.Failed
                    else -> DayStatus.None
                }
            day.dayKey to (status to day.taskCount)
        }

    val monthStart =
        Calendar.getInstance(timeZone, Locale.getDefault()).apply {
            clear()
            set(year, month, 1, 0, 0, 0)
        }
    val daysInMonth = monthStart.getActualMaximum(Calendar.DAY_OF_MONTH)
    val firstDayOfWeek = monthStart.get(Calendar.DAY_OF_WEEK)
    // 日历以周一为第一列：Mon=0 ... Sun=6。
    val leadingBlanks = (firstDayOfWeek + 5) % 7

    val cells = ArrayList<CalendarDayUi?>(42)
    repeat(leadingBlanks) { cells.add(null) }
    for (day in 1..daysInMonth) {
        val key = dayKey(year, month, day)
        val (status, tasks) = statusByDay[key] ?: (DayStatus.None to 0)
        cells.add(CalendarDayUi(day, status, tasks, key == todayKey))
    }
    while (cells.size % 7 != 0) cells.add(null)

    val signedKeysThisMonth =
        (1..daysInMonth).count { day ->
            val key = dayKey(year, month, day)
            val status = statusByDay[key]?.first ?: DayStatus.None
            status == DayStatus.Success || status == DayStatus.Partial
        }

    val signedKeys =
        statusByDay
            .filterValues { it.first == DayStatus.Success || it.first == DayStatus.Partial }
            .keys
            .toHashSet()
    val streak = computeSignInStreak(currentMillis, signedKeys, timeZone)

    return CalendarUi(
        // 日历月份统一使用阿拉伯数字和年月顺序，避免随界面语言变成“八月 2026”等格式。
        monthTitle = java.text.SimpleDateFormat("yyyy/MM", Locale.ROOT).format(monthStart.time),
        signedDays = signedKeysThisMonth,
        streakDays = streak,
        rows = cells.chunked(7),
    )
}

internal fun computeSignInStreak(
    currentMillis: Long,
    signedKeys: Set<String>,
    timeZone: java.util.TimeZone,
): Int {
    val cal = Calendar.getInstance(timeZone, Locale.ROOT).apply { timeInMillis = currentMillis }
    // A run commonly finishes before midnight. In that case the current streak is
    // still the consecutive run ending yesterday, rather than zero until today's run.
    if (!signedKeys.contains(dayKey(cal.timeInMillis, timeZone))) {
        cal.add(Calendar.DAY_OF_MONTH, -1)
        if (!signedKeys.contains(dayKey(cal.timeInMillis, timeZone))) return 0
    }
    var streak = 0
    while (signedKeys.contains(dayKey(cal.timeInMillis, timeZone))) {
        streak++
        cal.add(Calendar.DAY_OF_MONTH, -1)
    }
    return streak
}
