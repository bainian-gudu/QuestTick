package com.questtick.ui.screens

/** 签到记录筛选、分组和展示状态的纯 UI 逻辑。 */

import androidx.compose.runtime.Immutable
import com.questtick.data.FailureCategory
import com.questtick.data.RunRecord
import com.questtick.data.TaskResult

internal enum class RecFilter(val label: String) {
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

@Immutable
internal data class RecordRunGroup(
    val id: String,
    val run: RunRecord,
    val formattedTime: String,
    val results: List<TaskResult>,
    val succeeded: Int,
    val alreadySigned: Int,
    val failed: Int,
    val resultUnknown: Int,
    val skipped: Int,
) {
    val total: Int get() = results.size
}

@Immutable
internal data class RecordsCache(
    val allResults: List<TaskResult>,
    val counts: Map<RecFilter, Int>,
    val filteredRuns: Map<RecFilter, List<RecordRunGroup>>,
)

internal fun buildRecordsCache(
    history: List<RunRecord>,
    formatTimestamp: (Long) -> String = { it.toString() },
): RecordsCache {
    if (history.isEmpty()) {
        return RecordsCache(emptyList(), emptyMap(), emptyMap())
    }

    val estimatedSize = history.sumOf { it.results.size }
    val allResults = ArrayList<TaskResult>(estimatedSize)
    val counts = RecFilter.entries.associateWithTo(HashMap(RecFilter.entries.size)) { 0 }
    val groupedByFilter = RecFilter.entries.associateWithTo(HashMap(RecFilter.entries.size)) { ArrayList<RecordRunGroup>() }

    history.forEach { run ->
        allResults.addAll(run.results)
        val formattedTime = formatTimestamp(run.timestamp)
        val baseId = recordRunStableId(run)
        val allStats = MutableRecordStats()
        val bucketStats = RecFilter.entries.associateWithTo(HashMap(RecFilter.entries.size)) { MutableRecordStats() }
        val buckets = RecFilter.entries.associateWithTo(HashMap(RecFilter.entries.size)) { ArrayList<TaskResult>() }

        for (result in run.results) {
            val filter = recordPrimaryFilter(result)
            allStats.add(result)
            bucketStats.getValue(filter).add(result)
            buckets.getValue(filter).add(result)
            counts[RecFilter.ALL] = counts.getValue(RecFilter.ALL) + 1
            counts[filter] = counts.getValue(filter) + 1
        }

        val allRecordStats = allStats.toRecordStats()
        groupedByFilter.getValue(RecFilter.ALL).add(
            RecordRunGroup(
                id = "${baseId}_all",
                run = run,
                formattedTime = formattedTime,
                results = run.results,
                succeeded = allRecordStats.succeeded,
                alreadySigned = allRecordStats.alreadySigned,
                failed = allRecordStats.failed,
                resultUnknown = allRecordStats.resultUnknown,
                skipped = allRecordStats.skipped,
            ),
        )

        for (filter in RecFilter.entries) {
            if (filter == RecFilter.ALL) continue
            val filtered = buckets.getValue(filter)
            if (filtered.isNotEmpty()) {
                val results = filtered.toList()
                val stats = bucketStats.getValue(filter).toRecordStats()
                groupedByFilter.getValue(filter).add(
                    RecordRunGroup(
                        id = "${baseId}_${filter.name}",
                        run = run,
                        formattedTime = formattedTime,
                        results = results,
                        succeeded = stats.succeeded,
                        alreadySigned = stats.alreadySigned,
                        failed = stats.failed,
                        resultUnknown = stats.resultUnknown,
                        skipped = stats.skipped,
                    ),
                )
            }
        }
    }

    return RecordsCache(
        allResults = allResults,
        counts = counts,
        filteredRuns = groupedByFilter.mapValues { it.value.toList() },
    )
}

private data class RecordStats(
    val succeeded: Int,
    val alreadySigned: Int,
    val failed: Int,
    val resultUnknown: Int,
    val skipped: Int,
)

private class MutableRecordStats {
    private var succeeded = 0
    private var alreadySigned = 0
    private var failed = 0
    private var resultUnknown = 0
    private var skipped = 0

    fun add(result: TaskResult) {
        when (recordPrimaryFilter(result)) {
            RecFilter.SUCCESS -> succeeded++
            RecFilter.ALREADY -> alreadySigned++
            RecFilter.FAILED -> failed++
            RecFilter.RESULT_UNKNOWN -> resultUnknown++
            RecFilter.SKIPPED -> skipped++
            RecFilter.ALL -> Unit
        }
    }

    fun toRecordStats(): RecordStats =
        RecordStats(
            succeeded = succeeded,
            alreadySigned = alreadySigned,
            failed = failed,
            resultUnknown = resultUnknown,
            skipped = skipped,
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

private fun countRecordStats(results: List<TaskResult>): RecordStats {
    val stats = MutableRecordStats()
    for (result in results) stats.add(result)
    return stats.toRecordStats()
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
