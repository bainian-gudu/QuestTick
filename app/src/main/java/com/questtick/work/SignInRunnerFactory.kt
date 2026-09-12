package com.questtick.work

// 根据运行设置创建串行或并行签到执行器。

import android.content.Context
import com.questtick.data.SecureStore
import com.questtick.repository.account.AccountRepository
import com.questtick.net.HttpTransport
import com.questtick.repository.auth.AuthRepository
import com.questtick.repository.history.HistoryRepository
import com.questtick.repository.calendar.SignInCalendarRepository
import com.questtick.repository.log.LogRepository
import com.questtick.repository.log.AppErrorLogger
import com.questtick.repository.run.RunPersistenceRepository
import com.questtick.security.RootEnvironmentChecker
import com.questtick.sign.SignInRunCoordinator
import com.questtick.sign.SignInRunner
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SignInRunnerFactory
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val store: SecureStore,
        private val accountRepository: AccountRepository,
        private val historyRepository: HistoryRepository,
        private val signInCalendarRepository: SignInCalendarRepository,
        private val logRepository: LogRepository,
        private val runPersistenceRepository: RunPersistenceRepository,
        private val rootEnvironmentChecker: RootEnvironmentChecker,
        private val httpTransport: HttpTransport,
        private val authRepository: AuthRepository,
        private val postRunActionScheduler: PostRunActionScheduler,
        private val runCoordinator: SignInRunCoordinator,
        private val appErrorLogger: AppErrorLogger,
    ) {
        fun create(parallel: Boolean): SignInRunner =
            SignInRunner(
                context = context,
                store = store,
                accountRepository = accountRepository,
                historyRepository = historyRepository,
                signInCalendarRepository = signInCalendarRepository,
                logRepository = logRepository,
                runPersistenceRepository = runPersistenceRepository,
                rootEnvironmentChecker = rootEnvironmentChecker,
                httpTransport = httpTransport,
                authRepository = authRepository,
                postRunActionScheduler = postRunActionScheduler,
                parallel = parallel,
                runCoordinator = runCoordinator,
                appErrorLogger = appErrorLogger,
            )
    }
