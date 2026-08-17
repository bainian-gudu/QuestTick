package com.questtick.work

import java.time.Instant
import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

class SchedulerTest {
    @Test
    fun selectScheduleTimeAveragesTwoSourcesWithinFiveMinutes() {
        val system = 1_000_000L
        assertEquals(
            1_120_000L,
            selectScheduleTime(ScheduleTimeSources(system, 1_240_000L, 2_000_000L)),
        )
    }

    @Test
    fun selectScheduleTimeUsesNetworkWhenNoPairAgrees() {
        assertEquals(
            2_000_000L,
            selectScheduleTime(ScheduleTimeSources(1_000_000L, 2_000_000L, 3_000_000L)),
        )
    }

    @Test
    fun selectScheduleTimeUsesSystemWhenNetworkUnavailable() {
        assertEquals(
            1_000_000L,
            selectScheduleTime(ScheduleTimeSources(1_000_000L, null, null)),
        )
    }

    @Test
    fun computeScheduleInitialDelayUsesSameDayTargetWhenTargetIsFuture() {
        val now = millisOf(hour = 7, minute = 30)

        val delay =
            computeScheduleInitialDelayMillis(
                hour = 8,
                minute = 10,
                nowMillis = now,
                jitterMillis = 12_000L,
            )

        assertEquals(40 * 60_000L + 12_000L, delay)
    }

    @Test
    fun computeScheduleInitialDelayRollsToNextDayWhenTargetAlreadyPassed() {
        val now = millisOf(hour = 8, minute = 30)

        val delay =
            computeScheduleInitialDelayMillis(
                hour = 8,
                minute = 10,
                nowMillis = now,
                jitterMillis = 0L,
            )

        assertEquals((23 * 60 + 40) * 60_000L, delay)
    }

    @Test
    fun computeScheduleInitialDelayKeepsAtLeastOneMinuteWhenTargetIsVeryClose() {
        val now = millisOf(hour = 7, minute = 59, second = 30)

        val delay =
            computeScheduleInitialDelayMillis(
                hour = 8,
                minute = 0,
                nowMillis = now,
                jitterMillis = 0L,
            )

        assertEquals(60_000L, delay)
    }

    @Test
    fun computeScheduleInitialDelayCoercesInvalidTimeToValidRange() {
        val now = millisOf(hour = 22, minute = 50)

        val delay =
            computeScheduleInitialDelayMillis(
                hour = 99,
                minute = -5,
                nowMillis = now,
                jitterMillis = 0L,
            )

        assertEquals(10 * 60_000L, delay)
    }

    @Test
    fun computeScheduleInitialDelayCoercesJitterToConfiguredRange() {
        val now = millisOf(hour = 7, minute = 30)

        val delay =
            computeScheduleInitialDelayMillis(
                hour = 8,
                minute = 0,
                nowMillis = now,
                jitterMillis = Long.MAX_VALUE,
            )

        assertEquals(60 * 60_000L, delay)
    }

    @Test
    fun computeScheduleInitialDelayUsesProvidedDeviceTimezone() {
        val now = Instant.parse("2026-07-08T06:00:00Z").toEpochMilli()
        val delay = computeScheduleInitialDelayMillis(
            hour = 8,
            minute = 0,
            nowMillis = now,
            timeZone = TimeZone.getTimeZone("Asia/Tokyo"),
        )

        assertEquals(17 * 60 * 60_000L, delay)
    }

    @Test
    fun computeScheduleInitialDelayPreservesWallClockAcrossDstStart() {
        val zone = TimeZone.getTimeZone("America/New_York")
        val now = Instant.parse("2026-03-08T06:30:00Z").toEpochMilli()
        val delay = computeScheduleInitialDelayMillis(
            hour = 8,
            minute = 0,
            nowMillis = now,
            timeZone = zone,
        )

        assertEquals(5 * 60 * 60_000L + 30 * 60_000L, delay)
    }

    private fun millisOf(
        hour: Int,
        minute: Int,
        second: Int = 0,
    ): Long =
        Calendar.getInstance().apply {
            clear()
            set(2026, Calendar.JULY, 8, hour, minute, second)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
}
