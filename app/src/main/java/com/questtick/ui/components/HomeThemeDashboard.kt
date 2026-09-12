package com.questtick.ui.components

/*
 * 首页主题化仪表盘：运行进度卡、统计条、签到按钮与任务队列预览，是首页的主要视觉区块。
 */
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.questtick.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.questtick.sign.RootBlockMessages
import com.questtick.sign.RunProgressPhase
import com.questtick.sign.RunProgressState
import com.questtick.sign.RunTaskProgress
import com.questtick.sign.RunTaskStatus
import com.questtick.sign.runProgressTaskPreview
import com.questtick.ui.theme.AppUiThemePalette
import com.questtick.ui.theme.DangerRed
import com.questtick.ui.theme.LocalAppUiTheme
import com.questtick.ui.theme.SuccessGreen
import com.questtick.ui.theme.WarnAmber

/** 首页状态卡片：纯白卡片、蓝色强调与签到进度。 */
@Composable
fun HomeThemeDashboard(
    clockText: String,
    secText: String,
    dayText: String,
    enabledAccounts: Int,
    totalAccounts: Int,
    gamesCount: Int,
    success: Int,
    already: Int,
    failed: Int,
    running: Boolean,
    completed: Boolean = false,
    onRun: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = LocalAppUiTheme.current
    val shape = RoundedCornerShape(28.dp)

    Box(
        modifier
            .fillMaxWidth()
            .height(286.dp)
            .padding(start = 16.dp, end = 16.dp, top = 8.dp),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .clip(shape)
                .background(theme.card)
                .border(1.dp, theme.hairline, shape)
                .padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            DashboardHeader(theme = theme, running = running, hasAccounts = totalAccounts > 0)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                DashboardTimeBlock(
                    clockText = clockText,
                    secText = secText,
                    dayText = dayText,
                    enabledAccounts = enabledAccounts,
                    totalAccounts = totalAccounts,
                    gamesCount = gamesCount,
                    theme = theme,
                    modifier = Modifier.weight(1f),
                )
            }
            DashboardStatsRow(success = success, already = already, failed = failed)
            SigninButton(
                theme = theme,
                running = running,
                completed = completed,
                onRun = onRun,
                modifier = Modifier.fillMaxWidth().height(50.dp),
            )
        }
    }
}

