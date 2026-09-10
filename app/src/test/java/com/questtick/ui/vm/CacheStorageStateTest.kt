package com.questtick.ui.vm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CacheStorageStateTest {
    @Test
    fun `total size updates with scanned items`() {
        val state =
            CacheStorageState(
                items =
                    listOf(
                        CacheStorageItem(CacheCategory.REWARD_IMAGES, 128L),
                        CacheStorageItem(CacheCategory.NETWORK_RESPONSES, 256L),
                    ),
                loading = false,
            )

        assertEquals(384L, state.totalBytes)
        assertFalse(state.busy)
    }

    @Test
    fun `running and completed feedback both block duplicate cache actions`() {
        assertTrue(CacheStorageState(loading = false, scanCompleted = true).busy)
        assertTrue(
            CacheStorageState(
                loading = false,
                clearedCategory = CacheCategory.APP_UPDATES,
            ).busy,
        )
        assertTrue(CacheStorageState(loading = false, clearAllCompleted = true).busy)
    }
}
