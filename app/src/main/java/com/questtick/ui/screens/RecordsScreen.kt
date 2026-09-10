package com.questtick.ui.screens

import com.questtick.i18n.localizedText
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.questtick.i18n.Text
import com.questtick.i18n.formatLocalizedShortDateTime
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.questtick.data.TaskResult
import com.questtick.repository.base.RepositoryLoadState
import com.questtick.repository.run.PostRunActionRepository
import com.questtick.ui.components.EmptyState
import com.questtick.ui.components.GalaxyBackground
import com.questtick.ui.components.PageTitle
import com.questtick.ui.components.PanelCard
import com.questtick.ui.components.SkeletonCard
import com.questtick.ui.components.StatusBadge
import com.questtick.ui.components.clickableNoRipple
import com.questtick.ui.components.consumeHorizontalScrollOverflow
import com.questtick.ui.components.home.ResultRow
import com.questtick.ui.theme.AppMotion
import com.questtick.ui.theme.DangerRed
import com.questtick.ui.theme.SuccessGreen
import com.questtick.ui.theme.TextSecondary
import com.questtick.ui.theme.WarnAmber
import com.questtick.ui.vm.EmailDeliveryUiState
import com.questtick.ui.vm.RecordsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.distinctUntilChanged

private fun formatRecordTime(timestamp: Long): String = formatLocalizedShortDateTime(timestamp)

private const val RECORD_RUN_INITIAL_LIMIT = 24
private const val RECORD_RUN_BATCH_SIZE = 24

/** 仅用首尾标识触发缓存更新，避免 Compose 为比较整份历史列表而阻塞主线程。 */
private data class HistorySnapshotKey(
    val identity: Int,
    val size: Int,
    val newestRunId: String,
    val oldestRunId: String,
    val newestTimestamp: Long,
    val oldestTimestamp: Long,
)

