package com.questtick.ui.vm

import com.questtick.data.Account
import com.questtick.data.Games
import com.questtick.repository.auth.AuthRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description

@OptIn(ExperimentalCoroutinesApi::class)
class AddAccountViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var authRepository: AuthRepository

    @Before
    fun setUp() {
        authRepository = mockk()
    }

    @Test
    fun cookieValidationIsDebouncedBeforeWarning() {
        val viewModel =
            createViewModel {
                AuthRepository.CredentialHealth(
                    state = AuthRepository.CredentialState.EXPIRING,
                    reason = "Cookie 可能不完整：缺少cookie_token",
                )
            }

        try {
            viewModel.updateCookie("account_id=10001")
            mainDispatcherRule.runCurrent()

            mainDispatcherRule.advanceTimeBy(349)
            mainDispatcherRule.runCurrent()
            assertNull(viewModel.uiState.value.cookieWarning)
            verify(exactly = 0) { authRepository.checkCookieHealth(any()) }

            mainDispatcherRule.advanceTimeBy(1)
            mainDispatcherRule.runCurrent()
            assertTrue(viewModel.uiState.value.cookieWarning.orEmpty().contains("缺少cookie_token"))
        } finally {
            viewModel.resetEditorState()
        }
    }

    @Test
    fun updatingCookieCancelsPreviousPendingValidation() {
        val checkedCookies = mutableListOf<String>()
        val invalidCookie = "account_id=10001"
        val validCookie = "account_id=10001; cookie_token=${"a".repeat(48)}"
        val viewModel =
            createViewModel { account ->
                checkedCookies.add(account.mysCookie)
                if (account.mysCookie == validCookie) {
                    AuthRepository.CredentialHealth(AuthRepository.CredentialState.VALID)
                } else {
                    AuthRepository.CredentialHealth(
                        state = AuthRepository.CredentialState.EXPIRING,
                        reason = "Cookie 可能不完整：缺少cookie_token",
                    )
                }
            }

        try {
            viewModel.updateCookie(invalidCookie)
            mainDispatcherRule.runCurrent()
            viewModel.updateCookie(validCookie)
            mainDispatcherRule.runCurrent()
            mainDispatcherRule.advanceTimeBy(350)
            mainDispatcherRule.runCurrent()

            assertEquals(validCookie, viewModel.uiState.value.cookie)
            assertNull(viewModel.uiState.value.cookieWarning)
            assertTrue(invalidCookie !in checkedCookies)
        } finally {
            viewModel.resetEditorState()
        }
    }

    @Test
    fun resetEditorStateCancelsPendingCookieValidation() {
        val viewModel =
            createViewModel {
                AuthRepository.CredentialHealth(
                    state = AuthRepository.CredentialState.EXPIRING,
                    reason = "Cookie 可能不完整：缺少cookie_token",
                )
            }

        viewModel.updateCookie("account_id=10001")
        mainDispatcherRule.runCurrent()
        viewModel.resetEditorState()
        mainDispatcherRule.advanceTimeBy(350)
        mainDispatcherRule.runCurrent()

        assertEquals("", viewModel.uiState.value.cookie)
        assertNull(viewModel.uiState.value.cookieWarning)
        verify(exactly = 0) { authRepository.checkCookieHealth(any()) }
    }

    @Test
    fun syncInitialKeepsTabWhenSameEditorSessionIsUpdatedByQrLogin() {
        val viewModel = createViewModel { AuthRepository.CredentialHealth(AuthRepository.CredentialState.VALID) }
        try {
            viewModel.syncInitial(account(id = "1", label = "账号A"))
            viewModel.setTab(2)

            viewModel.syncInitial(
                account(
                    id = "1",
                    label = "账号A",
                    mysCookie = "account_id=10001; cookie_token=${"a".repeat(48)}",
                    mysUid = "10001",
                    stoken = "stoken",
                    qrLoginBound = true,
                ),
            )

            val state = viewModel.uiState.value
            assertEquals(2, state.tab)
            assertEquals("account_id=10001; cookie_token=${"a".repeat(48)}", state.cookie)
            assertTrue(state.hydrated)
        } finally {
            viewModel.resetEditorState()
        }
    }

    @Test
    fun saveAccountRejectsEmptyCredentials() {
        val viewModel = createViewModel { AuthRepository.CredentialHealth(AuthRepository.CredentialState.VALID) }
        var saved: Account? = null

        viewModel.saveAccount { saved = it }
        mainDispatcherRule.runCurrent()

        assertNull(saved)
        assertEquals("请至少填写米游社 Cookie、云游戏 Token 或保留扫码登录凭证中的一项", viewModel.uiState.value.saveError)
    }

    @Test
    fun saveAccountRejectsEmptyGameSelection() {
        val viewModel = createViewModel { AuthRepository.CredentialHealth(AuthRepository.CredentialState.VALID) }
        var saved: Account? = null

        viewModel.updateGenshinToken("genshin-token")
        Games.ALL_KEYS.forEach(viewModel::toggleGame)
        viewModel.saveAccount { saved = it }
        mainDispatcherRule.runCurrent()

        assertNull(saved)
        assertEquals("请至少选择一个需要签到的游戏", viewModel.uiState.value.saveError)
    }

    @Test
    fun saveAccountTrimsFieldsDefaultsBlankLabelAndStoresAllGamesAsEmptySet() {
        val viewModel = createViewModel { AuthRepository.CredentialHealth(AuthRepository.CredentialState.VALID) }
        var saved: Account? = null

        try {
            viewModel.updateLabel("   ")
            viewModel.updateCookie("  account_id=10001; cookie_token=${"a".repeat(48)}  ")
            viewModel.updateGenshinToken("  genshin-token  ")
            viewModel.updateStarrailToken("  starrail-token  ")
            viewModel.saveAccount { saved = it }
            mainDispatcherRule.runCurrent()

            val account = requireNotNull(saved)
            assertEquals("未命名账号", account.label)
            assertEquals("account_id=10001; cookie_token=${"a".repeat(48)}", account.mysCookie)
            assertEquals("genshin-token", account.genshinToken)
            assertEquals("starrail-token", account.starrailToken)
            assertEquals(Games.ALL_KEYS, account.selectedGames)
            assertNull(viewModel.uiState.value.saveError)
        } finally {
            viewModel.resetEditorState()
        }
    }

    @Test
    fun saveAccountKeepsPartialGameSelectionAndCloudKeepLoginBindings() {
        val selectedGame = Games.ALL_KEYS.first()
        val viewModel = createViewModel { AuthRepository.CredentialHealth(AuthRepository.CredentialState.VALID) }
        var saved: Account? = null

        viewModel.syncInitial(
            account(
                id = "cloud",
                label = "云游戏账号",
                genshinWebCookie = "genshin_web_cookie",
                starrailWebCookie = "starrail_web_cookie",
                genshinCloudQrLoginBound = true,
                starrailCloudQrLoginBound = true,
            ),
        )
        Games.ALL_KEYS.filterNot { it == selectedGame }.forEach(viewModel::toggleGame)
        viewModel.updateGenshinToken("  genshin-token  ")
        viewModel.saveAccount { saved = it }
        mainDispatcherRule.runCurrent()

        val account = requireNotNull(saved)
        assertEquals(setOf(selectedGame), account.selectedGames)
        assertTrue(account.genshinCloudQrLoginBound)
        assertTrue(account.starrailCloudQrLoginBound)
        assertEquals("genshin-token", account.genshinToken)
    }

    @Test
    fun saveAccountAllowsBlankVisibleCredentialWhenMysKeepLoginExists() {
        val viewModel = createViewModel { AuthRepository.CredentialHealth(AuthRepository.CredentialState.VALID) }
        var saved: Account? = null

        viewModel.syncInitial(
            account(
                id = "mys",
                label = "米游社账号",
                mysUid = "10001",
                stoken = "stoken",
                qrLoginBound = true,
            ),
        )
        viewModel.saveAccount { saved = it }
        mainDispatcherRule.runCurrent()

        val account = requireNotNull(saved)
        assertEquals("", account.mysCookie)
        assertEquals("10001", account.mysUid)
        assertEquals("stoken", account.stoken)
        assertTrue(account.qrLoginBound)
        assertNull(viewModel.uiState.value.saveError)
    }

    @Test
    fun dismissSaveErrorClearsCurrentError() {
        val viewModel = createViewModel { AuthRepository.CredentialHealth(AuthRepository.CredentialState.VALID) }

        viewModel.saveAccount {}
        mainDispatcherRule.runCurrent()
        assertTrue(viewModel.uiState.value.saveError.orEmpty().isNotBlank())

        viewModel.dismissSaveError()

        assertNull(viewModel.uiState.value.saveError)
    }

    @Test
    fun syncInitialResetsTransientStateWhenSwitchingAccounts() {
        val viewModel =
            createViewModel {
                AuthRepository.CredentialHealth(
                    state = AuthRepository.CredentialState.EXPIRING,
                    reason = "Cookie 可能不完整：缺少cookie_token",
                )
            }

        try {
            viewModel.syncInitial(account(id = "1", label = "账号A"))
            viewModel.setTab(2)
            viewModel.updateCookie("account_id=10001")
            mainDispatcherRule.runCurrent()
            mainDispatcherRule.advanceTimeBy(350)
            mainDispatcherRule.runCurrent()

            viewModel.syncInitial(account(id = "2", label = "账号B", mysCookie = ""))

            val state = viewModel.uiState.value
            assertEquals("账号B", state.label)
            assertEquals(0, state.tab)
            assertEquals("", state.cookie)
            assertNull(state.cookieWarning)
            assertTrue(state.hydrated)
        } finally {
            viewModel.resetEditorState()
        }
    }

    @Test
    fun syncInitialStartsFreshWhenSameAccountIsOpenedInNewEditorSession() {
        val viewModel = createViewModel { AuthRepository.CredentialHealth(AuthRepository.CredentialState.VALID) }

        try {
            viewModel.syncInitial(account(id = "1", label = "账号A"), editorSessionKey = 1L)
            viewModel.updateLabel("未保存昵称")
            viewModel.setTab(2)

            viewModel.syncInitial(account(id = "1", label = "账号A"), editorSessionKey = 2L)

            val state = viewModel.uiState.value
            assertEquals("账号A", state.label)
            assertEquals(0, state.tab)
            assertTrue(state.hydrated)
        } finally {
            viewModel.resetEditorState()
        }
    }

    @Test
    fun resetEditorStateIgnoresStaleEditorSessionDispose() {
        val viewModel = createViewModel { AuthRepository.CredentialHealth(AuthRepository.CredentialState.VALID) }

        try {
            viewModel.syncInitial(account(id = "1", label = "账号A"), editorSessionKey = 1L)
            viewModel.syncInitial(account(id = "2", label = "账号B"), editorSessionKey = 2L)

            viewModel.resetEditorState(editorSessionKey = 1L)

            val state = viewModel.uiState.value
            assertEquals("账号B", state.label)
            assertTrue(state.hydrated)
        } finally {
            viewModel.resetEditorState()
        }
    }

    private fun createViewModel(
        health: (Account) -> AuthRepository.CredentialHealth,
    ): AddAccountViewModel {
        every { authRepository.checkCookieHealth(any()) } answers {
            health(firstArg<Account>())
        }
        return AddAccountViewModel(authRepository).apply {
            backgroundDispatcher = mainDispatcherRule.dispatcher
        }
    }

    private fun account(
        id: String,
        label: String,
        mysCookie: String = "",
        mysUid: String = "",
        stoken: String = "",
        qrLoginBound: Boolean = false,
        genshinWebCookie: String = "",
        starrailWebCookie: String = "",
        genshinCloudQrLoginBound: Boolean = false,
        starrailCloudQrLoginBound: Boolean = false,
    ): Account =
        Account(
            id = id,
            label = label,
            mysCookie = mysCookie,
            mysUid = mysUid,
            stoken = stoken,
            qrLoginBound = qrLoginBound,
            genshinWebCookie = genshinWebCookie,
            starrailWebCookie = starrailWebCookie,
            genshinCloudQrLoginBound = genshinCloudQrLoginBound,
            starrailCloudQrLoginBound = starrailCloudQrLoginBound,
        )

}

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    val scheduler: TestCoroutineScheduler = TestCoroutineScheduler(),
    val dispatcher: TestDispatcher = StandardTestDispatcher(scheduler),
) : TestWatcher() {
    fun runCurrent() {
        scheduler.runCurrent()
    }

    fun advanceTimeBy(delayMs: Long) {
        scheduler.advanceTimeBy(delayMs)
    }

    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
