package com.questtick.sign

import android.content.Context
import com.questtick.data.Account
import com.questtick.data.AppSettings
import com.questtick.data.FailureCategory
import com.questtick.data.RunRecord
import com.questtick.data.RunTerminationReason
import com.questtick.data.SecureStore
import com.questtick.net.HttpResponse
import com.questtick.net.HttpTransport
import com.questtick.repository.account.AccountRepository
import com.questtick.repository.auth.AuthRepository
import com.questtick.repository.calendar.SignInCalendarRepository
import com.questtick.repository.history.HistoryRepository
import com.questtick.repository.log.LogRepository
import com.questtick.repository.run.RunPersistenceRepository
import com.questtick.security.RootDetectorV2
import com.questtick.security.RootEnvironmentChecker
import com.questtick.work.PostRunActionScheduler
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyTimeout
import java.net.ConnectException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 签到编排器边界测试：部分失败、并发与取消。
 *
 * 通过 fake HttpTransport 在 JVM 单元测试中驱动完整 runAll 流程，
 * 固化三条边界：
 * 1. 部分失败时运行仍正常收尾，成功与失败结果同一条运行记录原子落盘；
 * 2. 并行调度下每个计划任务有且仅有一个终态，finishRun 恰好一次；
 * 3. 取消时以 cancelRun 持久化取消态，绝不触发 finishRun。
 */
