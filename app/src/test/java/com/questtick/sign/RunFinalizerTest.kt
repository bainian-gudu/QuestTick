package com.questtick.sign

import com.questtick.data.AppSettings
import com.questtick.data.LogEntry
import com.questtick.data.MailSettings
import com.questtick.data.RunRecord
import com.questtick.data.RunTrigger
import com.questtick.data.SecureStore
import com.questtick.data.TaskResult
import com.questtick.repository.calendar.SignInCalendarRepository
import com.questtick.repository.history.HistoryRepository
import com.questtick.repository.run.PostRunActionRequest
import com.questtick.repository.run.PostRunActionType
import com.questtick.repository.run.RunPersistenceRepository
import com.questtick.work.PostRunActionScheduler
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** RunFinalizer 收尾落盘与后置动作的边界测试。 */
class RunFinalizerTest {
    private lateinit var store: SecureStore
    private lateinit var runPersistenceRepository: RunPersistenceRepository
    private lateinit var postRunActionScheduler: PostRunActionScheduler
    private lateinit var historyRepository: HistoryRepository
    private lateinit var signInCalendarRepository: SignInCalendarRepository
    private val logs = mutableListOf<LogEntry>()
    private lateinit var finalizer: RunFinalizer

    @Before
    fun setup() {
        store = mockk(relaxed = true)
        runPersistenceRepository = mockk(relaxed = true)
        postRunActionScheduler = mockk(relaxed = true)
        historyRepository = mockk(relaxed = true)
        signInCalendarRepository = mockk(relaxed = true)
        logs.clear()
        finalizer =
            RunFinalizer(
                store = store,
                runPersistenceRepository = runPersistenceRepository,
                postRunActionScheduler = postRunActionScheduler,
                historyRepository = historyRepository,
                signInCalendarRepository = signInCalendarRepository,
                log = { level, message, detail -> logs.add(LogEntry(0L, level, message, detail)) },
            )
    }

    private fun recordWithResults(count: Int): RunRecord =
        RunRecord(
            timestamp = 1L,
            runId = "run-1",
            trigger = RunTrigger.MANUAL,
            results =
                (1..count).map {
                    TaskResult(
                        game = "Genshin",
                        gameKey = "Genshin",
                        accountLabel = "A",
                        accountId = "account-1",
                        taskId = "account-1|MYS|Genshin",
                        success = true,
                        skipped = false,
                        message = "OK",
                    )
                },
        )

    private fun capturedActions(): List<PostRunActionType> {
        val slot = slot<List<PostRunActionRequest>>()
        // finishRun 的 maxHistory/maxLogs 带默认值，JVM 层调用会填充，verify 需匹配全部参数。
        verify { runPersistenceRepository.finishRun(any(), any(), capture(slot), any(), any()) }
        return slot.captured.map { it.type }
    }

    @Test
    fun `finish enqueues notification and email when both enabled`() =
        runTest {
            every { store.getAppSettings() } returns AppSettings(notifyEnabled = true)
            every { store.getMailSettings() } returns MailSettings(enabled = true)

            finalizer.finish(recordWithResults(1), emptyMap(), startTime = 1L, logs = emptyList())

            assertEquals(
                setOf(PostRunActionType.NOTIFICATION, PostRunActionType.EMAIL),
                capturedActions().toSet(),
            )
            verify { postRunActionScheduler.scheduleForRun("run-1", any()) }
            coVerify { historyRepository.reload() }
            coVerify { signInCalendarRepository.reload() }
        }

    @Test
    fun `finish skips email when run has no task results`() =
        runTest {
            every { store.getAppSettings() } returns AppSettings(notifyEnabled = true)
            every { store.getMailSettings() } returns MailSettings(enabled = true)

            finalizer.finish(recordWithResults(0), emptyMap(), startTime = 1L, logs = emptyList())

            assertEquals(listOf(PostRunActionType.NOTIFICATION), capturedActions())
        }

    @Test
    fun `finish skips all post-run actions when disabled`() =
        runTest {
            every { store.getAppSettings() } returns AppSettings(notifyEnabled = false)
            every { store.getMailSettings() } returns MailSettings(enabled = false)

            finalizer.finish(recordWithResults(1), emptyMap(), startTime = 1L, logs = emptyList())

            assertTrue(capturedActions().isEmpty())
        }

    @Test
    fun `finish persists without post-run actions when blocked`() =
        runTest {
            finalizer.finish(
                recordWithResults(1),
                emptyMap(),
                startTime = 1L,
                logs = emptyList(),
                enqueuePostRunActions = false,
            )

            assertTrue(capturedActions().isEmpty())
            // 阻断场景下不读取通知/邮件设置。
            verify(exactly = 0) { store.getAppSettings() }
            verify(exactly = 0) { store.getMailSettings() }
        }

    @Test
    fun `finish tolerates post-run scheduler failure after persistence`() =
        runTest {
            every { store.getAppSettings() } returns AppSettings(notifyEnabled = true)
            every { store.getMailSettings() } returns MailSettings(enabled = false)
            every { postRunActionScheduler.scheduleForRun(any(), any()) } throws RuntimeException("wm unavailable")

            // 不抛出：投递调度失败不能影响已原子提交的结果。
            finalizer.finish(recordWithResults(1), emptyMap(), startTime = 1L, logs = emptyList())

            verify { runPersistenceRepository.finishRun(any(), any(), any(), any(), any()) }
            coVerify { historyRepository.reload() }
            assertTrue(logs.any { it.level == "WARN" && it.message.contains("投递任务将在下次启动恢复") })
        }
}
