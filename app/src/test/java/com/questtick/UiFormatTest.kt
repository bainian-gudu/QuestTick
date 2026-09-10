package com.questtick

import org.junit.Assert.assertEquals
import org.junit.Test

class UiFormatTest {
    @Test
    fun `format readable bytes handles negative and small values`() {
        assertEquals("0 B", formatReadableBytes(-1))
        assertEquals("0 B", formatReadableBytes(0))
        assertEquals("512 B", formatReadableBytes(512))
    }

    @Test
    fun `format readable bytes scales to kb mb and gb`() {
        assertEquals("1.0 KB", formatReadableBytes(1024))
        assertEquals("1.5 KB", formatReadableBytes(1536))
        assertEquals("2.0 MB", formatReadableBytes(2L * 1024 * 1024))
        assertEquals("3.0 GB", formatReadableBytes(3L * 1024 * 1024 * 1024))
    }
}