class SignInRunnerBoundaryTest {
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
        every {
            rootEnvironmentChecker.check(any())
        } returns RootDetectorV2.RootCheckResult(
            isRooted = false,
            score = 0,
            triggers = emptyList(),
            level = RootDetectorV2.RiskLevel.SAFE,
        )
        // 任务执行权与终态持久化：relaxed mock 默认返回 false 会让编排器抛出 RunPersistenceException，
        // 这里显式放行，让测试聚焦于编排器边界而不是持久化状态机。
        every { runPersistenceRepo.markTaskRunning(any(), any(), any(), any()) } returns true
        every { runPersistenceRepo.persistTaskResult(any(), any(), any(), any()) } returns true
        every { runPersistenceRepo.isTaskRunning(any(), any()) } returns false
    }

    @Test
    fun `partial failure keeps the run terminal with one success and one failure`() =
        runBlocking {
            val transport = mixedTransport(failMarker = "BBB")
            val runner =
                newRunner(
                    transport = transport,
                    accounts =
                        listOf(
                            testAccount("acc-a", "账号A", "cookie_token=AAA&account_id=1"),
                            testAccount("acc-b", "账号B", "cookie_token=BBB&account_id=2"),
                        ),
                    parallel = true,
                )

            val record = runner.runAll()

            assertEquals(2, record.total)
            assertEquals(1, record.succeeded)
            assertEquals(1, record.failed)
            assertEquals(RunTerminationReason.COMPLETED, record.terminationReason)

            val byTaskId = record.results.associateBy { it.taskId }
            val successResult = byTaskId["acc-a|MYS|Genshin"]
            val failureResult = byTaskId["acc-b|MYS|Genshin"]
            assertTrue("成功账号缺少任务结果", successResult != null && successResult.success)
            assertFalse("成功账号的结果不应标记为跳过", successResult!!.skipped)
            assertTrue("失败账号缺少任务结果", failureResult != null && !failureResult.success)
            assertTrue("失败账号必须携带失败分类", failureResult!!.failureCategory != FailureCategory.NONE)

            verify(exactly = 1) { runPersistenceRepo.startRun(any(), any(), any(), any()) }
            verify(exactly = 2) { runPersistenceRepo.persistTaskResult(any(), any(), any(), any()) }
            val finishedRecord = slot<RunRecord>()
            verify(exactly = 1) {
                runPersistenceRepo.finishRun(capture(finishedRecord), any(), any(), any(), any())
            }
            assertEquals(record, finishedRecord.captured)
        }

    @Test
    fun `parallel run records each planned task exactly once`() =
        runBlocking {
            val transport = HttpTransport { throw ConnectException("network down (test)") }
            val accounts =
                listOf(
                    testAccount("acc-1", "账号一", "cookie_token=X1&account_id=11"),
                    testAccount("acc-2", "账号二", "cookie_token=X2&account_id=12"),
                    testAccount("acc-3", "账号三", "cookie_token=X3&account_id=13"),
                )
            val runner = newRunner(transport = transport, accounts = accounts, parallel = true)

            val record = runner.runAll()

            val expectedTaskIds = accounts.map { "${it.id}|MYS|Genshin" }.toSet()
            assertEquals(expectedTaskIds, record.results.map { it.taskId }.toSet())
            assertEquals(accounts.size, record.results.size)
            record.results.forEach { result -> assertFalse("每个任务都应当以失败终态结束", result.success) }
            assertEquals(RunTerminationReason.COMPLETED, record.terminationReason)

            // 并发下不允许重复终态：每个任务 ID 恰好出现一次。
            record.results.groupingBy { it.taskId }.eachCount().forEach { (taskId, count) ->
                assertEquals(1, count)
            }

            verify(exactly = 1) { runPersistenceRepo.startRun(any(), any(), any(), any()) }
            verify(exactly = 3) { runPersistenceRepo.persistTaskResult(any(), any(), any(), any()) }
            verify(exactly = 1) { runPersistenceRepo.finishRun(any(), any(), any(), any(), any()) }
        }

    @Test
    fun `cancellation during signing persists cancelRun and never finishRun`() =
        runBlocking {
            val requestGate = CompletableDeferred<Unit>()
            val transport: HttpTransport = mockk()
            every { transport.execute(any()) } answers {
                requestGate.await()
                HttpResponse.text(code = 200, body = "{}")
            }
            val runner =
                newRunner(
                    transport = transport,
                    accounts = listOf(testAccount("acc-1", "账号一", "cookie_token=AAA&account_id=1")),
                    parallel = false,
                )

            val deferred = async { runner.runAll() }
            // 等待运行真正开始并进入请求阶段，确保取消发生在签到进行中。
            verifyTimeout(timeout = 5_000) { runPersistenceRepo.startRun(any(), any(), any(), any()) }
            verifyTimeout(timeout = 5_000) { transport.execute(any()) }

            deferred.cancel()
            var cancellation: CancellationException? = null
            try {
                deferred.await()
            } catch (e: CancellationException) {
                cancellation = e
            }
            assertTrue("取消后 await 应抛出 CancellationException", cancellation != null)

            val reason = slot<RunTerminationReason>()
            verify(exactly = 1) {
                runPersistenceRepo.cancelRun(any(), capture(reason), any(), any())
            }
            assertEquals(RunTerminationReason.USER_CANCELLED, reason.captured)
            verify(exactly = 0) { runPersistenceRepo.finishRun(any(), any(), any(), any(), any()) }
            requestGate.complete(Unit)
        }

    private fun newRunner(
        transport: HttpTransport,
        accounts: List<Account>,
        parallel: Boolean,
    ): SignInRunner {
        every { accountRepo.getAccounts() } returns accounts
        return SignInRunner(
            context = context,
            store = store,
            accountRepository = accountRepo,
            historyRepository = historyRepo,
            signInCalendarRepository = calendarRepo,
            logRepository = logRepo,
            runPersistenceRepository = runPersistenceRepo,
            rootEnvironmentChecker = rootEnvironmentChecker,
            httpTransport = transport,
            authRepository = authRepo,
            postRunActionScheduler = postRunActionScheduler,
            parallel = parallel,
        )
    }

    private fun testAccount(
        id: String,
        label: String,
        cookie: String,
    ): Account =
        Account(
            id = id,
            label = label,
            mysCookie = cookie,
            selectedGames = setOf("Genshin"),
        )

    /**
     * 按 Cookie 标记分流：标记账号的请求直接失败，
     * 其余账号返回角色/签到/奖励查询的成功响应。
     */
    private fun mixedTransport(failMarker: String): HttpTransport =
        HttpTransport { request ->
            val cookie = request.headers["Cookie"].orEmpty()
            if (cookie.contains(failMarker)) throw ConnectException("network down (test)")
            when {
                request.url.contains("/binding/api/getUserGameRolesByCookie") ->
                    HttpResponse.text(
                        code = 200,
                        body = """{"retcode":0,"data":{"list":[{"game_uid":"1","region":"cn_gf01","nickname":"t"}]}}""",
                    )

                request.url.endsWith("/sign") ->
                    HttpResponse.text(code = 200, body = """{"retcode":0,"message":"OK"}""")

                else ->
                    HttpResponse.text(code = 200, body = """{"retcode":0,"message":"OK","data":{}}""")
            }
        }
}
