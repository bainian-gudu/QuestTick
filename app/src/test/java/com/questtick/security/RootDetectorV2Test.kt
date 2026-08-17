package com.questtick.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootDetectorV2Test {
    @Test
    fun rootEvidenceTriggersBlockDirectly() {
        assertTrue(RootDetectorV2.isRootEvidenceTrigger("root_management_apps_rb"))
        assertTrue(RootDetectorV2.isRootEvidenceTrigger("root_native"))
        assertTrue(RootDetectorV2.isRootEvidenceTrigger("su_binary_rb"))
        assertTrue(RootDetectorV2.isRootEvidenceTrigger("magisk_binary_rb"))
        assertTrue(RootDetectorV2.isRootEvidenceTrigger("su_binary_fs"))
        assertTrue(RootDetectorV2.isRootEvidenceTrigger("which_su"))
        assertTrue(RootDetectorV2.isRootEvidenceTrigger("su_exec_uid0"))
        assertTrue(RootDetectorV2.isRootEvidenceTrigger("rw_paths"))
        assertTrue(RootDetectorV2.isRootEvidenceTrigger("writable_system"))
        assertTrue(RootDetectorV2.isRootEvidenceTrigger("root_apps:com.topjohnwu.magisk"))
        assertTrue(RootDetectorV2.isRootEvidenceTrigger("magisk_files:/data/adb/magisk"))
    }

    @Test
    fun emulatorAndWeakRiskTriggersDoNotBlockAsRootEvidence() {
        assertFalse(RootDetectorV2.isRootEvidenceTrigger("RootBeer.isRooted"))
        assertFalse(RootDetectorV2.isRootEvidenceTrigger("emulator"))
        assertFalse(RootDetectorV2.isRootEvidenceTrigger("test_keys"))
        assertFalse(RootDetectorV2.isRootEvidenceTrigger("dangerous_props"))
        assertFalse(RootDetectorV2.isRootEvidenceTrigger("dangerous_system_props"))
        assertFalse(RootDetectorV2.isRootEvidenceTrigger("potentially_dangerous_apps_rb"))
        assertFalse(RootDetectorV2.isRootEvidenceTrigger("risk_apps:moe.shizuku.privileged.api"))
        assertFalse(RootDetectorV2.isRootEvidenceTrigger("busybox"))
        assertFalse(RootDetectorV2.isRootEvidenceTrigger("selinux_permissive"))
    }

    @Test
    fun explicitRootEvidenceAlwaysBlocks() {
        val result = safeResult().copy(
            isRooted = true,
            rootEvidenceTriggers = listOf("su_binary_fs"),
        )

        assertEquals(RootBlockingPolicy.Decision.BLOCK_ROOT_EVIDENCE, RootBlockingPolicy.decide(result))
    }

    @Test
    fun partialProbeAvailabilityDoesNotPretendRootOrBlock() {
        val result = safeResult().copy(
            completeness = RootDetectorV2.CheckCompleteness.PARTIAL,
            checkedProbeCount = RootDetectorV2.TOTAL_PROBE_GROUPS - 1,
            unavailableProbes = listOf("rootbeer"),
        )

        assertEquals(RootBlockingPolicy.Decision.ALLOW, RootBlockingPolicy.decide(result))
    }

    @Test
    fun failedRootCheckBlocksWithoutPretendingRootWasDetected() {
        val failed = safeResult().copy(
            completeness = RootDetectorV2.CheckCompleteness.FAILED,
            checkedProbeCount = 0,
            unavailableProbes = listOf("rootbeer", "filesystem", "command"),
        )

        assertFalse(failed.isRooted)
        assertEquals(RootBlockingPolicy.Decision.BLOCK_CHECK_FAILED, RootBlockingPolicy.decide(failed))
        assertEquals(RootBlockingPolicy.Decision.BLOCK_CHECK_FAILED, RootBlockingPolicy.decide(null))
    }

    private fun safeResult() =
        RootDetectorV2.RootCheckResult(
            isRooted = false,
            score = 0,
            triggers = emptyList(),
            level = RootDetectorV2.RiskLevel.SAFE,
        )
}
