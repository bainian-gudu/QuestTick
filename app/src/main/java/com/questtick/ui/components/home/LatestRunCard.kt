package com.questtick.ui.components.home

import com.questtick.i18n.localizedText
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.questtick.i18n.Text
import com.questtick.i18n.formatLocalizedShortDateTime
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.questtick.data.RunRecord
import com.questtick.ui.components.PanelCard
import com.questtick.ui.components.StatusBadge
import com.questtick.ui.components.clickableNoRipple
import com.questtick.ui.theme.AppMotion
import com.questtick.ui.theme.DangerRed
import com.questtick.ui.theme.SuccessGreen
import com.questtick.ui.theme.TextSecondary
import com.questtick.ui.theme.WarnAmber


private fun formatTime(ts: Long) = formatLocalizedShortDateTime(ts)

/**
 * 首页“最近签到”卡片。
 *
 * 默认折叠，只展示时间、统计标签与展开箭头；展开后显示最多 6 条详情，
 * 更多内容引导到记录页查看。
 */
@Composable
fun LatestRunCard(
    record: RunRecord,
    modifier: Modifier = Modifier,
) {
    val formattedTime = remember(record.timestamp) { formatTime(record.timestamp) }
    val summary = remember(record) { buildLatestRunSummary(record) }

    // 最近签到详情默认折叠。
    var expanded by rememberSaveable(record.timestamp) { mutableStateOf(false) }
    // 展开箭头旋转动画。
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(AppMotion.ArrowDurationMillis),
        label = "arrowRotation",
    )

    PanelCard(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            // 头部区域始终可见，点击可展开或收起。
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickableNoRipple { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                formattedTime,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            if (summary.succeeded > 0) MiniStatChip("✓${summary.succeeded}", SuccessGreen)
                            if (summary.alreadySigned > 0) MiniStatChip("已签${summary.alreadySigned}", WarnAmber)
                            if (summary.failed > 0) MiniStatChip("✗${summary.failed}", DangerRed)
                            if (summary.resultUnknown > 0) MiniStatChip("待确认${summary.resultUnknown}", WarnAmber)
                        }
                        Spacer(Modifier.size(8.dp))
                        Text("${summary.totalCount} 项任务", fontSize = 12.sp, color = TextSecondary)
                    }
                }
                Spacer(Modifier.size(8.dp))
                // 展开 / 收起箭头。
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = localizedText(if (expanded) "收起" else "展开"),
                    tint = TextSecondary,
                    modifier =
                        Modifier
                            .size(24.dp)
                            .rotate(arrowRotation),
                )
            }

            // 详情区域带折叠动画。
            AnimatedVisibility(
                visible = expanded,
                enter = AppMotion.expandEnter(),
                exit = AppMotion.expandExit(),
            ) {
                Column {
                    Spacer(Modifier.height(10.dp))
                    // 分隔线。
                    Box(Modifier.fillMaxWidth().height(0.5.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)))
                    Spacer(Modifier.height(8.dp))
                    // 结果列表。
                    summary.topResults.forEachIndexed { index, result ->
                        ResultRow(result, loadRewardImage = expanded)
                        if (index < summary.topResults.lastIndex) {
                            Box(
                                Modifier.padding(start = 40.dp, top = 2.dp, bottom = 2.dp)
                                    .fillMaxWidth().height(0.5.dp)
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)),
                            )
                        }
                    }
                    if (summary.hasMore) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "还有 ${summary.remainingCount} 条，前往「记录」查看全部",
                            fontSize = 11.sp,
                            color = TextSecondary,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    // 收起按钮。
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.fillMaxWidth().clickableNoRipple { expanded = false },
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.KeyboardArrowDown,
                            contentDescription = localizedText("收起"),
                            tint = TextSecondary.copy(alpha = 0.6f),
                            modifier = Modifier.size(16.dp).rotate(180f),
                        )
                        Spacer(Modifier.size(4.dp))
                        Text("收起", fontSize = 12.sp, color = TextSecondary.copy(alpha = 0.6f))
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniStatChip(
    text: String,
    color: androidx.compose.ui.graphics.Color,
) {
    StatusBadge(
        text = text,
        color = color,
        shape = RoundedCornerShape(5.dp),
        horizontalPadding = 6.dp,
        verticalPadding = 2.dp,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        backgroundAlpha = 0.12f,
    )
}