@Composable
private fun DashboardHeader(
    theme: AppUiThemePalette,
    running: Boolean,
    hasAccounts: Boolean,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                "QuestTick",
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 18.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (hasAccounts) {
                Text(
                    "账号状态与每日签到同步",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        StatusPill(running = running)
    }
}

@Composable
private fun DashboardTimeBlock(
    clockText: String,
    secText: String,
    dayText: String,
    enabledAccounts: Int,
    totalAccounts: Int,
    gamesCount: Int,
    theme: AppUiThemePalette,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                clockText,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 42.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = (-1).sp,
            )
            Spacer(Modifier.size(6.dp))
            Text(
                secText,
                color = theme.brand,
                fontSize = 18.sp,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        Spacer(Modifier.height(3.dp))
        Text(
            "$dayText · 已启用 $enabledAccounts/$totalAccounts 个账号 · $gamesCount 款游戏",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun StatusPill(running: Boolean) {
    val color = if (running) WarnAmber else SuccessGreen
    StatusBadge(
        text = if (running) "同步中" else "就绪",
        color = color,
        shape = CircleShape,
        horizontalPadding = 11.dp,
        verticalPadding = 6.dp,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        backgroundAlpha = 0.08f,
        borderAlpha = 0.22f,
        leadingDotColor = color,
        leadingDotSize = 7.dp,
    )
}

@Composable
private fun DashboardStatsRow(
    success: Int,
    already: Int,
    failed: Int,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        DashboardStatChip("成功", success, SuccessGreen, Modifier.weight(1f))
        DashboardStatChip("已签", already, WarnAmber, Modifier.weight(1f))
        DashboardStatChip("失败", failed, DangerRed, Modifier.weight(1f))
    }
}

@Composable
private fun DashboardStatChip(
    label: String,
    value: Int,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .height(58.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(color.copy(alpha = 0.10f))
            .border(1.dp, color.copy(alpha = 0.24f), RoundedCornerShape(15.dp))
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("$value", color = color, fontSize = 20.sp, lineHeight = 22.sp, fontWeight = FontWeight.Black, maxLines = 1)
        Spacer(Modifier.height(1.dp))
        Text(
            label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
            lineHeight = 13.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun SigninButton(
    theme: AppUiThemePalette,
    running: Boolean,
    completed: Boolean,
    onRun: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier
            .clip(shape)
            .background(Brush.horizontalGradient(listOf(theme.brandAlt, theme.brand)))
            .border(1.dp, theme.brandAlt.copy(alpha = if (running) 0.28f else 0.46f), shape)
            .then(if (!running) Modifier.clickableNoRipple(onRun) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!running && !completed) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White)
                Spacer(Modifier.size(6.dp))
            }
            Text(
                when {
                    running -> "签到进行中"
                    completed -> "签到完成"
                    else -> "开始签到"
                },
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = Color.White,
            )
        }
    }
}

@Composable
fun HomeThemeProgressCard(
    progress: RunProgressState,
    modifier: Modifier = Modifier,
) {
    val theme = LocalAppUiTheme.current
    val safeTotal = progress.total.coerceAtLeast(0)
    val completed = progress.done.coerceIn(0, safeTotal)
    val finished = !progress.running && progress.phase != RunProgressPhase.IDLE
    val isRootCheckFailedBlock =
        progress.phase == RunProgressPhase.BLOCKED && progress.message == RootBlockMessages.CHECK_FAILED_CONTENT
    val headline =
        when (progress.phase) {
            RunProgressPhase.FINISHED -> "签到完成"
            RunProgressPhase.BLOCKED -> if (isRootCheckFailedBlock) RootBlockMessages.CHECK_FAILED_TITLE else RootBlockMessages.DETECTED_TITLE
            RunProgressPhase.CANCELLED -> "签到已取消"
            RunProgressPhase.FAILED -> "签到异常结束"
            RunProgressPhase.CHECKING -> "正在检查运行环境"
            else -> "签到进行中"
        }
    val subtitle =
        when (progress.phase) {
            RunProgressPhase.BLOCKED -> progress.message.ifBlank { RootBlockMessages.DETECTED_CONTENT }
            RunProgressPhase.CANCELLED -> progress.message.ifBlank { "签到任务已取消" }
            RunProgressPhase.CHECKING -> progress.message.ifBlank { "正在执行 Root 与运行环境检测" }
            else -> "当前阶段：${progress.phase.label}"
        }
    val badgeText =
        when (progress.phase) {
            RunProgressPhase.FINISHED -> "完成"
            RunProgressPhase.BLOCKED -> "已阻断"
            RunProgressPhase.CANCELLED -> "已取消"
            RunProgressPhase.FAILED -> "异常"
            else -> "进行中"
        }
    val badgeColor =
        when (progress.phase) {
            RunProgressPhase.FINISHED -> SuccessGreen
            RunProgressPhase.BLOCKED, RunProgressPhase.FAILED -> DangerRed
            RunProgressPhase.CANCELLED -> MaterialTheme.colorScheme.onSurfaceVariant
            else -> WarnAmber
        }
    PanelCard(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(Brush.linearGradient(listOf(theme.brandAlt, theme.brand))),
                    contentAlignment = Alignment.Center,
                ) {
                    CenteredSparkIcon(Modifier.size(20.dp))
                }
                Spacer(Modifier.size(10.dp))
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        headline,
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        subtitle,
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                }
                StatusBadge(
                    text = badgeText,
                    color = badgeColor,
                    shape = RoundedCornerShape(999.dp),
                    horizontalPadding = 8.dp,
                    verticalPadding = 3.dp,
                    backgroundAlpha = 0.14f,
                    borderAlpha = 0.18f,
                    leadingDotColor = badgeColor,
                    leadingDotSize = 5.dp,
                    leadingDotSpacing = 5.dp,
                )
            }

            Spacer(Modifier.height(12.dp))

            if (safeTotal > 0) {
                ResultSummaryRow(
                    success = progress.success,
                    alreadySigned = progress.alreadySigned,
                    failed = progress.failed,
                    skipped = progress.skipped,
                    resultUnknown = progress.resultUnknown,
                    cancelled = progress.cancelled,
                )

                val preview = runProgressTaskPreview(progress.tasks)
                if (preview.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    ProgressTaskQueuePreview(preview)
                }
            }

            Spacer(Modifier.height(13.dp))

            Text(
                text =
                    if (progress.phase == RunProgressPhase.BLOCKED) {
                        progress.message.ifBlank { RootBlockMessages.DETECTED_CONTENT }
                    } else {
                        "已完成 $completed / $safeTotal"
                    },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            StarTrackProgress(
                completed = completed,
                total = safeTotal.coerceAtLeast(1),
                success = progress.success,
                alreadySigned = progress.alreadySigned,
                failed = progress.failed,
                skipped = progress.skipped,
                resultUnknown = progress.resultUnknown,
                cancelled = progress.cancelled,
                finished = finished,
                theme = theme,
                modifier = Modifier.fillMaxWidth().height(30.dp),
            )
        }
    }
}

