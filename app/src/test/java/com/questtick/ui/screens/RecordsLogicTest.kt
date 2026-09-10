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
    fun buildRecordsIndexCountsAllFilters() {
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

        val index = buildRecordsIndex(history)

        assertEquals(5, index.allResultCount)
        assertEquals(5, index.counts.getValue(RecFilter.ALL))
        assertEquals(2, index.counts.getValue(RecFilter.SUCCESS))
        assertEquals(1, index.counts.getValue(RecFilter.ALREADY))
        assertEquals(1, index.counts.getValue(RecFilter.FAILED))
        assertEquals(1, index.counts.getValue(RecFilter.SKIPPED))
    }

    @Test
    fun buildRecordsIndexKeepsOnlyMatchingRunSummaries() {
        val successA = result("successA", success = true)
        val already = result("already", success = true, alreadySigned = true)
        val failed = result("failed", success = false)
        val skipped = result("skipped", success = false, skipped = true)
        val successB = result("successB", success = true)
        val firstRun = RunRecord(timestamp = 1000L, results = listOf(successA, already, failed))
        val secondRun = RunRecord(timestamp = 2000L, results = listOf(skipped, successB))

        val index = buildRecordsIndex(listOf(firstRun, secondRun))

        assertEquals(listOf(0, 1), index.runsByFilter.getValue(RecFilter.ALL).map { it.runIndex })
        assertEquals(listOf(0, 1), index.runsByFilter.getValue(RecFilter.SUCCESS).map { it.runIndex })
        assertEquals(listOf(0), index.runsByFilter.getValue(RecFilter.ALREADY).map { it.runIndex })
        assertEquals(listOf(0), index.runsByFilter.getValue(RecFilter.FAILED).map { it.runIndex })
        assertEquals(listOf(1), index.runsByFilter.getValue(RecFilter.SKIPPED).map { it.runIndex })
        assertEquals(
            1,
            index.runsByFilter
                .getValue(RecFilter.ALL)
                .first()
                .succeeded,
        )
        assertEquals(
            1,
            index.runsByFilter
                .getValue(RecFilter.ALL)
                .first()
                .alreadySigned,
        )
        assertEquals(
            1,
            index.runsByFilter
                .getValue(RecFilter.ALL)
                .first()
                .failed,
        )
    }

    @Test
    fun buildRecordsIndexReturnsEmptyIndexForEmptyHistory() {
        val index = buildRecordsIndex(emptyList())

        assertEquals(0, index.allResultCount)
        assertTrue(index.counts.isEmpty())
        assertTrue(index.runsByFilter.isEmpty())
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
