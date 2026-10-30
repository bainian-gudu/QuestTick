@file:Suppress(
    "detekt:CyclomaticComplexMethod",
    "detekt:TooManyFunctions",
)

package com.questtick.ui.screens

import androidx.compose.runtime.Immutable

/** 预处理后的日志条目；脱敏与时间格式化在 remember 中一次完成。 */
@Immutable
internal data class ProcessedLogEntry(
    val timestamp: Long,
    val level: String,
    val formattedTime: String,
    val dateKey: String, // 日期分隔键，格式 yyyy-MM-dd
    val safeMessage: String,
    val safeDetail: String,
)

@Immutable
internal data class PreparedLogs(
    val entries: List<ProcessedLogEntry>,
    val stats: LogStats,
    val sourceSize: Int = entries.size,
    val sourceLastTimestamp: Long = entries.lastOrNull()?.timestamp ?: NO_LOG_TIMESTAMP,
) {
    fun matchesSource(
        size: Int,
        lastTimestamp: Long,
    ): Boolean = sourceSize == size && sourceLastTimestamp == lastTimestamp
}

internal const val NO_LOG_TIMESTAMP = Long.MIN_VALUE
internal const val MAX_EXPANDED_LOG_GROUP_KEYS = 240

internal fun prepareLogs(
    logs: List<com.questtick.data.LogEntry>,
    formatTime: (Long) -> String,
    formatDate: (Long) -> String,
    mask: (String) -> String,
): PreparedLogs {
    if (logs.isEmpty()) return PreparedLogs(emptyList(), LogStats())
    val result = ArrayList<ProcessedLogEntry>(logs.size)
    // 只按时间戳稳定排序：同一毫秒内的多条日志保留写入顺序，
    // 避免字典序把「签到结束」排到「签到完成」汇总之前。
    for (entry in logs.sortedBy { it.timestamp }) {
        result.add(
            ProcessedLogEntry(
                timestamp = entry.timestamp,
                level = entry.level,
                formattedTime = formatTime(entry.timestamp),
                dateKey = formatDate(entry.timestamp),
                safeMessage = mask(entry.message),
                safeDetail = mask(entry.detail),
            ),
        )
    }
    return PreparedLogs(
        entries = result,
        stats = countLogStats(result),
        sourceSize = logs.size,
        sourceLastTimestamp = logs.lastOrNull()?.timestamp ?: NO_LOG_TIMESTAMP,
    )
}

internal enum class LogFilter(
    val label: String,
) {
    ALL("全部"),
    DEBUG("调试"),
    INFO("信息"),
    OK("成功"),
    WARN("警告"),
    ERROR("错误"),
}

internal fun matchesLogFilter(
    entry: ProcessedLogEntry,
    filter: LogFilter,
): Boolean =
    when (filter) {
        LogFilter.ALL -> true
        LogFilter.DEBUG -> entry.level == "DEBUG"
        LogFilter.INFO -> entry.level == "INFO"
        LogFilter.OK -> entry.level == "OK"
        LogFilter.WARN -> entry.level == "WARN"
        LogFilter.ERROR -> entry.level == "ERROR"
    }

@Immutable
internal data class LogRunGroup(
    val id: String,
    val title: String,
    val subtitle: String,
    val dateKey: String,
    val entries: List<ProcessedLogEntry>,
    val stats: LogStats,
    val running: Boolean,
    val expansionKeys: List<String>,
)

internal fun logGroupEntryKey(entry: ProcessedLogEntry): String = "${entry.timestamp}_${entry.level}_${entry.safeMessage.hashCode()}"

internal fun logGroupExpansionKeys(group: LogRunGroup): List<String> = group.expansionKeys.ifEmpty { listOf(group.id) }

internal fun isLogGroupExpanded(
    group: LogRunGroup,
    expandedKeys: Set<String>,
): Boolean = logGroupExpansionKeys(group).any { it in expandedKeys }

internal fun logGroupStableItemKey(
    group: LogRunGroup,
    expandedKeys: Set<String>,
): String = logGroupExpansionKeys(group).firstOrNull { it in expandedKeys } ?: group.id

