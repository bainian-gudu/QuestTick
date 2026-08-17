package com.questtick.ui.screens

import com.questtick.sign.RunProgressPhase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeSignInButtonStateTest {
    @Test
    fun `completed button expires after three seconds`() {
        val finishedAt = 10_000L

        assertTrue(shouldShowSignInCompleted(RunProgressPhase.FINISHED, finishedAt, finishedAt))
        assertTrue(shouldShowSignInCompleted(RunProgressPhase.FINISHED, finishedAt, finishedAt + 2_999L))
        assertFalse(shouldShowSignInCompleted(RunProgressPhase.FINISHED, finishedAt, finishedAt + 3_000L))
        assertFalse(shouldShowSignInCompleted(RunProgressPhase.FAILED, finishedAt, finishedAt + 1_000L))
    }
}
