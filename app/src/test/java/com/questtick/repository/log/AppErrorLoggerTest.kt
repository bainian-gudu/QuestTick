package com.questtick.repository.log

import com.questtick.data.LogEntry
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppErrorLoggerTest {
    @Test
    fun `record appends original error details with feature context`() {
        val repository = mockk<LogRepository>(relaxed = true)
        every { repository.append(any()) } returns Unit
        val entry = slot<LogEntry>()
        val logger = AppErrorLogger(repository)
        val cause = IllegalStateException("remote response body")
        val error = IllegalArgumentException("request failed", cause)

        logger.record("版本获取", error)

        verify(exactly = 1) { repository.append(capture(entry)) }
        assertEquals("ERROR", entry.captured.level)
        assertTrue(entry.captured.message.contains("[版本获取]"))
        assertTrue(entry.captured.message.contains("request failed"))
        assertTrue(entry.captured.detail.contains("IllegalArgumentException"))
        assertTrue(entry.captured.detail.contains("request failed"))
        assertTrue(entry.captured.detail.contains("IllegalStateException"))
        assertTrue(entry.captured.detail.contains("remote response body"))
    }
}
