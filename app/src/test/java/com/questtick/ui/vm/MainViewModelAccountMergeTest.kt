package com.questtick.ui.vm

import com.questtick.data.Account
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainViewModelAccountMergeTest {
    @Test
    fun editableMergePersistsMysCoinSetting() {
        val latest = Account(id = "account", label = "account", mysCoinEnabled = false)

        val enabled = mergeEditableAccount(latest, latest.copy(mysCoinEnabled = true))
        val disabled = mergeEditableAccount(enabled, enabled.copy(mysCoinEnabled = false))

        assertTrue(enabled.mysCoinEnabled)
        assertFalse(disabled.mysCoinEnabled)
    }
}
