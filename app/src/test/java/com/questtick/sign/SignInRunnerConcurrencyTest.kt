package com.questtick.sign

import android.content.Context
import com.questtick.data.Account
import com.questtick.data.AppSettings
import com.questtick.data.SecureStore
import com.questtick.repository.account.AccountRepository
import com.questtick.net.HttpTransport
import com.questtick.repository.auth.AuthRepository
import com.questtick.repository.history.HistoryRepository
import com.questtick.repository.calendar.SignInCalendarRepository
import com.questtick.repository.log.LogRepository
import com.questtick.repository.run.RunPersistenceRepository
import com.questtick.security.RootEnvironmentChecker
import com.questtick.work.PostRunActionScheduler
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

/**
 * SignInRunner 基础测试
 */
class SignInRunnerConcurrencyTest {

    private lateinit var context: Context
    private lateinit var store: SecureStore
    private lateinit var accountRepo: AccountRepository
    private lateinit var historyRepo: HistoryRepository
    private lateinit var calendarRepo: SignInCalendarRepository
    private lateinit var logRepo: LogRepository
    private lateinit var runPersistenceRepo: RunPersistenceRepository
    private lateinit var rootEnvironmentChecker: RootEnvironmentChecker
    private lateinit var authRepo: AuthRepository
    private lateinit var postRunActionScheduler: PostRunActionScheduler

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        store = mockk(relaxed = true)
        accountRepo = mockk(relaxed = true)
        historyRepo = mockk(relaxed = true)
        calendarRepo = mockk(relaxed = true)
        logRepo = mockk(relaxed = true)
        runPersistenceRepo = mockk(relaxed = true)
        rootEnvironmentChecker = mockk(relaxed = true)
        authRepo = mockk(relaxed = true)
        postRunActionScheduler = mockk(relaxed = true)

        every { store.getAppSettings() } returns AppSettings()
        every { store.effectiveMysDeviceId(any()) } returns "test-device"
        every { store.effectiveCloudDeviceId(any()) } returns "test-cloud-device"
    }

    @Test
    fun `SignInRunner can be instantiated`() {
        val runner = SignInRunner(
            context = context,
            store = store,
            accountRepository = accountRepo,
            historyRepository = historyRepo,
            signInCalendarRepository = calendarRepo,
            logRepository = logRepo,
            runPersistenceRepository = runPersistenceRepo,
            rootEnvironmentChecker = rootEnvironmentChecker,
            httpTransport = HttpTransport { error("network not expected") },
            authRepository = authRepo,
            postRunActionScheduler = postRunActionScheduler,
            parallel = true
        )
        assertNotNull(runner)
    }
}