internal fun updateExpandedLogGroupKeys(
    currentKeys: List<String>,
    group: LogRunGroup,
    expanded: Boolean,
    maxKeys: Int = MAX_EXPANDED_LOG_GROUP_KEYS,
): List<String> {
    val groupKeys = logGroupExpansionKeys(group)
    return if (expanded) {
        (currentKeys + groupKeys).distinct().takeLast(maxKeys.coerceAtLeast(1))
    } else {
        currentKeys.filterNot { it in groupKeys }
    }
}

internal fun buildLogGroups(
    entries: List<ProcessedLogEntry>,
    activeRunStartedAt: Long = 0L,
): List<LogRunGroup> {
    if (entries.isEmpty()) return emptyList()

    val groups = ArrayList<LogRunGroup>()
    val current = ArrayList<ProcessedLogEntry>()
    var currentIsRun = false
    var currentEnded = false

    fun flush() {
        currentEnded = false
        if (current.isEmpty()) return
        val list = current.toList()
        val first = list.first()
        val stats = countLogStats(list)
        val running = currentIsRun && list.none { isRunEndLog(it) }
        // 非运行分组的标题按组内日志类型取，不再固定叫「其它日志」。
        val title =
            when {
                currentIsRun && running -> "签到进行中 · ${first.formattedTime}"
                currentIsRun -> "运行日志 · ${first.formattedTime}"
                else -> "${logGroupTypeLabel(stats)} · ${first.formattedTime}"
            }
        val subtitle = stats.subtitle(list.size)
        val expansionKeys = list.map(::logGroupEntryKey).distinct()
        groups.add(
            LogRunGroup(
                // ID 只绑定日志组第一条日志，不绑定“进行中/已完成”等展示状态，
                // 避免任务状态变化后用户已展开的日志组被重置为折叠。
                id = expansionKeys.first(),
                title = title,
                subtitle = subtitle,
                dateKey = first.dateKey,
                entries = list,
                stats = stats,
                running = running,
                expansionKeys = expansionKeys,
            ),
        )
        current.clear()
        currentIsRun = false
    }

    entries
        // 稳定排序：同一毫秒内的多条日志保留写入顺序，不能被 level/message 字典序打乱。
        .sortedBy { it.timestamp }
        .forEach { entry ->
            if (current.isNotEmpty() && entry.dateKey != current.first().dateKey) {
                flush()
            }
            if (currentEnded && !isRunSummaryLog(entry)) {
                // 结束标记之后只有「签到完成」汇总仍属于本次运行，其它日志一律独立成组。
                flush()
            }
            if (isRunStartLog(entry)) {
                // 只有首个签到组前的预备日志可以并入该组；已结束签到后的其它日志会阻断合并。
                if (currentIsRun || groups.isNotEmpty()) flush()
                currentIsRun = true
            }
            current.add(entry)
            if (currentIsRun && isRunEndLog(entry)) {
                // 不立刻收尾：同一毫秒内紧随其后的「签到完成」汇总必须并入本组，
                // 否则结束标记会把汇总挤成一个独立的「其它日志」组。
                currentEnded = true
            }
        }
    flush()
    if (activeRunStartedAt > 0L && groups.isNotEmpty()) {
        val index = groups.lastIndex
        val latest = groups[index]
        val hasRunEnd = latest.entries.any { isRunEndLog(it) }
        val hasActiveRunLog = latest.entries.any { it.timestamp >= activeRunStartedAt }
        if (hasActiveRunLog && !hasRunEnd && !latest.running) {
            val first = latest.entries.first()
            groups[index] =
                latest.copy(
                    title = "签到进行中 · ${first.formattedTime}",
                    running = true,
                )
        }
    }
    return groups
}

internal fun filterLogGroups(
    groups: List<LogRunGroup>,
    filter: LogFilter,
    query: String = "",
): List<LogRunGroup> =
    groups.mapNotNull { group ->
        val filtered =
            group.entries.filter { entry ->
                matchesLogFilter(entry, filter) && matchesLogSearch(entry, query)
            }
        if (filtered.isEmpty()) {
            null
        } else {
            val filteredStats = countLogStats(filtered)
            group.copy(
                entries = filtered,
                stats = filteredStats,
                subtitle = filteredStats.subtitle(filtered.size),
            )
        }
    }

