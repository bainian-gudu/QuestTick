package com.questtick.ui.vm

import android.content.Context
import app.cash.turbine.test
import com.questtick.repository.AppStateRepository
import com.questtick.repository.account.AccountRepository
import com.questtick.repository.history.HistoryRepository
import com.questtick.repository.calendar.SignInCalendarRepository
import com.questtick.repository.log.LogRepository
import com.questtick.repository.log.AppErrorLogger
import com.questtick.repository.run.ExecutionStateRepository
import com.questtick.repository.settings.SettingsRepository
import com.questtick.security.RootEnvironmentChecker
import com.questtick.sign.SignInRunCoordinator
import com.questtick.work.SignInRunnerFactory
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

/**
 * HomeViewModel 状态机测试（使用 Turbine + coroutines-test）
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    private lateinit var context: Context
    private lateinit var appStateRepo: AppStateRepository
    private lateinit var accountRepo: AccountRepository
    private lateinit var historyRepo: HistoryRepository
    private lateinit var calendarRepo: SignInCalendarRepository
    private lateinit var logRepo: LogRepository
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var executionStateRepository: ExecutionStateRepository
    private lateinit var rootEnvironmentChecker: RootEnvironmentChecker
    private lateinit var runnerFactory: SignInRunnerFactory
    private lateinit var appErrorLogger: AppErrorLogger

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        appStateRepo = mockk(relaxed = true)
        accountRepo = mockk(relaxed = true)
        historyRepo = mockk(relaxed = true)
        calendarRepo = mockk(relaxed = true)
        logRepo = mockk(relaxed = true)
        settingsRepo = mockk(relaxed = true)
        executionStateRepository = mockk(relaxed = true)
        rootEnvironmentChecker = mockk(relaxed = true)
        runnerFactory = mockk(relaxed = true)
        appErrorLogger = mockk(relaxed = true)

        every { accountRepo.accounts } returns MutableStateFlow(emptyList())
        every { historyRepo.history } returns MutableStateFlow(emptyList())
        every { historyRepo.loaded } returns MutableStateFlow(true)
        every { calendarRepo.days } returns MutableStateFlow(emptyList())
        every { calendarRepo.loaded } returns MutableStateFlow(true)
        every { settingsRepo.settings } returns MutableStateFlow(com.questtick.data.AppSettings())
        every { settingsRepo.mail } returns MutableStateFlow(com.questtick.data.MailSettings())
    }

    @Test
    fun `HomeViewModel 初始化后 running 状态为 false`() =
        runTest {
            val viewModel = createViewModel()

            viewModel.running.test {
                assertFalse(awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `HomeViewModel progress 初始状态不为 null`() =
        runTest {
            val viewModel = createViewModel()

            viewModel.progress.test {
                val initial = awaitItem()
                assertNotNull(initial)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `HomeViewModel 暴露 accounts Flow`() {
        val viewModel = createViewModel()
        assertNotNull(viewModel.accounts)
    }

    private fun createViewModel(): HomeViewModel =
        HomeViewModel(
            appStateRepository = appStateRepo,
            accountRepository = accountRepo,
            historyRepository = historyRepo,
            signInCalendarRepository = calendarRepo,
            logRepository = logRepo,
            settingsRepository = settingsRepo,
            executionStateRepository = executionStateRepository,
            rootEnvironmentChecker = rootEnvironmentChecker,
            runnerFactory = runnerFactory,
            runCoordinator = SignInRunCoordinator(),
            appErrorLogger = appErrorLogger,
        )
}
