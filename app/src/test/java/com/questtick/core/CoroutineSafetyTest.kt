package com.questtick.core

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class CoroutineSafetyTest {
    @Test
    fun `runCatchingCancellable never wraps cancellation`() {
        try {
            runCatchingCancellable<String> { throw CancellationException("cancel") }
            fail("CancellationException should be rethrown")
        } catch (_: CancellationException) {
            // expected
        }
    }

    @Test
    fun `runCatchingCancellable keeps ordinary failures in Result`() {
        val result = runCatchingCancellable<String> { error("failure") }

        assertEquals("failure", result.exceptionOrNull()?.message)
    }
}
