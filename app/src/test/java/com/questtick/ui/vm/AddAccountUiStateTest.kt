package com.questtick.ui.vm

import com.questtick.data.Account
import com.questtick.data.Games
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AddAccountUiStateTest {
    @Test
    fun hasFormChangesFromReturnsFalseForInitialAccount() {
        val account = account()
        val state = AddAccountUiState.from(account)

        assertFalse(state.hasFormChangesFrom(account))
    }

    @Test
    fun hasFormChangesFromIgnoresTransientUiState() {
        val account = account()
        val state = AddAccountUiState.from(account)

        assertFalse(
            state.copy(
                tab = 2,
                hydrated = true,
                saveError = "无法保存",
                cookieWarning = "Cookie 即将过期",
            ).hasFormChangesFrom(account),
        )
    }

    @Test
    fun hasFormChangesFromDetectsTextFieldChanges() {
        val account = account()
        val state = AddAccountUiState.from(account)

        assertTrue(state.copy(label = "新标签").hasFormChangesFrom(account))
        assertTrue(state.copy(cookie = "new_cookie=1").hasFormChangesFrom(account))
        assertTrue(state.copy(genshin = "new_genshin").hasFormChangesFrom(account))
        assertTrue(state.copy(starrail = "new_starrail").hasFormChangesFrom(account))
    }

    @Test
    fun hasFormChangesFromDetectsGameSelectionChanges() {
        val account = account(selectedGames = setOf("Genshin"))
        val state = AddAccountUiState.from(account)

        assertFalse(state.hasFormChangesFrom(account))
        assertTrue(state.copy(selectedGames = Games.ALL_KEYS).hasFormChangesFrom(account))
    }

    @Test
    fun newAccountPlaceholderLabelDoesNotCountAsChange() {
        val account = account(label = "新账号")
        val state = AddAccountUiState.from(account)

        assertFalse(state.hasFormChangesFrom(account))
        assertTrue(state.copy(label = "自定义账号").hasFormChangesFrom(account))
    }

    private fun account(
        label: String = "账号A",
        selectedGames: Set<String> = Games.ALL_KEYS,
    ): Account =
        Account(
            id = "1",
            label = label,
            mysCookie = "account_id=1; cookie_token=token",
            genshinToken = "genshin",
            starrailToken = "starrail",
            selectedGames = selectedGames,
        )
}