@Composable
private fun CenteredSparkIcon(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val longRadius = size.minDimension * 0.46f
        val shortRadius = size.minDimension * 0.20f
        val path =
            Path().apply {
                moveTo(cx, cy - longRadius)
                lineTo(cx + shortRadius, cy - shortRadius)
                lineTo(cx + longRadius, cy)
                lineTo(cx + shortRadius, cy + shortRadius)
                lineTo(cx, cy + longRadius)
                lineTo(cx - shortRadius, cy + shortRadius)
                lineTo(cx - longRadius, cy)
                lineTo(cx - shortRadius, cy - shortRadius)
                close()
            }
        drawPath(path = path, color = Color.White)
    }
}

@Composable
private fun ProgressTaskQueuePreview(tasks: List<RunTaskProgress>) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f))
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.20f), RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            "任务队列",
            color = MaterialTheme.colorScheme.primary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(4.dp))
        tasks.forEachIndexed { index, task ->
            if (index > 0) Spacer(Modifier.height(5.dp))
            ProgressTaskRow(task)
        }
    }
}

@Composable
private fun ProgressTaskRow(task: RunTaskProgress) {
    val color = progressTaskStatusColor(task.status)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                task.title,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(1.dp))
            Text(
                task.type.label +
                    task.message
                        .takeIf { it.isNotBlank() }
                        ?.let { " · $it" }
                        .orEmpty(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.size(8.dp))
        StatusBadge(
            text = task.status.label,
            color = color,
            shape = RoundedCornerShape(999.dp),
            horizontalPadding = 7.dp,
            verticalPadding = 2.dp,
            backgroundAlpha = 0.12f,
            borderAlpha = 0.16f,
            leadingDotColor = color,
            leadingDotSize = 4.dp,
            leadingDotSpacing = 4.dp,
        )
    }
}

@Composable
private fun progressTaskStatusColor(status: RunTaskStatus): Color =
    when (status) {
        RunTaskStatus.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
        RunTaskStatus.RUNNING -> WarnAmber
        RunTaskStatus.SUCCESS -> SuccessGreen
        RunTaskStatus.ALREADY_SIGNED -> WarnAmber
        RunTaskStatus.FAILED -> DangerRed
        RunTaskStatus.RESULT_UNKNOWN -> WarnAmber
        RunTaskStatus.SKIPPED, RunTaskStatus.CANCELLED -> MaterialTheme.colorScheme.onSurfaceVariant
    }

