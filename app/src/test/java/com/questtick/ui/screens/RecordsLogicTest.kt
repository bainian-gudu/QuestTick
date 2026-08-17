package com.questtick.ui.screens

import com.questtick.data.FailureCategory
import com.questtick.data.RunRecord
import com.questtick.data.TaskResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordsLogicTest {
    @Test
    fun matchesRecordFilterClassifiesNormalStates() {
        val success = result("success", success = true)
        val already = result("already", success = true, alreadySigned = true)
        val failed = result("failed", success = false)
        val skipped = result("skipped", success = false, skipped = true)
        val unknown =
            result("unknown", success = false, skipped = true).copy(
                failureCategory = FailureCategory.RESULT_UNKNOWN,
            )

        assertTrue(matchesRecordFilter(success, RecFilter.ALL))
        assertTrue(matchesRecordFilter(success, RecFilter.SUCCESS))
        assertFalse(matchesRecordFilter(success, RecFilter.ALREADY))
        assertFalse(matchesRecordFilter(success, RecFilter.FAILED))
        assertFalse(matchesRecordFilter(success, RecFilter.SKIPPED))

        assertTrue(matchesRecordFilter(already, RecFilter.ALREADY))
        assertFalse(matchesRecordFilter(already, RecFilter.SUCCESS))
        assertFalse(matchesRecordFilter(already, RecFilter.FAILED))

        assertTrue(matchesRecordFilter(failed, RecFilter.FAILED))
        assertFalse(matchesRecordFilter(failed, RecFilter.SUCCESS))
        assertFalse(matchesRecordFilter(failed, RecFilter.SKIPPED))

        assertTrue(matchesRecordFilter(skipped, RecFilter.SKIPPED))
        assertFalse(matchesRecordFilter(skipped, RecFilter.FAILED))
        assertFalse(matchesRecordFilter(skipped, RecFilter.SUCCESS))

        assertTrue(matchesRecordFilter(unknown, RecFilter.RESULT_UNKNOWN))
        assertFalse(matchesRecordFilter(unknown, RecFilter.SKIPPED))
        assertFalse(matchesRecordFilter(unknown, RecFilter.FAILED))
    }

    @Test
    fun recordPrimaryFilterUsesDisplayStatusPrecedence() {
        assertEquals(RecFilter.SKIPPED, recordPrimaryFilter(result("skip", success = true, skipped = true)))
        assertEquals(RecFilter.ALREADY, recordPrimaryFilter(result("already", success = true, alreadySigned = true)))
        assertEquals(RecFilter.SUCCESS, recordPrimaryFilter(result("success", success = true)))
        assertEquals(RecFilter.FAILED, recordPrimaryFilter(result("failed", success = false)))
    }

    @Test
    fun buildRecordsCacheCountsAllFilters() {
        val successA = result("successA", success = true)
        val already = result("already", success = true, alreadySigned = true)
        val failed = result("failed", success = false)
        val skipped = result("skipped", success = false, skipped = true)
        val successB = result("successB", success = true)
        val history =
            listOf(
                RunRecord(timestamp = 1000L, results = listOf(successA, already, failed)),
                RunRecord(timestamp = 2000L, results = listOf(skipped, successB)),
            )

        val cache = buildRecordsCache(history)

        assertEquals(listOf(successA, already, failed, skipped, successB), cache.allResults)
        assertEquals(5, cache.count(RecFilter.ALL))
        assertEquals(2, cache.count(RecFilter.SUCCESS))
        assertEquals(1, cache.count(RecFilter.ALREADY))
        assertEquals(1, cache.count(RecFilter.FAILED))
        assertEquals(1, cache.count(RecFilter.SKIPPED))
    }

    @Test
    fun buildRecordsCacheFiltersRunsAndDropsEmptyRuns() {
        val successA = result("successA", success = true)
        val already = result("already", success = true, alreadySigned = true)
        val failed = result("failed", success = false)
        val skipped = result("skipped", success = false, skipped = true)
        val successB = result("successB", success = true)
        val firstRun = RunRecord(timestamp = 1000L, results = listOf(successA, already, failed))
        val secondRun = RunRecord(timestamp = 2000L, results = listOf(skipped, successB))

        val cache = buildRecordsCache(listOf(firstRun, secondRun))

        assertEquals(listOf(firstRun.results, secondRun.results), cache.runs(RecFilter.ALL).map { it.results })
        assertEquals(listOf(listOf(successA), listOf(successB)), cache.runs(RecFilter.SUCCESS).map { it.results })
        assertEquals(listOf(listOf(already)), cache.runs(RecFilter.ALREADY).map { it.results })
        assertEquals(listOf(listOf(failed)), cache.runs(RecFilter.FAILED).map { it.results })
        assertEquals(listOf(listOf(skipped)), cache.runs(RecFilter.SKIPPED).map { it.results })
        assertEquals("1000", cache.runs(RecFilter.ALL).first().formattedTime)
        assertEquals(1, cache.runs(RecFilter.ALL).first().succeeded)
        assertEquals(1, cache.runs(RecFilter.ALL).first().alreadySigned)
        assertEquals(1, cache.runs(RecFilter.ALL).first().failed)
    }

    @Test
    fun buildRecordsCacheReturnsEmptyCacheForEmptyHistory() {
        val cache = buildRecordsCache(emptyList())

        assertTrue(cache.allResults.isEmpty())
        assertTrue(cache.counts.isEmpty())
        assertTrue(cache.filteredRuns.isEmpty())
    }

    @Test
    fun recordStableKeysStayStableForSameContent() {
        val first = result("first", success = true)
        val second = result("second", success = false)
        val run = RunRecord(timestamp = 1234L, results = listOf(first, second))

        assertEquals(recordRunStableId(run), recordRunStableId(run.copy()))
        assertEquals(recordResultStableKey(first, 0), recordResultStableKey(first.copy(), 0))
    }

    @Test
    fun recFilterLabelsStayStableForUi() {
        assertSame(RecFilter.ALL, RecFilter.entries.first())
        assertEquals(listOf("全部", "成功", "失败", "已签到", "待确认", "跳过"), RecFilter.entries.map { it.label })
    }

    private fun RecordsCache.count(filter: RecFilter): Int = counts.getValue(filter)

    private fun RecordsCache.runs(filter: RecFilter): List<RecordRunGroup> =
        filteredRuns.getValue(filter)

    private fun result(
        label: String,
        success: Boolean,
        skipped: Boolean = false,
        alreadySigned: Boolean = false,
    ): TaskResult =
        TaskResult(
            game = "原神",
            gameKey = "Genshin",
            accountLabel = label,
            success = success,
            skipped = skipped,
            alreadySigned = alreadySigned,
            message = label,
        )
}
