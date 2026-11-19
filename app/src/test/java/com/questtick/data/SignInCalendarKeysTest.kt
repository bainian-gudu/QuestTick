package com.questtick.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.util.TimeZone

class SignInCalendarKeysTest {
    @Test
    fun `business day follows current device timezone`() {
        val original = TimeZone.getDefault()
        try {
            val timestamp = Instant.parse("2026-07-09T16:30:00Z").toEpochMilli()
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
            assertEquals("20260709", dayKey(timestamp))

            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
            assertEquals("20260710", dayKey(timestamp))
        } finally {
            TimeZone.setDefault(original)
        }
    }
}
