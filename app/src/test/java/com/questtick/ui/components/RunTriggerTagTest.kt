package com.questtick.ui.components

import com.questtick.data.RunTrigger
import org.junit.Assert.assertEquals
import org.junit.Test

class RunTriggerTagTest {
    @Test
    fun manualRunsAreLabelledManual() {
        assertEquals("手动", runTriggerLabel(RunTrigger.MANUAL))
    }

    @Test
    fun scheduledRunsAreLabelledAutomatic() {
        assertEquals("自动", runTriggerLabel(RunTrigger.SCHEDULED))
    }

    @Test
    fun retriedRunsKeepAutomaticLabelWithRetrySuffix() {
        assertEquals("自动重试", runTriggerLabel(RunTrigger.RETRY))
    }
}