@Composable
private fun ResultSummaryRow(
    success: Int,
    alreadySigned: Int,
    failed: Int,
    skipped: Int,
    resultUnknown: Int,
    cancelled: Int,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        DashboardStatChip("成功", success, SuccessGreen, Modifier.weight(1f))
        DashboardStatChip("已签", alreadySigned, WarnAmber, Modifier.weight(1f))
        DashboardStatChip("失败", failed, DangerRed, Modifier.weight(1f))
        DashboardStatChip("待确认", resultUnknown, WarnAmber, Modifier.weight(1f))
        DashboardStatChip("跳过", skipped, MaterialTheme.colorScheme.onSurfaceVariant, Modifier.weight(1f))
        DashboardStatChip("取消", cancelled, MaterialTheme.colorScheme.onSurfaceVariant, Modifier.weight(1f))
    }
}

@Composable
private fun StarTrackProgress(
    completed: Int,
    total: Int,
    success: Int,
    alreadySigned: Int,
    failed: Int,
    skipped: Int,
    resultUnknown: Int,
    cancelled: Int,
    finished: Boolean,
    theme: AppUiThemePalette,
    modifier: Modifier = Modifier,
) {
    val safeTotal = total.coerceAtLeast(1)
    val safeCompleted = completed.coerceIn(0, safeTotal)
    val skippedColor = MaterialTheme.colorScheme.onSurfaceVariant

    Canvas(modifier) {
        val centerY = size.height / 2f
        val nodeRadius =
            when {
                safeTotal <= 10 -> 5.2.dp.toPx()
                safeTotal <= 24 -> 4.2.dp.toPx()
                else -> 3.2.dp.toPx()
            }
        val horizontalInset = nodeRadius + 1.dp.toPx()
        val usableWidth = (size.width - horizontalInset * 2f).coerceAtLeast(1f)
        val step = if (safeTotal == 1) 0f else usableWidth / (safeTotal - 1)
        val positions = List(safeTotal) { index -> horizontalInset + step * index }

        for (index in 0 until safeTotal - 1) {
            val finishedLine = index < safeCompleted - 1
            drawLine(
                color = if (finishedLine) theme.brand.copy(alpha = 0.55f) else theme.brandAlt.copy(alpha = 0.18f),
                start = Offset(positions[index] + nodeRadius, centerY),
                end = Offset(positions[index + 1] - nodeRadius, centerY),
                strokeWidth = 2.dp.toPx(),
            )
        }

        for (index in 0 until safeTotal) {
            val nodeColor =
                if (finished) {
                    finishedNodeColor(
                        index,
                        success,
                        alreadySigned,
                        failed,
                        resultUnknown,
                        skipped,
                        cancelled,
                        skippedColor,
                        theme,
                    )
                } else if (index < safeCompleted) {
                    theme.brand
                } else {
                    theme.brandAlt.copy(alpha = 0.22f)
                }
            val center = Offset(positions[index], centerY)
            val pending = !finished && index >= safeCompleted
            drawCircle(
                color = nodeColor.copy(alpha = if (pending) 0.55f else 1f),
                radius = nodeRadius,
                center = center,
            )
            if (pending) {
                drawCircle(
                    color = theme.brandAlt.copy(alpha = 0.32f),
                    radius = nodeRadius,
                    center = center,
                    style = Stroke(width = 1.dp.toPx()),
                )
            } else {
                drawCircle(
                    color = Color.White.copy(alpha = 0.36f),
                    radius = nodeRadius * 0.38f,
                    center = center,
                )
            }
        }
    }
}

private fun finishedNodeColor(
    index: Int,
    success: Int,
    alreadySigned: Int,
    failed: Int,
    resultUnknown: Int,
    skipped: Int,
    cancelled: Int,
    skippedColor: Color,
    theme: AppUiThemePalette,
): Color =
    when {
        index < success -> SuccessGreen
        index < success + alreadySigned -> WarnAmber
        index < success + alreadySigned + failed -> DangerRed
        index < success + alreadySigned + failed + resultUnknown -> WarnAmber
        index < success + alreadySigned + failed + resultUnknown + skipped + cancelled -> skippedColor
        else -> theme.brandAlt.copy(alpha = 0.22f)
    }
