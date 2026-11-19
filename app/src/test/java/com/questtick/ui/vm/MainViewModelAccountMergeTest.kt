package com.questtick.ui.vm

import com.questtick.data.Account
import org.junit.Assert.assertEquals
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

    @Test
    fun editableMergePersistsQrCredentialsOnlyWhenDraftIsSaved() {
        val latest = Account(id = "account", label = "account")
        val edited =
            latest.copy(
                mysCookie = "account_id=10001; cookie_token=token",
                mysUid = "10001",
                stoken = "stoken",
                stmid = "stmid",
                ltoken = "ltoken",
                qrLoginBound = true,
                genshinToken = "ai=combo",
                genshinWebCookie = "web-cookie",
                genshinCloudQrLoginBound = true,
            )

        val merged = mergeEditableAccount(latest, edited)

        assertTrue(merged.qrLoginBound)
        assertTrue(merged.hasMysKeepLogin)
        assertEquals("stoken", merged.stoken)
        assertEquals("web-cookie", merged.genshinWebCookie)
        assertTrue(merged.hasGenshinCloudKeepLogin)
    }
}