@Composable
fun RecordsScreen(
    isVisible: Boolean = true,
    viewModel: RecordsViewModel = hiltViewModel(),
) {
    val history by viewModel.history.collectAsStateWithLifecycle()
    val loadState by viewModel.loadState.collectAsStateWithLifecycle()
    val emailDeliveries by viewModel.emailDeliveries.collectAsStateWithLifecycle()

    var filter by rememberSaveable { mutableStateOf(RecFilter.ALL) }
    var recordsIndex by remember { mutableStateOf<RecordsIndex?>(null) }
    var visibleRunLimit by rememberSaveable(filter) { mutableIntStateOf(RECORD_RUN_INITIAL_LIMIT) }
    val listState = rememberLazyListState()
    val filterScrollState = rememberScrollState()
    val historyKey =
        HistorySnapshotKey(
            identity = System.identityHashCode(history),
            size = history.size,
            newestRunId = history.firstOrNull()?.runId.orEmpty(),
            oldestRunId = history.lastOrNull()?.runId.orEmpty(),
            newestTimestamp = history.firstOrNull()?.timestamp ?: 0L,
            oldestTimestamp = history.lastOrNull()?.timestamp ?: 0L,
        )

    LaunchedEffect(isVisible) {
        if (isVisible) viewModel.refresh()
    }

    LaunchedEffect(historyKey, isVisible) {
        // 页面切走时保留现有索引，避免分页器预加载的隐藏页面抢占 CPU。
        if (!isVisible) return@LaunchedEffect
        if (history.isEmpty()) {
            recordsIndex = RecordsIndex(0, emptyMap(), emptyMap())
            return@LaunchedEffect
        }
        recordsIndex = withContext(Dispatchers.Default) { buildRecordsIndex(history) }
    }

    // 过滤索引只保存运行摘要；结果行在对应卡片展开后才读取。
    val currentRunSummaries =
        remember(filter, recordsIndex) { recordsIndex?.runsByFilter?.get(filter).orEmpty() }
    val buildingInitialIndex =
        loadState !is RepositoryLoadState.InitialLoading && history.isNotEmpty() && recordsIndex == null
    val visibleFilteredRuns =
        remember(currentRunSummaries, visibleRunLimit) {
            currentRunSummaries.take(visibleRunLimit.coerceAtMost(currentRunSummaries.size))
        }
    val hasMoreRuns = visibleFilteredRuns.size < currentRunSummaries.size

    LaunchedEffect(filter, currentRunSummaries.size) {
        visibleRunLimit = visibleRunLimit.coerceAtMost(currentRunSummaries.size.coerceAtLeast(RECORD_RUN_INITIAL_LIMIT))
    }

    LaunchedEffect(listState, hasMoreRuns, currentRunSummaries.size, visibleRunLimit) {
        if (!hasMoreRuns) return@LaunchedEffect
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.any { it.key == "load_more_records" } }
            .distinctUntilChanged()
            .collect { loadMoreVisible ->
                if (loadMoreVisible) {
                    visibleRunLimit = (visibleRunLimit + RECORD_RUN_BATCH_SIZE).coerceAtMost(currentRunSummaries.size)
                }
            }
    }

    val countMap = remember(recordsIndex) { recordsIndex?.counts.orEmpty() }
    val displayedResultCount = recordsIndex?.allResultCount ?: 0

    fun count(f: RecFilter) = countMap[f] ?: 0

    GalaxyBackground {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "spacer_top") { Spacer(Modifier.height(8.dp)) }
            item(key = "page_title") {
                PageTitle("签到记录", "共 $displayedResultCount 条记录") {
                    if (history.isNotEmpty()) {
                        Text(
                            "清空",
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 14.sp,
                            modifier = Modifier.clickableNoRipple(viewModel::clearHistory),
                        )
                    }
                }
            }

            item(key = "filter_chips") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .consumeHorizontalScrollOverflow()
                        .horizontalScroll(filterScrollState),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    RecFilter.entries.forEach { f ->
                        FilterChip(f.label, count(f), filter == f) { filter = f }
                    }
                }
            }

            when {
                loadState is RepositoryLoadState.Error && history.isEmpty() -> {
                    item(key = "load_error") {
                        EmptyState(
                            badge = {
                                Box(
                                    Modifier
                                        .size(64.dp)
                                        .clip(RoundedCornerShape(20.dp))
                                        .background(MaterialTheme.colorScheme.error.copy(alpha = 0.12f)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(Icons.Filled.FilterList, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                                }
                            },
                            title = "记录读取失败",
                            subtitle = "本地记录暂时无法读取，请重试",
                            action = { Button(onClick = viewModel::refresh) { Text("重新加载") } },
                        )
                    }
                }
                loadState is RepositoryLoadState.InitialLoading || buildingInitialIndex -> {
                    item(key = "skeleton_1") {
                        SkeletonCard(modifier = Modifier.fillMaxWidth(), titleWidth = 90.dp, lineCount = 4)
                    }
                    item(key = "skeleton_2") {
                        SkeletonCard(modifier = Modifier.fillMaxWidth(), titleWidth = 72.dp, lineCount = 3)
                    }
                }
                history.isEmpty() -> {
                    item(key = "empty_state") {
                        EmptyState(
                            badge = {
                                Box(
                                    Modifier
                                        .size(
                                            64.dp,
                                        ).clip(RoundedCornerShape(20.dp))
                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        Icons.Filled.FilterList,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(30.dp),
                                    )
                                }
                            },
                            title = "暂无记录",
                            subtitle = "执行签到后记录将显示在这里",
                        )
                    }
                }
                currentRunSummaries.isNotEmpty() -> {
                    // LazyColumn 只组合视口附近的运行卡片；详情在展开时按需读取。
                    items(
                        visibleFilteredRuns,
                        key = { it.id },
                        contentType = { "run_card" },
                    ) { summary ->
                        history.getOrNull(summary.runIndex)?.let { run ->
                            RunCard(
                                summary = summary,
                                run = run,
                                filter = filter,
                                emailDelivery = emailDeliveries[run.runId],
                                onRetryEmail = viewModel::retryEmail,
                            )
                        }
                    }
                    if (hasMoreRuns) {
                        item(key = "load_more_records", contentType = "load_more_records") {
                            Text(
                                "正在加载更多记录…",
                                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
                else -> Unit
            }
            item(key = "spacer_bottom") { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun FilterChip(
    label: String,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val localizedLabel = localizedText(label)
    StatusBadge(
        text = "$localizedLabel $count",
        color = MaterialTheme.colorScheme.primary,
        shape = RoundedCornerShape(999.dp),
        horizontalPadding = 14.dp,
        verticalPadding = 8.dp,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        backgroundAlpha = 0.05f,
        borderAlpha = 0.35f,
        selected = selected,
        selectedContentColor = Color.White,
        selectedBackgroundAlpha = 1f,
        onClick = onClick,
    )
}

/** 可折叠的签到记录卡片。 */
@Composable
@Suppress("detekt:LongMethod", "detekt:CyclomaticComplexMethod", "detekt:FunctionNaming")
private fun RunCard(
    summary: RecordRunSummary,
    run: com.questtick.data.RunRecord,
    filter: RecFilter,
    emailDelivery: EmailDeliveryUiState?,
    onRetryEmail: (String) -> Unit,
) {
    val resultCount = summary.count(filter)
    val succeeded =
        if (filter == RecFilter.ALL) {
            summary.succeeded
        } else if (filter == RecFilter.SUCCESS) {
            resultCount
        } else {
            0
        }
    val already =
        if (filter == RecFilter.ALL) {
            summary.alreadySigned
        } else if (filter == RecFilter.ALREADY) {
            resultCount
        } else {
            0
        }
    val failed =
        if (filter == RecFilter.ALL) {
            summary.failed
        } else if (filter == RecFilter.FAILED) {
            resultCount
        } else {
            0
        }
    val resultUnknown =
        if (filter == RecFilter.ALL) {
            summary.resultUnknown
        } else if (filter == RecFilter.RESULT_UNKNOWN) {
            resultCount
        } else {
            0
        }
    var expanded by rememberSaveable("${summary.id}_${filter.name}") { mutableStateOf(false) }
    val loadedResults =
        remember(expanded, summary.id, filter) {
            if (!expanded) {
                emptyList()
            } else if (filter == RecFilter.ALL) {
                run.results
            } else {
                run.results.filter { matchesRecordFilter(it, filter) }
            }
        }
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(AppMotion.ARROW_DURATION_MILLIS),
        label = "arrow",
    )

    PanelCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth().clickableNoRipple { expanded = !expanded },
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
                                formatRecordTime(summary.timestamp),
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            if (succeeded > 0) MiniStat("✓$succeeded", SuccessGreen)
                            if (already > 0) MiniStat("已签$already", WarnAmber)
                            if (failed > 0) MiniStat("✗$failed", DangerRed)
                            if (resultUnknown > 0) MiniStat("待确认$resultUnknown", WarnAmber)
                        }
                        Spacer(Modifier.size(8.dp))
                        Text("$resultCount 项任务", fontSize = 12.sp, color = TextSecondary)
                    }
                }
                Spacer(Modifier.size(8.dp))
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = localizedText(if (expanded) "收起" else "展开"),
                    tint = TextSecondary,
                    modifier = Modifier.size(22.dp).rotate(arrowRotation),
                )
            }

            emailDelivery?.let { delivery ->
                Spacer(Modifier.height(10.dp))
                EmailDeliveryStatusRow(delivery, onRetryEmail)
            }

            AnimatedVisibility(
                visible = expanded,
                enter = AppMotion.expandEnter(),
                exit = AppMotion.expandExit(),
            ) {
                Column {
                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(6.dp))
                    RunResultRows(loadedResults, loadRewardImages = true)
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
private fun EmailDeliveryStatusRow(
    delivery: EmailDeliveryUiState,
    onRetryEmail: (String) -> Unit,
) {
    val canRetry =
        delivery.status == PostRunActionRepository.STATUS_FAILED ||
            delivery.status == PostRunActionRepository.STATUS_RESULT_UNKNOWN ||
            delivery.status == PostRunActionRepository.STATUS_CANCELLED
    val (label, color) =
        when (delivery.status) {
            PostRunActionRepository.STATUS_PENDING -> {
                val suffix = if (delivery.attemptCount > 0) "（等待第${delivery.attemptCount + 1}次尝试）" else ""
                "邮件待发送$suffix" to WarnAmber
            }
            PostRunActionRepository.STATUS_PROCESSING -> "邮件发送中" to MaterialTheme.colorScheme.primary
            PostRunActionRepository.STATUS_DELIVERED -> "邮件已发送" to SuccessGreen
            PostRunActionRepository.STATUS_FAILED -> "邮件发送失败" to DangerRed
            PostRunActionRepository.STATUS_RESULT_UNKNOWN -> "邮件投递结果不确定，请确认后重发" to WarnAmber
            PostRunActionRepository.STATUS_CANCELLED -> "邮件发送已取消" to TextSecondary
            else -> "邮件状态未知" to TextSecondary
        }

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(color.copy(alpha = 0.08f))
                .border(0.5.dp, color.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            color = color,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
        if (canRetry) {
            TextButton(onClick = { onRetryEmail(delivery.actionId) }) {
                Text(if (delivery.status == PostRunActionRepository.STATUS_RESULT_UNKNOWN) "确认重新发送" else "重新发送")
            }
        }
    }
}

@Composable
private fun RunResultRows(
    results: List<TaskResult>,
    loadRewardImages: Boolean,
) {
    if (results.size > 12) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
        ) {
            itemsIndexed(
                results,
                key = { index, result -> recordResultStableKey(result, index) },
                contentType = { _, _ -> "result_row" },
            ) { index, result ->
                ResultRowWithDivider(
                    result = result,
                    showDivider = index < results.lastIndex,
                    loadRewardImage = loadRewardImages,
                )
            }
        }
    } else {
        Column {
            results.forEachIndexed { index, result ->
                ResultRowWithDivider(
                    result = result,
                    showDivider = index < results.lastIndex,
                    loadRewardImage = loadRewardImages,
                )
            }
        }
    }
}

@Composable
private fun ResultRowWithDivider(
    result: TaskResult,
    showDivider: Boolean,
    loadRewardImage: Boolean,
) {
    ResultRow(result, loadRewardImage = loadRewardImage)
    if (showDivider) {
        HorizontalDivider(
            modifier = Modifier.padding(start = 40.dp, top = 2.dp, bottom = 2.dp),
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
        )
    }
}

@Composable
private fun MiniStat(
    text: String,
    color: Color,
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
