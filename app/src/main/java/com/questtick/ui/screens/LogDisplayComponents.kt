package com.questtick.ui.screens

/*
 * 日志页展示组件：搜索框、统计芯片、日期分隔条、运行分组卡片与关键词高亮文本，供 LogsScreen 组装复用。
 */
import com.questtick.i18n.localizedText
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import com.questtick.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.questtick.ui.components.PanelCard
import com.questtick.ui.components.StatusBadge
import com.questtick.ui.components.clickableNoRipple
import com.questtick.ui.theme.AppMotion
import com.questtick.ui.theme.DangerRed
import com.questtick.ui.theme.SuccessGreen
import com.questtick.ui.theme.TextSecondary
import com.questtick.ui.theme.WarnAmber

@Composable
internal fun LogSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    onClear: () -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(18.dp)) },
        trailingIcon = {
            if (value.isNotEmpty()) {
                IconButton(onClick = onClear) {
                    Icon(Icons.Filled.Close, contentDescription = localizedText("清除搜索"), tint = TextSecondary, modifier = Modifier.size(18.dp))
                }
            }
        },
        placeholder = { Text("搜索日志、详情、日期或级别", fontSize = 13.sp, color = TextSecondary) },
    )
}

@Composable
internal fun LogStatChip(
    text: String,
    color: Color,
    selected: Boolean,
    onClick: () -> Unit,
) {
    StatusBadge(
        text = text,
        color = color,
        shape = RoundedCornerShape(999.dp),
        horizontalPadding = 10.dp,
        verticalPadding = 5.dp,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        backgroundAlpha = 0.10f,
        borderAlpha = 0.35f,
        selected = selected,
        onClick = onClick,
    )
}

@Composable
internal fun DateDivider(date: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        HorizontalDivider(Modifier.weight(1f), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)
        Text(date, modifier = Modifier.padding(horizontal = 12.dp), fontSize = 11.sp, color = TextSecondary.copy(alpha = 0.7f), fontWeight = FontWeight.Medium)
        HorizontalDivider(Modifier.weight(1f), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)
    }
}

@Composable
internal fun LogRunGroupCard(
    group: LogRunGroup,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    verbose: Boolean,
    searchQuery: String,
) {
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(AppMotion.ARROW_DURATION_MILLIS),
        label = "logGroupArrowRotation",
    )
    PanelCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth().clickableNoRipple { onExpandedChange(!expanded) }, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // 分组标题包含动态时间，必须通过同一套模板本地化，避免繁体页面标题与卡片标题不一致。
                        Text(
                            localizedText(group.title),
                            modifier = Modifier.weight(1f, fill = false),
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            softWrap = true,
                        )
                        if (group.running) {
                            Spacer(Modifier.width(6.dp))
                            StatusBadge(text = "进行中", color = WarnAmber, shape = RoundedCornerShape(999.dp), horizontalPadding = 7.dp, verticalPadding = 2.dp, backgroundAlpha = 0.14f, borderAlpha = 0.18f, leadingDotColor = WarnAmber, leadingDotSize = 5.dp, leadingDotSpacing = 4.dp)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(group.subtitle, color = TextSecondary, fontSize = 11.sp, softWrap = true)
                }
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = localizedText(if (expanded) "收起" else "展开"), tint = TextSecondary, modifier = Modifier.size(22.dp).rotate(arrowRotation))
            }
            AnimatedVisibility(visible = expanded, enter = AppMotion.expandEnter(), exit = AppMotion.expandExit()) {
                Column {
                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline.copy(alpha = 0.65f))
                    Spacer(Modifier.height(4.dp))
                    val entries = remember(group.entries) { group.entries.asReversed() }
                    LogEntryRows(entries, verbose, searchQuery)
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth().clickableNoRipple { onExpandedChange(false) }, horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = localizedText("收起"), tint = TextSecondary.copy(alpha = 0.65f), modifier = Modifier.size(15.dp).rotate(180f))
                        Spacer(Modifier.width(4.dp))
                        Text("收起", fontSize = 11.sp, color = TextSecondary.copy(alpha = 0.75f))
                    }
                }
            }
        }
    }
}

@Composable
private fun LogEntryRows(
    entries: List<ProcessedLogEntry>,
    verbose: Boolean,
    searchQuery: String,
) {
    Column { entries.forEachIndexed { index, entry -> LogEntryRowWithDivider(entry, verbose, searchQuery, index < entries.lastIndex) } }
}

@Composable
private fun LogEntryRowWithDivider(
    entry: ProcessedLogEntry,
    verbose: Boolean,
    searchQuery: String,
    showDivider: Boolean,
) {
    ProcessedLogRow(entry, verbose, searchQuery)
    if (showDivider) HorizontalDivider(Modifier.padding(start = 62.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
}

@Composable
@Suppress("detekt:LongMethod", "detekt:FunctionNaming")
private fun ProcessedLogRow(
    entry: ProcessedLogEntry,
    verbose: Boolean,
    searchQuery: String,
) {
    val color =
        remember(entry.level) {
            when (entry.level) {
                "DEBUG" -> TextSecondary.copy(alpha = 0.85f)
                "OK" -> SuccessGreen
                "WARN" -> WarnAmber
                "ERROR" -> DangerRed
                else -> TextSecondary
            }
        }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Text(
                entry.formattedTime,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = TextSecondary.copy(alpha = 0.7f),
                modifier = Modifier.width(62.dp).padding(top = 2.dp),
                maxLines = 1,
            )
            StatusBadge(
                text = entry.level,
                color = color,
                modifier = Modifier.padding(end = 8.dp, top = 1.dp),
                shape = RoundedCornerShape(4.dp),
                horizontalPadding = 6.dp,
                verticalPadding = 2.dp,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                backgroundAlpha = 0.15f,
            )
            Column(Modifier.weight(1f)) {
                // 日志写入时保留中文源文案；显示时按当前语言翻译，未知服务端文本由本地化入口原样返回。
                HighlightedLogText(
                    localizedText(entry.safeMessage),
                    searchQuery,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = if (verbose) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        AnimatedVisibility(visible = verbose && entry.safeDetail.isNotBlank(), enter = AppMotion.expandEnter(), exit = AppMotion.expandExit()) {
            Box(
                Modifier
                    .padding(start = 62.dp, top = 6.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(color.copy(alpha = 0.06f))
                    .border(1.dp, color.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                HighlightedLogText(
                    localizedText(entry.safeDetail),
                    searchQuery,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    lineHeight = 15.sp,
                    color = TextSecondary,
                )
            }
        }
    }
}

@Composable
private fun HighlightedLogText(
    text: String,
    query: String,
    modifier: Modifier = Modifier,
    fontFamily: FontFamily? = null,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    color: Color,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    val highlightColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
    val annotated = remember(text, query, highlightColor) { highlightedLogText(text, query, highlightColor) }
    Text(annotated, modifier = modifier, fontFamily = fontFamily, fontSize = fontSize, lineHeight = lineHeight, color = color, maxLines = maxLines, overflow = overflow)
}

private fun highlightedLogText(
    text: String,
    query: String,
    highlightColor: Color,
): AnnotatedString {
    val ranges = logSearchRanges(text, query)
    if (ranges.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        var cursor = 0
        ranges.forEach { range ->
            if (cursor < range.first) append(text.substring(cursor, range.first))
            pushStyle(SpanStyle(background = highlightColor, fontWeight = FontWeight.Bold))
            append(text.substring(range.first, range.last + 1))
            pop()
            cursor = range.last + 1
        }
        if (cursor < text.length) append(text.substring(cursor))
    }
}
