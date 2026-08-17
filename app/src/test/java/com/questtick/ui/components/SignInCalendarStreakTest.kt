package com.questtick.ui.components

import com.questtick.data.dayKey
import java.time.Instant
import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

class SignInCalendarStreakTest {
    @Test
    fun `streak ending yesterday remains visible before today's run`() {
        val zone = TimeZone.getTimeZone("Asia/Shanghai")
        val now = Instant.parse("2026-08-17T01:00:00Z").toEpochMilli()
        val signed = keysEndingAt(now, zone, days = 12, includeToday = false)

        assertEquals(12, computeSignInStreak(now, signed, zone))
    }

    @Test
    fun `a gap resets the current streak`() {
        val zone = TimeZone.getTimeZone("Asia/Shanghai")
        val now = Instant.parse("2026-08-17T04:00:00Z").toEpochMilli()
        val twoDaysAgo = Calendar.getInstance(zone).apply {
            timeInMillis = now
            add(Calendar.DAY_OF_MONTH, -2)
        }

        assertEquals(
            0,
            computeSignInStreak(
                now,
                setOf(dayKey(twoDaysAgo.timeInMillis, zone)),
                zone,
            ),
        )
    }

    private fun keysEndingAt(
        now: Long,
        zone: TimeZone,
        days: Int,
        includeToday: Boolean,
    ): Set<String> {
        val calendar = Calendar.getInstance(zone).apply {
            timeInMillis = now
            if (!includeToday) add(Calendar.DAY_OF_MONTH, -1)
        }
        return buildSet {
            repeat(days) {
                add(dayKey(calendar.timeInMillis, zone))
                calendar.add(Calendar.DAY_OF_MONTH, -1)
            }
        }
    }
}
