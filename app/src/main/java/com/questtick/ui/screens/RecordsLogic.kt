package com.questtick.ui.screens

/** 签到记录筛选、分组和展示状态的纯 UI 逻辑。 */

import com.questtick.data.FailureCategory
import com.questtick.data.RunRecord
import com.questtick.data.TaskResult

internal enum class RecFilter(
    val label: String,
) {
    ALL("全部"),
    SUCCESS("成功"),
    FAILED("失败"),
    ALREADY("已签到"),
    RESULT_UNKNOWN("待确认"),
    SKIPPED("跳过"),
}

internal fun matchesRecordFilter(
    result: TaskResult,
    filter: RecFilter,
): Boolean =
    when (filter) {
        RecFilter.ALL -> true
        RecFilter.SUCCESS -> result.success && !result.skipped && !result.alreadySigned
        RecFilter.FAILED -> !result.success && !result.skipped
        RecFilter.ALREADY -> result.success && result.alreadySigned
        RecFilter.RESULT_UNKNOWN -> result.failureCategory == FailureCategory.RESULT_UNKNOWN
        RecFilter.SKIPPED -> result.skipped && result.failureCategory != FailureCategory.RESULT_UNKNOWN
    }

/**
 * 记录卡片的轻量索引。索引只保存运行位置和统计数字，不持有筛选后的结果列表。
 * 详情结果在用户展开对应卡片时才从历史快照中读取。
 */
internal data class RecordRunSummary(
    val runIndex: Int,
    val id: String,
    val timestamp: Long,
    val total: Int,
    val succeeded: Int,
    val alreadySigned: Int,
    val failed: Int,
    val resultUnknown: Int,
    val skipped: Int,
    private val filterCounts: IntArray,
) {
    fun count(filter: RecFilter): Int = filterCounts[filter.ordinal]
}

internal class RecordsIndex(
    val allResultCount: Int,
    val counts: Map<RecFilter, Int>,
    val runsByFilter: Map<RecFilter, List<RecordRunSummary>>,
)

/** 在后台建立轻量索引；不会为未显示的运行复制结果列表。 */
internal fun buildRecordsIndex(
    history: List<RunRecord>,
): RecordsIndex {
    if (history.isEmpty()) return RecordsIndex(0, emptyMap(), emptyMap())

    val counts = RecFilter.entries.associateWithTo(HashMap(RecFilter.entries.size)) { 0 }
    val grouped = RecFilter.entries.associateWithTo(HashMap(RecFilter.entries.size)) { ArrayList<RecordRunSummary>() }
    var allResultCount = 0

    history.forEachIndexed { index, run ->
        val filterCounts = IntArray(RecFilter.entries.size)
        var succeeded = 0
        var alreadySigned = 0
        var failed = 0
        var resultUnknown = 0
        var skipped = 0

        run.results.forEach { result ->
            val primary = recordPrimaryFilter(result)
            filterCounts[RecFilter.ALL.ordinal]++
            filterCounts[primary.ordinal]++
            counts[RecFilter.ALL] = counts.getValue(RecFilter.ALL) + 1
            counts[primary] = counts.getValue(primary) + 1
            allResultCount++
            when (primary) {
                RecFilter.SUCCESS -> succeeded++
                RecFilter.ALREADY -> alreadySigned++
                RecFilter.FAILED -> failed++
                RecFilter.RESULT_UNKNOWN -> resultUnknown++
                RecFilter.SKIPPED -> skipped++
                RecFilter.ALL -> Unit
            }
        }

        val summary =
            RecordRunSummary(
                runIndex = index,
                id = recordRunStableId(run),
                timestamp = run.timestamp,
                total = run.results.size,
                succeeded = succeeded,
                alreadySigned = alreadySigned,
                failed = failed,
                resultUnknown = resultUnknown,
                skipped = skipped,
                filterCounts = filterCounts,
            )
        RecFilter.entries.forEach { filter ->
            if (summary.count(filter) > 0) grouped.getValue(filter).add(summary)
        }
    }

    return RecordsIndex(
        allResultCount = allResultCount,
        counts = counts,
        runsByFilter = grouped.mapValues { (_, values) -> values.toList() },
    )
}

internal fun recordPrimaryFilter(result: TaskResult): RecFilter =
    when {
        result.failureCategory == FailureCategory.RESULT_UNKNOWN -> RecFilter.RESULT_UNKNOWN
        result.skipped -> RecFilter.SKIPPED
        result.success && result.alreadySigned -> RecFilter.ALREADY
        result.success -> RecFilter.SUCCESS
        else -> RecFilter.FAILED
    }

internal fun recordRunStableId(run: RunRecord): String {
    val first = run.results.firstOrNull()
    val last = run.results.lastOrNull()
    return buildString {
        append(run.timestamp)
        append('_')
        append(run.results.size)
        append('_')
        append(first?.accountLabel.orEmpty().hashCode())
        append('_')
        append(first?.gameKey.orEmpty().hashCode())
        append('_')
        append(last?.message.orEmpty().hashCode())
    }
}

internal fun recordResultStableKey(
    result: TaskResult,
    index: Int,
): String =
    buildString {
        append(result.accountLabel)
        append('|')
        append(result.gameKey)
        append('|')
        append(result.game)
        append('|')
        append(result.message.hashCode())
        append('|')
        append(index)
    }
