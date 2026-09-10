package com.questtick.repository.log

import com.questtick.data.LogEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class LogRepositoryMergeTest {
    @Test
    fun mergeLogSnapshotsKeepsPersistedOrderAndAppendsLiveEntries() {
        val old = entry(1000, "历史日志")
        val start = entry(2000, "开始执行签到")
        val live = entry(3000, "运行中日志")

        val merged =
            mergeLogSnapshots(
                persisted = listOf(old),
                current = listOf(old, start, live),
            )

        assertEquals(listOf(old, start, live), merged)
    }

    @Test
    fun mergeLogSnapshotsDeduplicatesEntriesAlreadyPersistedAfterFinish() {
        val start = entry(1000, "开始执行签到")
        val end = entry(2000, "签到结束")

        val merged =
            mergeLogSnapshots(
                persisted = listOf(start, end),
                current = listOf(start, end),
            )

        assertEquals(listOf(start, end), merged)
    }

    @Test
    fun mergeLogSnapshotsKeepsLatestLimitWithoutReordering() {
        val first = entry(1000, "旧日志")
        val second = entry(2000, "开始执行签到")
        val third = entry(3000, "签到结束")

        val merged =
            mergeLogSnapshots(
                persisted = listOf(first, second),
                current = listOf(third),
                maxKeep = 2,
            )

        assertEquals(listOf(second, third), merged)
    }

    @Test
    fun mergeLogSnapshotsSortsOutOfOrderEntriesByTimestamp() {
        val older = entry(1000, "较早")
        val newer = entry(3000, "较晚")
        val middle = entry(2000, "中间")

        val merged =
            mergeLogSnapshots(
                persisted = listOf(newer),
                current = listOf(older, middle),
            )

        assertEquals(listOf(older, middle, newer), merged)
    }

    private fun entry(
        timestamp: Long,
        message: String,
        level: String = "INFO",
        detail: String = "",
    ): LogEntry = LogEntry(timestamp, level, message, detail)
}
