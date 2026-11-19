package com.questtick.ui.components.home

import com.questtick.data.Account
import com.questtick.data.RunRecord
import com.questtick.data.TaskResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeSummaryLogicTest {
    @Test
    fun latestRunOrNullReturnsNewestFirstRecord() {
        val newest = RunRecord(timestamp = 2L, results = emptyList())
        val older = RunRecord(timestamp = 1L, results = emptyList())

        assertSame(newest, latestRunOrNull(listOf(newest, older)))
        assertNull(latestRunOrNull(emptyList()))
    }

    @Test
    fun buildHomeDashboardStatsCountsEnabledAccountsAndLatestRunOnly() {
        val accounts =
            listOf(
                Account(id = "1", label = "A", enabled = true),
                Account(id = "2", label = "B", enabled = false),
                Account(id = "3", label = "C", enabled = true),
            )
        val latest =
            RunRecord(
                timestamp = 2L,
                results =
                    listOf(
                        result("success", success = true),
                        result("already", success = true, alreadySigned = true),
                        result("failed", success = false),
                        result("skipped", success = false, skipped = true),
                    ),
            )
        val older = RunRecord(timestamp = 1L, results = listOf(result("old", success = false)))

        val stats = buildHomeDashboardStats(accounts, listOf(latest, older))

        assertEquals(
            HomeDashboardStats(
                enabledAccounts = 2,
                totalAccounts = 3,
                success = 1,
                alreadySigned = 1,
                failed = 1,
            ),
            stats,
        )
    }

    @Test
    fun buildHomeDashboardStatsUsesZeroCountsWhenHistoryIsEmpty() {
        val stats = buildHomeDashboardStats(listOf(Account(id = "1", label = "A")), emptyList())

        assertEquals(
            HomeDashboardStats(
                enabledAccounts = 1,
                totalAccounts = 1,
                success = 0,
                alreadySigned = 0,
                failed = 0,
            ),
            stats,
        )
    }

    @Test
    fun buildLatestRunSummaryLimitsVisibleResultsAndCountsRemaining() {
        val results = (1..8).map { result("result$it", success = it % 2 == 0) }
        val record = RunRecord(timestamp = 1L, results = results)

        val summary = buildLatestRunSummary(record, visibleResultLimit = 6)

        assertEquals(8, summary.totalCount)
        assertEquals(results.take(6), summary.topResults)
        assertTrue(summary.hasMore)
        assertEquals(2, summary.remainingCount)
    }

    @Test
    fun buildLatestRunSummaryHandlesEmptyAndZeroLimit() {
        val emptySummary = buildLatestRunSummary(RunRecord(timestamp = 1L, results = emptyList()))

        assertEquals(0, emptySummary.totalCount)
        assertTrue(emptySummary.topResults.isEmpty())
        assertFalse(emptySummary.hasMore)
        assertEquals(0, emptySummary.remainingCount)

        val record = RunRecord(timestamp = 1L, results = listOf(result("only", success = true)))
        val zeroLimitSummary = buildLatestRunSummary(record, visibleResultLimit = 0)

        assertTrue(zeroLimitSummary.topResults.isEmpty())
        assertTrue(zeroLimitSummary.hasMore)
        assertEquals(1, zeroLimitSummary.remainingCount)
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
