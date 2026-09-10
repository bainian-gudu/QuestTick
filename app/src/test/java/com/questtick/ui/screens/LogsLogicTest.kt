package com.questtick.ui.screens

import com.questtick.data.LogEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LogsLogicTest {
    @Test
    fun countLogStatsCountsKnownLevelsOnly() {
        val stats =
            countLogStats(
                listOf(
                    entry(1, "DEBUG"),
                    entry(2, "INFO"),
                    entry(3, "OK"),
                    entry(4, "WARN"),
                    entry(5, "ERROR"),
                    entry(6, "UNKNOWN"),
                ),
            )

        assertEquals(LogStats(debug = 1, info = 1, ok = 1, warn = 1, error = 1), stats)
        assertEquals("共 6 条 · ✓1  ⚠1  ✗1  ℹ1  DBG1", stats.subtitle(6))
    }

    @Test
    fun matchesLogFilterMatchesExactLevels() {
        val debug = entry(1, "DEBUG")
        val info = entry(2, "INFO")
        val ok = entry(3, "OK")
        val warn = entry(4, "WARN")
        val error = entry(5, "ERROR")

        assertTrue(matchesLogFilter(debug, LogFilter.ALL))
        assertTrue(matchesLogFilter(debug, LogFilter.DEBUG))
        assertTrue(matchesLogFilter(info, LogFilter.INFO))
        assertTrue(matchesLogFilter(ok, LogFilter.OK))
        assertTrue(matchesLogFilter(warn, LogFilter.WARN))
        assertTrue(matchesLogFilter(error, LogFilter.ERROR))

        assertFalse(matchesLogFilter(debug, LogFilter.INFO))
        assertFalse(matchesLogFilter(info, LogFilter.OK))
        assertFalse(matchesLogFilter(ok, LogFilter.ERROR))
    }

    @Test
    fun buildLogGroupsMergesPreludeIntoNextRunAndMarksEndedRun() {
        val prelude = entry(1000, "INFO", message = "准备执行任务")
        val start = entry(2000, "INFO", message = "开始执行签到")
        val ok = entry(3000, "OK", message = "原神签到成功")
        val end = entry(4000, "INFO", message = "签到结束")
        val misc = entry(5000, "WARN", message = "其它警告")

        val groups = buildLogGroups(listOf(prelude, start, ok, end, misc))

        assertEquals(2, groups.size)
        assertEquals("运行日志 · 00:00:01", groups[0].title)
        assertEquals("2026-07-03", groups[0].dateKey)
        assertFalse(groups[0].running)
        assertEquals(listOf(prelude, start, ok, end), groups[0].entries)
        assertEquals(LogStats(info = 3, ok = 1), groups[0].stats)
        assertEquals("其它日志 · 00:00:05", groups[1].title)
        assertEquals(listOf(misc), groups[1].entries)
    }

    @Test
    fun buildLogGroupsDoesNotMergeAcrossDates() {
        val start = entry(86_400_000L - 2_000L, "INFO", message = "开始执行签到", dateKey = "2026-07-03")
        val end = entry(86_400_000L + 1_000L, "INFO", message = "签到结束", dateKey = "2026-07-04")

        val groups = buildLogGroups(listOf(start, end))

        assertEquals(2, groups.size)
        assertEquals(listOf(start), groups[0].entries)
        assertEquals(listOf(end), groups[1].entries)
    }

    @Test
    fun buildLogGroupsDoesNotMergeRunAfterInterveningOtherLog() {
        val firstStart = entry(1_000, "INFO", message = "开始执行签到")
        val firstEnd = entry(2_000, "INFO", message = "签到结束")
        val other = entry(3_000, "WARN", message = "应用功能异常")
        val secondStart = entry(4_000, "INFO", message = "开始执行签到")
        val secondEnd = entry(5_000, "INFO", message = "签到结束")

        val groups = buildLogGroups(listOf(firstStart, firstEnd, other, secondStart, secondEnd))

        assertEquals(3, groups.size)
        assertEquals(listOf(firstStart, firstEnd), groups[0].entries)
        assertEquals(listOf(other), groups[1].entries)
        assertEquals(listOf(secondStart, secondEnd), groups[2].entries)
    }

    @Test
    fun buildLogGroupsDoesNotMergeSameTypeStartsAcrossAnInterveningLog() {
        val firstStart = entry(1_000, "INFO", message = "开始执行签到")
        val other = entry(2_000, "WARN", message = "网络状态发生变化")
        val secondStart = entry(3_000, "INFO", message = "开始执行签到")
        val secondEnd = entry(4_000, "INFO", message = "签到结束")

        val groups = buildLogGroups(listOf(firstStart, other, secondStart, secondEnd))

        assertEquals(2, groups.size)
        assertEquals(listOf(firstStart, other), groups[0].entries)
        assertEquals(listOf(secondStart, secondEnd), groups[1].entries)
    }

    @Test
    fun buildLogGroupsSortsEntriesByTimestampBeforeGrouping() {
        val end = entry(3_000, "INFO", message = "签到结束")
        val start = entry(1_000, "INFO", message = "开始执行签到")
        val ok = entry(2_000, "OK", message = "签到成功")

        val group = buildLogGroups(listOf(end, ok, start)).single()

        assertEquals(listOf(start, ok, end), group.entries)
    }

    @Test
    fun buildLogGroupsMarksUnfinishedRunAsRunning() {
        val start = entry(1000, "INFO", message = "开始执行签到")
        val warn = entry(2000, "WARN", message = "等待接口响应")

        val groups = buildLogGroups(listOf(start, warn))

        assertEquals(1, groups.size)
        assertEquals("签到进行中 · 00:00:01", groups.single().title)
        assertTrue(groups.single().running)
        assertEquals(LogStats(info = 1, warn = 1), groups.single().stats)
    }

    @Test
    fun buildLogGroupsFlushesPreviousRunningGroupOnNewStart() {
        val firstStart = entry(1000, "INFO", message = "开始执行签到")
        val firstWarn = entry(2000, "WARN", message = "第一次仍在运行")
        val secondStart = entry(3000, "INFO", message = "开始执行签到")
        val secondEnd = entry(4000, "INFO", message = "签到结束")

        val groups = buildLogGroups(listOf(firstStart, firstWarn, secondStart, secondEnd))

        assertEquals(2, groups.size)
        assertTrue(groups[0].running)
        assertEquals(listOf(firstStart, firstWarn), groups[0].entries)
        assertFalse(groups[1].running)
        assertEquals(listOf(secondStart, secondEnd), groups[1].entries)
    }

    @Test
    fun buildLogGroupsMarksLatestOpenGroupAsRunningWhenCoordinatorActive() {
        val misc = entry(1000, "INFO", message = "准备刷新日志")

        val groups = buildLogGroups(listOf(misc), activeRunStartedAt = 900)

        assertEquals(1, groups.size)
        assertEquals("签到进行中 · 00:00:01", groups.single().title)
        assertTrue(groups.single().running)
        assertEquals(listOf(misc), groups.single().entries)
    }

    @Test
    fun buildLogGroupsDoesNotMarkOldMiscGroupWhenCoordinatorActive() {
        val misc = entry(1000, "INFO", message = "旧日志")

        val groups = buildLogGroups(listOf(misc), activeRunStartedAt = 2_000)

        assertEquals(1, groups.size)
        assertEquals("其它日志 · 00:00:01", groups.single().title)
        assertFalse(groups.single().running)
    }

    @Test
    fun buildLogGroupsNamesNonRunErrorsAsErrorLogs() {
        val info = entry(1000, "INFO", message = "应用功能开始")
        val error = entry(2000, "ERROR", message = "应用功能失败")

        val group = buildLogGroups(listOf(info, error)).single()

        assertEquals("错误日志 · 00:00:01", group.title)
        assertFalse(group.running)
    }

    @Test
    fun buildLogGroupsDoesNotOverrideEndedRunWhenCoordinatorStillActive() {
        val start = entry(1000, "INFO", message = "开始执行签到")
        val end = entry(2000, "INFO", message = "签到结束")

        val groups = buildLogGroups(listOf(start, end), activeRunStartedAt = 900)

        assertEquals(1, groups.size)
        assertEquals("运行日志 · 00:00:01", groups.single().title)
        assertFalse(groups.single().running)
    }

    @Test
    fun buildLogGroupsKeepsSameIdWhenRunningRunCompletes() {
        val start = entry(1000, "INFO", message = "开始执行签到")
        val ok = entry(2000, "OK", message = "原神签到成功")
        val end = entry(3000, "INFO", message = "签到结束")

        val runningGroup = buildLogGroups(listOf(start, ok)).single()
        val completedGroup = buildLogGroups(listOf(start, ok, end)).single()

        assertTrue(runningGroup.running)
        assertFalse(completedGroup.running)
        assertEquals(runningGroup.id, completedGroup.id)
        assertTrue(completedGroup.expansionKeys.contains(runningGroup.id))
    }

    @Test
    fun buildLogGroupsKeepsSameIdWhenPreludeBecomesRun() {
        val prelude = entry(1000, "INFO", message = "云原神版本已准备")
        val start = entry(2000, "INFO", message = "开始执行签到")
        val end = entry(3000, "INFO", message = "签到结束")

        val preparingGroup = buildLogGroups(listOf(prelude), activeRunStartedAt = 900).single()
        val completedGroup = buildLogGroups(listOf(prelude, start, end)).single()

        assertTrue(preparingGroup.running)
        assertFalse(completedGroup.running)
        assertEquals(preparingGroup.id, completedGroup.id)
        assertTrue(completedGroup.expansionKeys.contains(preparingGroup.id))
    }

    @Test
    fun buildLogGroupsExpansionKeysBridgeWhenGroupFirstEntryChanges() {
        val prelude = entry(500, "INFO", message = "云原神版本已准备")
        val start = entry(1000, "INFO", message = "开始执行签到")
        val ok = entry(2000, "OK", message = "原神签到成功")

        val initialGroup = buildLogGroups(listOf(start, ok)).single()
        val mergedGroup = buildLogGroups(listOf(prelude, start, ok)).single()

        assertTrue(initialGroup.running)
        assertTrue(mergedGroup.running)
        assertFalse(initialGroup.id == mergedGroup.id)
        assertTrue(mergedGroup.expansionKeys.contains(initialGroup.id))
    }

    @Test
    fun logGroupExpansionDefaultsToCollapsed() {
        val group = buildLogGroups(listOf(entry(1000, "INFO", message = "开始执行签到"))).single()

        assertFalse(isLogGroupExpanded(group, emptySet()))
    }

    @Test
    fun updateExpandedLogGroupKeysExpandsAndCollapsesOnlyByUserAction() {
        val start = entry(1000, "INFO", message = "开始执行签到")
        val ok = entry(2000, "OK", message = "原神签到成功")
        val end = entry(3000, "INFO", message = "签到结束")
        val runningGroup = buildLogGroups(listOf(start, ok)).single()
        val completedGroup = buildLogGroups(listOf(start, ok, end)).single()

        val expandedKeys = updateExpandedLogGroupKeys(emptyList(), runningGroup, expanded = true)

        assertTrue(isLogGroupExpanded(runningGroup, expandedKeys.toSet()))
        assertTrue(isLogGroupExpanded(completedGroup, expandedKeys.toSet()))
        assertEquals(
            emptyList<String>(),
            updateExpandedLogGroupKeys(expandedKeys, completedGroup, expanded = false),
        )
    }

    @Test
    fun logGroupStableItemKeyUsesExpandedAnchorWhenGroupFirstEntryChanges() {
        val prelude = entry(500, "INFO", message = "云原神版本已准备")
        val start = entry(1000, "INFO", message = "开始执行签到")
        val ok = entry(2000, "OK", message = "原神签到成功")
        val initialGroup = buildLogGroups(listOf(start, ok)).single()
        val mergedGroup = buildLogGroups(listOf(prelude, start, ok)).single()

        val expandedKeys = updateExpandedLogGroupKeys(emptyList(), initialGroup, expanded = true).toSet()

        assertEquals(initialGroup.id, logGroupStableItemKey(mergedGroup, expandedKeys))
    }

    @Test
    fun updateExpandedLogGroupKeysKeepsExpandedWhenGroupFirstEntryChanges() {
        val prelude = entry(500, "INFO", message = "云原神版本已准备")
        val start = entry(1000, "INFO", message = "开始执行签到")
        val ok = entry(2000, "OK", message = "原神签到成功")
        val initialGroup = buildLogGroups(listOf(start, ok)).single()
        val mergedGroup = buildLogGroups(listOf(prelude, start, ok)).single()

        val expandedKeys = updateExpandedLogGroupKeys(emptyList(), initialGroup, expanded = true)

        assertTrue(isLogGroupExpanded(initialGroup, expandedKeys.toSet()))
        assertTrue(isLogGroupExpanded(mergedGroup, expandedKeys.toSet()))
    }

    @Test
    fun updateExpandedLogGroupKeysKeepsExpandedWhenPartialGroupBecomesComplete() {
        val start = entry(1000, "INFO", message = "开始执行签到")
        val middle = entry(2000, "OK", message = "原神签到成功")
        val end = entry(3000, "INFO", message = "签到结束")
        val partialGroup = buildLogGroups(listOf(middle), activeRunStartedAt = 1500).single()
        val completedGroup = buildLogGroups(listOf(start, middle, end)).single()

        val expandedKeys = updateExpandedLogGroupKeys(emptyList(), partialGroup, expanded = true)

        assertTrue(isLogGroupExpanded(partialGroup, expandedKeys.toSet()))
        assertTrue(isLogGroupExpanded(completedGroup, expandedKeys.toSet()))
    }

    @Test
    fun updateExpandedLogGroupKeysCapsStoredKeys() {
        val first = buildLogGroups(listOf(entry(1000, "INFO", message = "开始执行签到"))).single()
        val second = buildLogGroups(listOf(entry(2000, "INFO", message = "开始执行签到"))).single()
        val firstExpanded = updateExpandedLogGroupKeys(emptyList(), first, expanded = true, maxKeys = 1)
        val secondExpanded = updateExpandedLogGroupKeys(firstExpanded, second, expanded = true, maxKeys = 1)

        assertFalse(isLogGroupExpanded(first, secondExpanded.toSet()))
        assertTrue(isLogGroupExpanded(second, secondExpanded.toSet()))
    }

    @Test
    fun filterLogGroupsDropsEmptyGroupsAndRecalculatesStats() {
        val firstGroup =
            buildLogGroups(
                listOf(
                    entry(1000, "INFO", message = "开始执行签到"),
                    entry(2000, "OK", message = "成功"),
                    entry(3000, "INFO", message = "签到结束"),
                ),
            ).single()
        val secondGroup = buildLogGroups(listOf(entry(4000, "WARN", message = "其它警告"))).single()

        val filtered = filterLogGroups(listOf(firstGroup, secondGroup), LogFilter.OK)

        assertEquals(1, filtered.size)
        assertEquals(listOf(entry(2000, "OK", message = "成功")), filtered.single().entries)
        assertEquals(LogStats(ok = 1), filtered.single().stats)
        assertEquals("共 1 条 · ✓1  ⚠0  ✗0  ℹ0  DBG0", filtered.single().subtitle)
    }

    @Test
    fun matchesLogSearchSearchesMessageDetailLevelTimeAndDate() {
        val entry =
            entry(
                timestamp = 1000,
                level = "ERROR",
                message = "原神签到失败",
                detail = "retcode=-5003",
                dateKey = "2026-07-03",
            )

        assertTrue(matchesLogSearch(entry, "原神"))
        assertTrue(matchesLogSearch(entry, "RETCode"))
        assertTrue(matchesLogSearch(entry, "error"))
        assertTrue(matchesLogSearch(entry, "00:00:01"))
        assertTrue(matchesLogSearch(entry, "2026-07"))
        assertTrue(matchesLogSearch(entry, "  失败  "))
        assertFalse(matchesLogSearch(entry, "云原神"))
    }

    @Test
    fun filterLogGroupsAppliesFilterAndSearchTogether() {
        val group =
            buildLogGroups(
                listOf(
                    entry(1000, "INFO", message = "开始执行签到"),
                    entry(2000, "OK", message = "原神签到成功"),
                    entry(3000, "OK", message = "崩坏星穹铁道签到成功"),
                    entry(4000, "WARN", message = "网络波动"),
                    entry(5000, "INFO", message = "签到结束"),
                ),
            ).single()

        val filtered = filterLogGroups(listOf(group), LogFilter.OK, query = "原神")

        assertEquals(1, filtered.size)
        assertEquals(listOf(entry(2000, "OK", message = "原神签到成功")), filtered.single().entries)
        assertEquals(LogStats(ok = 1), filtered.single().stats)
    }

    @Test
    fun filterLogGroupsReturnsAllEntriesForBlankSearch() {
        val group =
            buildLogGroups(
                listOf(
                    entry(1000, "INFO", message = "开始执行签到"),
                    entry(2000, "OK", message = "原神签到成功"),
                    entry(3000, "INFO", message = "签到结束"),
                ),
            ).single()

        assertEquals(group.entries, filterLogGroups(listOf(group), LogFilter.ALL, query = " ").single().entries)
    }

    @Test
    fun logSearchRangesFindsCaseInsensitiveNonOverlappingRanges() {
        assertEquals(listOf(0 until 2, 4 until 6), logSearchRanges("Aa--aa", "aa"))
        assertEquals(emptyList<IntRange>(), logSearchRanges("Aa--aa", ""))
        assertEquals(emptyList<IntRange>(), logSearchRanges("Aa--aa", "bb"))
    }

    @Test
    fun buildLogSearchSummaryDescribesActiveFilterAndQuery() {
        val group =
            buildLogGroups(
                listOf(
                    entry(1000, "INFO", message = "开始执行签到"),
                    entry(2000, "OK", message = "原神签到成功"),
                    entry(3000, "INFO", message = "签到结束"),
                ),
            ).single()

        assertEquals(
            "搜索“原神”：1/3 条 · 1 组",
            buildLogSearchSummary(
                visibleGroups = filterLogGroups(listOf(group), LogFilter.ALL, query = "原神"),
                totalEntries = 3,
                query = "原神",
                filter = LogFilter.ALL,
            ).label(),
        )
        assertEquals(
            "成功：1/3 条 · 1 组",
            buildLogSearchSummary(
                visibleGroups = filterLogGroups(listOf(group), LogFilter.OK),
                totalEntries = 3,
                query = "",
                filter = LogFilter.OK,
            ).label(),
        )
        assertEquals(
            "成功中搜索“原神”：1/3 条 · 1 组",
            buildLogSearchSummary(
                visibleGroups = filterLogGroups(listOf(group), LogFilter.OK, query = "原神"),
                totalEntries = 3,
                query = "原神",
                filter = LogFilter.OK,
            ).label(),
        )
    }

    @Test
    fun prepareLogsProcessesWholeSnapshot() {
        val logs =
            listOf(
                LogEntry(1000, "INFO", "开始执行签到", "cookie=secret"),
                LogEntry(2000, "OK", "原神签到成功", "token=secret"),
                LogEntry(3000, "INFO", "签到结束", ""),
            )

        val prepared =
            prepareLogs(
                logs = logs,
                formatTime = { "T$it" },
                formatDate = { "D$it" },
                mask = { it.replace("secret", "***") },
            )

        assertEquals(3, prepared.entries.size)
        assertEquals(3, prepared.sourceSize)
        assertEquals(3000L, prepared.sourceLastTimestamp)
        assertEquals("T1000", prepared.entries.first().formattedTime)
        assertEquals("D3000", prepared.entries.last().dateKey)
        assertEquals("cookie=***", prepared.entries.first().safeDetail)
        assertEquals("token=***", prepared.entries[1].safeDetail)
        assertEquals(LogStats(info = 2, ok = 1), prepared.stats)
    }

    @Test
    fun preparedLogsMatchesSourceBySizeAndLastTimestamp() {
        val first = entry(1000, "INFO")
        val second = entry(2000, "WARN")
        val prepared = PreparedLogs(listOf(first, second), LogStats(info = 1, warn = 1))

        assertTrue(prepared.matchesSource(size = 2, lastTimestamp = 2000L))
        assertFalse(prepared.matchesSource(size = 1, lastTimestamp = 2000L))
        assertFalse(prepared.matchesSource(size = 2, lastTimestamp = 1000L))
        assertEquals(NO_LOG_TIMESTAMP, PreparedLogs(emptyList(), LogStats()).sourceLastTimestamp)
    }

    @Test
    fun logFilterLabelsStayStableForUi() {
        assertSame(LogFilter.ALL, LogFilter.entries.first())
        assertEquals(listOf("全部", "调试", "信息", "成功", "警告", "错误"), LogFilter.entries.map { it.label })
    }

    private fun entry(
        timestamp: Long,
        level: String,
        message: String = level,
        detail: String = "",
        dateKey: String = "2026-07-03",
    ): ProcessedLogEntry =
        ProcessedLogEntry(
            timestamp = timestamp,
            level = level,
            formattedTime = "00:00:${(timestamp / 1000).toString().padStart(2, '0')}",
            dateKey = dateKey,
            safeMessage = message,
            safeDetail = detail,
        )
}
