package com.questtick.sign

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DsTest {
    @Test
    fun deterministicSignatureMatchesExpectedMd5() {
        val ds = Ds.generate(timestampSeconds = 1_700_000_000L, random = "abc123")

        assertEquals("1700000000,abc123,6e8f99d9056c2e8b01e661dce48f8bc7", ds)
    }

    @Test
    fun boundaryRandomLowerAndUpperValuesGenerate() {
        assertEquals("1,000000,91106f51d1dd9b2d732b8cedbfaaa44e", Ds.generate(1L, "000000"))
        assertEquals("1700000000,zzzzzz,f491efde9746424d3d693771a4e2323d", Ds.generate(1_700_000_000L, "zzzzzz"))
    }

    @Test
    fun generatedSignatureHasExpectedFormat() {
        val parts = Ds.generate().split(",")

        assertEquals(3, parts.size)
        assertTrue(parts[0].toLong() > 0)
        assertEquals(6, parts[1].length)
        assertTrue(parts[1].all { it in '0'..'9' || it in 'a'..'z' })
        assertEquals(32, parts[2].length)
        assertTrue(parts[2].all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test(expected = IllegalArgumentException::class)
    fun zeroTimestampRejected() {
        Ds.generate(timestampSeconds = 0L, random = "abc123")
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeTimestampRejected() {
        Ds.generate(timestampSeconds = -1L, random = "abc123")
    }

    @Test(expected = IllegalArgumentException::class)
    fun shortRandomRejected() {
        Ds.generate(timestampSeconds = 1_700_000_000L, random = "abc12")
    }

    @Test(expected = IllegalArgumentException::class)
    fun longRandomRejected() {
        Ds.generate(timestampSeconds = 1_700_000_000L, random = "abc1234")
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidRandomCharactersRejected() {
        Ds.generate(timestampSeconds = 1_700_000_000L, random = "ABC123")
    }
}
