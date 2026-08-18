package com.questtick.ui.screens

import com.questtick.i18n.localizedText
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import com.questtick.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.questtick.log.LogExporter
import com.questtick.repository.base.RepositoryLoadState
import com.questtick.sign.Mask
import com.questtick.ui.components.AnimatedSwitch
import com.questtick.ui.components.EmptyState
import com.questtick.ui.components.GalaxyBackground
import com.questtick.ui.components.PageTitle
import com.questtick.ui.components.PanelCard
import com.questtick.ui.components.SkeletonCard
import com.questtick.ui.components.StatusBadge
import com.questtick.ui.components.clickableNoRipple
import com.questtick.ui.components.consumeHorizontalScrollOverflow
import com.questtick.ui.theme.AppMotion
import com.questtick.ui.theme.DangerRed
import com.questtick.ui.theme.SuccessGreen
import com.questtick.ui.theme.TextSecondary
import com.questtick.ui.theme.WarnAmber
import com.questtick.ui.vm.LogsViewModel
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val logTimeFmt: SimpleDateFormat get() = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
private val logDateFmt: SimpleDateFormat get() = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

@Composable
fun LogsScreen(
    isVisible: Boolean = true,
    viewModel: LogsViewModel = hiltViewModel(),
) {
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val loadState by viewModel.loadState.collectAsStateWithLifecycle()
    val signingStartedAt by viewModel.runningStartedAt.collectAsStateWithLifecycle()
    LaunchedEffect(isVisible) {
        if (isVisible) viewModel.refresh()
    }

    val displayPrepared =
        remember(logs, isVisible) {
            if (!isVisible) {
                PreparedLogs(emptyList(), LogStats())
            } else {
                val timeFormatter = logTimeFmt
                val dateFormatter = logDateFmt
                prepareLogs(
                    logs = logs,
                    formatTime = { timestamp -> timeFormatter.format(Date(timestamp)) },
                    formatDate = { timestamp -> dateFormatter.format(Date(timestamp)) },
                    mask = Mask::sensitive,
                )
            }
        }
    val processed = displayPrepared.entries
    val stats = displayPrepared.stats
    var filter by rememberSaveable { mutableStateOf(LogFilter.ALL) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var debouncedSearchKeyword by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(searchQuery, isVisible) {
        if (!isVisible) return@LaunchedEffect
        delay(250)
        debouncedSearchKeyword = searchQuery.trim()
    }
    val searchKeyword = debouncedSearchKeyword
    val allEntries = remember(processed, isVisible) { if (isVisible) processed else emptyList() }
    val allGroups = remember(allEntries, signingStartedAt) { buildLogGroups(allEntries, signingStartedAt).asReversed() }
    val visibleGroups =
        remember(allGroups, filter, searchKeyword) {
            filterLogGroups(allGroups, filter, searchKeyword)
        }
    val visibleEntryCount = remember(visibleGroups) { visibleGroups.sumOf { it.entries.size } }
    val filteringLogs = filter != LogFilter.ALL || searchKeyword.isNotEmpty()
    val totalEntryCount = allEntries.size
    val searchSummary =
        remember(visibleGroups, totalEntryCount, searchKeyword, filter) {
            buildLogSearchSummary(
                visibleGroups = visibleGroups,
                totalEntries = totalEntryCount,
                query = searchKeyword,
                filter = filter,
            )
        }
    val titleSubtitle =
        when {
            filteringLogs -> "$visibleEntryCount/$totalEntryCount 条日志"
            else -> "$totalEntryCount 条日志"
        }

    // 导出默认包含详情和 DEBUG 内容，便于直接提交完整诊断信息；用户仍可手动关闭。
    var verbose by rememberSaveable { mutableStateOf(true) }
    // 展开状态只响应用户手动展开/收起，不随日志分组刷新自动清理，
    // 避免任务完成瞬间的短暂重组把已展开日志误恢复为折叠。
    var expandedLogGroupIds by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val expandedLogGroupIdSet = remember(expandedLogGroupIds) { expandedLogGroupIds.toSet() }
    val listState = rememberLazyListState()
    val filterScrollState = rememberScrollState()
    val context = LocalContext.current

    LaunchedEffect(filter, searchKeyword) {
        listState.scrollToItem(0)
    }

    fun setLogGroupExpanded(
        group: LogRunGroup,
        expanded: Boolean,
    ) {
        expandedLogGroupIds = updateExpandedLogGroupKeys(expandedLogGroupIds, group, expanded)
    }

    // 新日志置顶；用户停留在顶部附近时自动滚回最上方。
    val isAtTop by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex <= 1
        }
    }
    LaunchedEffect(visibleGroups.firstOrNull()?.id, visibleEntryCount) {
        if (visibleGroups.isNotEmpty() && isAtTop) {
            listState.animateScrollToItem(0)
        }
    }

    // 导出按钮弹出菜单状态。
    var showExportMenu by remember { mutableStateOf(false) }

    fun saveExportBytes(
        uri: android.net.Uri?,
        format: LogExporter.ExportFormat,
    ) {
        if (uri == null) return
        try {
            val bytes = LogExporter.buildBytesWithDiagnostics(context, logs, verbose, format)
            val os = context.contentResolver.openOutputStream(uri)
            if (os == null) {
                viewModel.showToast("保存失败：无法打开目标文件")
                return
            }
            os.use { it.write(bytes) }
        } catch (e: Exception) {
            viewModel.showToast("保存失败：${e.message ?: e.javaClass.simpleName}")
        }
    }

    // SAF 创建文档：系统“另存为”对话框允许用户选择保存位置并修改文件名。
    val saveTxtLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument(LogExporter.ExportFormat.TXT.mimeType),
        ) { uri -> saveExportBytes(uri, LogExporter.ExportFormat.TXT) }
    val saveJsonLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument(LogExporter.ExportFormat.JSON.mimeType),
        ) { uri -> saveExportBytes(uri, LogExporter.ExportFormat.JSON) }
    val saveZipLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument(LogExporter.ExportFormat.ZIP.mimeType),
        ) { uri -> saveExportBytes(uri, LogExporter.ExportFormat.ZIP) }

    GalaxyBackground {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(8.dp))
            PageTitle(
                "运行日志",
                titleSubtitle,
            ) {
                if (logs.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // 导出按钮作为下拉菜单触发器。
                        Box {
                            Row(
                                Modifier.clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                                    .clickableNoRipple { showExportMenu = true }
                                    .padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Filled.IosShare,
                                    contentDescription = localizedText("导出日志"),
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(14.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text("导出", color = MaterialTheme.colorScheme.primary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            }

                            DropdownMenu(
                                expanded = showExportMenu,
                                onDismissRequest = { showExportMenu = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("📁 保存 TXT") },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Outlined.Folder,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    },
                                    onClick = {
                                        showExportMenu = false
                                        saveTxtLauncher.launch(LogExporter.suggestFileName(format = LogExporter.ExportFormat.TXT))
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("📁 保存 JSON") },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Outlined.Folder,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    },
                                    onClick = {
                                        showExportMenu = false
                                        saveJsonLauncher.launch(LogExporter.suggestFileName(format = LogExporter.ExportFormat.JSON))
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("📁 保存 ZIP") },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Outlined.Folder,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    },
                                    onClick = {
                                        showExportMenu = false
                                        saveZipLauncher.launch(LogExporter.suggestFileName(format = LogExporter.ExportFormat.ZIP))
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("📤 分享 TXT") },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Outlined.Share,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    },
                                    onClick = {
                                        showExportMenu = false
                                        val result = LogExporter.exportAndShare(context, logs, verbose, LogExporter.ExportFormat.TXT)
                                        if (result.isFailure) viewModel.showToast(result.exceptionOrNull()?.message ?: "日志导出失败")
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("📤 分享 JSON") },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Outlined.Share,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    },
                                    onClick = {
                                        showExportMenu = false
                                        val result = LogExporter.exportAndShare(context, logs, verbose, LogExporter.ExportFormat.JSON)
                                        if (result.isFailure) viewModel.showToast(result.exceptionOrNull()?.message ?: "日志导出失败")
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("📤 分享 ZIP") },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Outlined.Share,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    },
                                    onClick = {
                                        showExportMenu = false
                                        val result = LogExporter.exportAndShare(context, logs, verbose, LogExporter.ExportFormat.ZIP)
                                        if (result.isFailure) viewModel.showToast(result.exceptionOrNull()?.message ?: "日志导出失败")
                                    },
                                )
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "清空",
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 14.sp,
                            modifier = Modifier.clickableNoRipple(viewModel::clearLogs),
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))

            // 统计、过滤与详细模式区域。
            if (allEntries.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        Modifier
                            .weight(1f)
                            .consumeHorizontalScrollOverflow()
                            .horizontalScroll(filterScrollState),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        LogStatChip(
                            "全部 ${allEntries.size}",
                            MaterialTheme.colorScheme.primary,
                            filter == LogFilter.ALL,
                        ) { filter = LogFilter.ALL }
                        LogStatChip("DBG ${stats.debug}", TextSecondary, filter == LogFilter.DEBUG) { filter = LogFilter.DEBUG }
                        LogStatChip("ℹ ${stats.info}", TextSecondary, filter == LogFilter.INFO) { filter = LogFilter.INFO }
                        LogStatChip("✓ ${stats.ok}", SuccessGreen, filter == LogFilter.OK) { filter = LogFilter.OK }
                        LogStatChip("⚠ ${stats.warn}", WarnAmber, filter == LogFilter.WARN) { filter = LogFilter.WARN }
                        LogStatChip("✗ ${stats.error}", DangerRed, filter == LogFilter.ERROR) { filter = LogFilter.ERROR }
                    }
                    Spacer(Modifier.width(10.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickableNoRipple { verbose = !verbose },
                    ) {
                        Text("详细日志", fontSize = 12.sp, color = TextSecondary)
                        Spacer(Modifier.width(6.dp))
                        AnimatedSwitch(
                            checked = verbose,
                            onCheckedChange = { verbose = it },
                            offColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f),
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                LogSearchField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    onClear = {
                        searchQuery = ""
                        debouncedSearchKeyword = ""
                    },
                )
                AnimatedVisibility(
                    visible = searchSummary.active,
                    enter = AppMotion.expandEnter(),
                    exit = AppMotion.expandExit(),
                ) {
                    Column {
                        Spacer(Modifier.height(8.dp))
                        StatusBadge(
                            text = searchSummary.label(),
                            color = MaterialTheme.colorScheme.primary,
                            shape = RoundedCornerShape(999.dp),
                            horizontalPadding = 10.dp,
                            verticalPadding = 5.dp,
                            fontSize = 11.sp,
                            backgroundAlpha = 0.10f,
                            borderAlpha = 0.28f,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
            }

            when {
                loadState is RepositoryLoadState.Error && logs.isEmpty() -> {
                    EmptyState(
                        badge = {
                            Box(
                                Modifier.size(64.dp).clip(RoundedCornerShape(20.dp))
                                    .background(MaterialTheme.colorScheme.error.copy(alpha = 0.12f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.AutoMirrored.Filled.ListAlt, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                            }
                        },
                        title = "日志读取失败",
                        subtitle = "本地日志暂时无法读取，请重试",
                        action = { Button(onClick = viewModel::refresh) { Text("重新加载") } },
                    )
                }
                loadState is RepositoryLoadState.InitialLoading -> {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SkeletonCard(modifier = Modifier.fillMaxWidth(), titleWidth = 72.dp, lineCount = 4)
                        SkeletonCard(modifier = Modifier.fillMaxWidth(), titleWidth = 96.dp, lineCount = 3)
                    }
                }
                logs.isEmpty() -> {
                    EmptyState(
                        badge = {
                            Box(
                                Modifier.size(
                                    64.dp,
                                ).clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ListAlt,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(30.dp),
                                )
                            }
                        },
                        title = "暂无日志",
                        subtitle = "执行签到后日志将实时显示在这里",
                    )
                }
                visibleGroups.isEmpty() -> {
                    EmptyState(
                        badge = {
                            Box(
                                Modifier.size(
                                    64.dp,
                                ).clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ListAlt,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(30.dp),
                                )
                            }
                        },
                        title = if (searchKeyword.isEmpty()) "暂无${filter.label}日志" else "未找到匹配日志",
                        subtitle =
                            if (searchKeyword.isEmpty()) {
                                "切换筛选条件可查看其它日志"
                            } else {
                                "清除搜索关键词或切换筛选条件后重试"
                            },
                    )
                }
                else -> {
                    PanelCard(modifier = Modifier.fillMaxWidth().weight(1f)) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            // 每次签到运行折叠为一个日志组，降低长日志阅读成本。
                            itemsIndexed(
                                visibleGroups,
                                key = { _, group -> logGroupStableItemKey(group, expandedLogGroupIdSet) },
                            ) { index, group ->
                                if (index == 0 || group.dateKey != visibleGroups[index - 1].dateKey) {
                                    DateDivider(group.dateKey)
                                }
                                LogRunGroupCard(
                                    group = group,
                                    expanded = isLogGroupExpanded(group, expandedLogGroupIdSet),
                                    onExpandedChange = { expanded -> setLogGroupExpanded(group, expanded) },
                                    verbose = verbose,
                                    searchQuery = searchKeyword,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }
}
