package com.questtick.work

import java.time.Instant
import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SchedulerTest {
    @Test
    fun computeScheduleTargetUsesSameDayTargetWhenTargetIsFuture() {
        val now = millisOf(hour = 7, minute = 30)

        val target = computeScheduleTargetMillis(hour = 8, minute = 10, nowMillis = now)

        assertEquals(now + 40 * 60_000L, target)
    }

    @Test
    fun computeScheduleTargetRollsToNextDayWhenTargetAlreadyPassed() {
        val now = millisOf(hour = 8, minute = 30)

        val target = computeScheduleTargetMillis(hour = 8, minute = 10, nowMillis = now)

        assertEquals(now + 24 * 60 * 60_000L - 20 * 60_000L, target)
    }

    @Test
    fun computeScheduleTargetCoercesInvalidTimeToValidRange() {
        val now = millisOf(hour = 22, minute = 50)

        // 99 点 / -5 分会被夹到 23:00，因此目标仍在当天。
        val target = computeScheduleTargetMillis(hour = 99, minute = -5, nowMillis = now)

        assertEquals(now + 10 * 60_000L, target)
    }

    @Test
    fun computeScheduleTargetUsesProvidedDeviceTimezone() {
        val now = Instant.parse("2026-07-08T06:00:00Z").toEpochMilli()

        // 设备在东京（UTC+9）：当地 15:00，目标 08:00 落在次日，即 17 小时后。
        val target =
            computeScheduleTargetMillis(
                hour = 8,
                minute = 0,
                nowMillis = now,
                timeZone = TimeZone.getTimeZone("Asia/Tokyo"),
            )

        assertEquals(now + 17 * 60 * 60_000L, target)
    }

    @Test
    fun computeScheduleTargetPreservesWallClockAcrossDstStart() {
        // 纽约 2026-03-08 01:30 EST，08:00 已是 EDT：墙上时间不变，实际只隔 5.5 小时。
        val now = Instant.parse("2026-03-08T06:30:00Z").toEpochMilli()

        val target =
            computeScheduleTargetMillis(
                hour = 8,
                minute = 0,
                nowMillis = now,
                timeZone = TimeZone.getTimeZone("America/New_York"),
            )

        assertEquals(now + 5 * 60 * 60_000L + 30 * 60_000L, target)
    }

    @Test
    fun resolveNextTargetKeepsDailyCadenceAfterLateRun() {
        val zone = TimeZone.getTimeZone("Asia/Shanghai")
        // 计划 11-14 05:00(+08:00)，实际 11-14 08:00 才执行；下一次仍应是 24 小时后的 05:00。
        val previous = Instant.parse("2026-11-13T21:00:00Z").toEpochMilli()
        val now = Instant.parse("2026-11-14T00:00:00Z").toEpochMilli()

        val next = resolveNextTargetMillis(5, 0, now, zone, previous)

        assertEquals(Instant.parse("2026-11-14T21:00:00Z").toEpochMilli(), next)
    }

    @Test
    fun resolveNextTargetReanchorsWhenAnchorIsCorrupt() {
        val zone = TimeZone.getTimeZone("Asia/Shanghai")
        // 锚点比现在晚 40 小时：继续加一天会把触发时刻飘到后天之后，必须按当前时间重算。
        val now = Instant.parse("2026-11-14T21:30:00Z").toEpochMilli()
        val corruptAnchor = now + 40 * 60 * 60_000L

        val next = resolveNextTargetMillis(5, 0, now, zone, corruptAnchor)

        assertEquals(Instant.parse("2026-11-15T21:00:00Z").toEpochMilli(), next)
    }

    @Test
    fun resolveNextTargetSkipsElapsedDays() {
        val zone = TimeZone.getTimeZone("Asia/Shanghai")
        // 锚点在 4 天前（例如退避重试多日未执行），下一次必须落在未来，而不是补跑已过去的日期。
        val previous = Instant.parse("2026-11-09T21:00:00Z").toEpochMilli()
        val now = Instant.parse("2026-11-14T21:30:00Z").toEpochMilli()

        val next = resolveNextTargetMillis(5, 0, now, zone, previous)

        assertEquals(Instant.parse("2026-11-15T21:00:00Z").toEpochMilli(), next)
    }

    @Test
    fun reconcileRebuildsScheduleOnlyWhenProbeSucceeded() {
        assertTrue(shouldRebuildSchedule(metadataMatches = true, hasActiveWork = false, probeFailed = false))
        assertTrue(shouldRebuildSchedule(metadataMatches = false, hasActiveWork = true, probeFailed = false))
        assertFalse(shouldRebuildSchedule(metadataMatches = true, hasActiveWork = true, probeFailed = false))
    }

    @Test
    fun reconcileKeepsScheduleWhenProbeFails() {
        // 查询失败不能当成「没有活动任务」，否则会取消正在退避重试的工作。
        assertFalse(shouldRebuildSchedule(metadataMatches = true, hasActiveWork = false, probeFailed = true))
        assertFalse(shouldRebuildSchedule(metadataMatches = false, hasActiveWork = false, probeFailed = true))
    }

    private fun millisOf(
        hour: Int,
        minute: Int,
        second: Int = 0,
    ): Long =
        Calendar
            .getInstance()
            .apply {
                clear()
                set(2026, Calendar.JULY, 8, hour, minute, second)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
}