@Immutable
internal data class LogSearchSummary(
    val query: String,
    val filter: LogFilter,
    val visibleEntries: Int,
    val totalEntries: Int,
    val visibleGroups: Int,
) {
    val active: Boolean get() = query.isNotBlank() || filter != LogFilter.ALL

    fun label(): String =
        when {
            totalEntries == 0 -> "暂无日志"
            !active -> "共 $totalEntries 条日志"
            query.isBlank() -> "${filter.label}：$visibleEntries/$totalEntries 条 · $visibleGroups 组"
            filter == LogFilter.ALL -> "搜索“$query”：$visibleEntries/$totalEntries 条 · $visibleGroups 组"
            else -> "${filter.label}中搜索“$query”：$visibleEntries/$totalEntries 条 · $visibleGroups 组"
        }
}

internal fun buildLogSearchSummary(
    visibleGroups: List<LogRunGroup>,
    totalEntries: Int,
    query: String,
    filter: LogFilter,
): LogSearchSummary =
    LogSearchSummary(
        query = query.trim(),
        filter = filter,
        visibleEntries = visibleGroups.sumOf { it.entries.size },
        totalEntries = totalEntries,
        visibleGroups = visibleGroups.size,
    )

internal fun logSearchRanges(
    text: String,
    query: String,
): List<IntRange> {
    val keyword = query.trim()
    if (text.isEmpty() || keyword.isEmpty()) return emptyList()
    val ranges = mutableListOf<IntRange>()
    var startIndex = 0
    while (startIndex <= text.length - keyword.length) {
        val index = text.indexOf(keyword, startIndex, ignoreCase = true)
        if (index < 0) break
        ranges.add(index until index + keyword.length)
        startIndex = index + keyword.length
    }
    return ranges
}

internal fun matchesLogSearch(
    entry: ProcessedLogEntry,
    query: String,
): Boolean {
    val keyword = query.trim()
    if (keyword.isEmpty()) return true
    return entry.safeMessage.contains(keyword, ignoreCase = true) ||
        entry.safeDetail.contains(keyword, ignoreCase = true) ||
        entry.level.contains(keyword, ignoreCase = true) ||
        entry.formattedTime.contains(keyword, ignoreCase = true) ||
        entry.dateKey.contains(keyword, ignoreCase = true)
}

internal fun isRunStartLog(entry: ProcessedLogEntry): Boolean = entry.safeMessage.contains("开始执行签到")

internal fun isRunEndLog(entry: ProcessedLogEntry): Boolean = entry.safeMessage.contains("签到结束")

/** 运行汇总行（签到完成：成功…）：无论排序如何都必须并入本次运行分组。 */
internal fun isRunSummaryLog(entry: ProcessedLogEntry): Boolean = entry.safeMessage.startsWith("签到完成：成功")

@Immutable
internal data class LogStats(
    val debug: Int = 0,
    val info: Int = 0,
    val ok: Int = 0,
    val warn: Int = 0,
    val error: Int = 0,
) {
    fun subtitle(total: Int): String = "共 $total 条 · ✓$ok  ⚠$warn  ✗$error  ℹ$info  DBG$debug"
}

/**
 * 非运行分组的标题类型：按组内最高级别取标签，命名与日志页筛选芯片（调试/信息/成功/警告/错误）一致。
 */
internal fun logGroupTypeLabel(stats: LogStats): String =
    when {
        stats.error > 0 -> "错误日志"
        stats.warn > 0 -> "警告日志"
        stats.ok > 0 -> "成功日志"
        stats.info > 0 -> "信息日志"
        stats.debug > 0 -> "调试日志"
        else -> "其它日志"
    }

internal fun countLogStats(list: List<ProcessedLogEntry>): LogStats =
    LogStats(
        debug = list.count { it.level == "DEBUG" },
        info = list.count { it.level == "INFO" },
        ok = list.count { it.level == "OK" },
        warn = list.count { it.level == "WARN" },
        error = list.count { it.level == "ERROR" },
    )
