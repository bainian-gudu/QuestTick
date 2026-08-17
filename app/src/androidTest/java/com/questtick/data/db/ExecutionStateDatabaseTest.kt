package com.questtick.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.questtick.data.FailureCategory
import com.questtick.data.RunRecord
import com.questtick.data.RunTrigger
import com.questtick.data.TaskResult
import com.questtick.repository.run.ExecutionStateRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExecutionStateDatabaseTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: ExecutionStateRepository

    @Before
    fun setUp() {
        database =
            Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext(),
                AppDatabase::class.java,
            ).allowMainThreadQueries().build()
        repository = ExecutionStateRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun scheduleSlotIsUniqueAndSameWorkCanReclaimDueRetry() = runBlocking {
        val day = "20260711"
        assertEquals(
            ExecutionStateRepository.SlotClaim.CLAIMED,
            repository.claimScheduledSlot("work-1", 0, day, now = 1_000L),
        )
        assertEquals(
            ExecutionStateRepository.SlotClaim.DUPLICATE,
            repository.claimScheduledSlot("work-2", 0, day, now = 1_001L),
        )

        repository.failScheduledSlot(
            workId = "work-1",
            businessDayKey = day,
            record = null,
            category = FailureCategory.NETWORK_TIMEOUT,
            retryAfterMillis = 60_000L,
            now = 2_000L,
        )
        assertEquals(
            ExecutionStateRepository.SlotClaim.NOT_BEFORE,
            repository.claimScheduledSlot("work-1", 1, day, now = 61_999L),
        )
        assertEquals(
            ExecutionStateRepository.SlotClaim.CLAIMED,
            repository.claimScheduledSlot("work-1", 2, day, now = 62_000L),
        )
        assertEquals(2, database.executionStateDao().getScheduleSlot(day)?.attemptCount)
        assertEquals(0, database.executionStateDao().getScheduleSlot(day)?.runCount)
    }

    @Test
    fun staleRunningSlotCanBeTakenOverButFreshSlotCannot() = runBlocking {
        val day = "20260711"
        repository.claimScheduledSlot("dead-work", 0, day, now = 1_000L)

        assertEquals(
            ExecutionStateRepository.SlotClaim.DUPLICATE,
            repository.claimScheduledSlot("new-work", 0, day, now = 60_000L),
        )
        assertEquals(
            ExecutionStateRepository.SlotClaim.CLAIMED,
            repository.claimScheduledSlot("new-work", 0, day, now = 2L * 60 * 60 * 1000 + 1_001L),
        )
        assertEquals("new-work", database.executionStateDao().getScheduleSlot(day)?.ownerWorkId)
    }

    @Test
    fun accountGuardPausesStructuredAuthAndClearsAfterSuccess() = runBlocking {
        repository.applyRunGuards(record(FailureCategory.AUTH_EXPIRED, retryable = false), now = 10L)

        val guard = repository.activeAccountGuards(now = 11L).single()
        assertEquals("account-1", guard.accountId)
        assertEquals(FailureCategory.AUTH_EXPIRED, guard.reason)

        repository.applyRunGuards(successRecord(), now = 20L)
        assertTrue(repository.activeAccountGuards(now = 21L).isEmpty())
    }

    @Test
    fun manualResumeOnlyRemovesRequestedAccountGuard() = runBlocking {
        repository.applyRunGuards(
            RunRecord(
                timestamp = 1L,
                runId = "run-guards",
                results =
                    listOf(
                        result(FailureCategory.CAPTCHA_REQUIRED, false),
                        result(FailureCategory.SMS_REQUIRED, false).copy(
                            accountId = "account-2",
                            taskId = "account-2|MYS|Genshin",
                        ),
                    ),
            ),
            now = 10L,
        )

        repository.resumeAccount("account-1")

        val remaining = repository.activeAccountGuards(now = 11L)
        assertEquals(listOf("account-2"), remaining.map { it.accountId })
        assertNull(database.executionStateDao().getAccountGuard("account-1"))
    }

    @Test
    fun retryableTaskFilterExcludesSuccessfulAndUnknownTasks() = runBlocking {
        val day = "20260711"
        repository.claimScheduledSlot("work-1", 0, day, now = 1L)
        assertEquals(1, repository.markScheduledRunStarted("work-1", day, now = 2L))
        val record =
            RunRecord(
                timestamp = 2L,
                runId = "run-filter",
                trigger = RunTrigger.SCHEDULED,
                results =
                    listOf(
                        result(FailureCategory.NONE, false).copy(success = true),
                        result(FailureCategory.NETWORK_UNAVAILABLE, true).copy(
                            accountId = "account-2",
                            taskId = "account-2|MYS|StarRail",
                            gameKey = "StarRail",
                        ),
                        result(FailureCategory.RESULT_UNKNOWN, false).copy(
                            accountId = "account-3",
                            taskId = "account-3|MYS|ZZZ",
                            gameKey = "ZZZ",
                            skipped = true,
                        ),
                    ),
            )
        database.historyDao().insert(SignHistoryEntity.fromModel(record))
        repository.failScheduledSlot(
            "work-1",
            day,
            record,
            FailureCategory.NETWORK_UNAVAILABLE,
            retryAfterMillis = 60_000L,
            now = 2L,
        )

        val taskIds = repository.retryableTaskIdsForSlot("work-1", day)

        assertEquals(setOf("account-2|MYS|StarRail"), taskIds)
        assertFalse(taskIds.orEmpty().contains("account-3|MYS|ZZZ"))
    }

    private fun record(category: FailureCategory, retryable: Boolean): RunRecord =
        RunRecord(timestamp = 1L, runId = "run-1", results = listOf(result(category, retryable)))

    private fun successRecord(): RunRecord =
        RunRecord(
            timestamp = 2L,
            runId = "run-success",
            results = listOf(result(FailureCategory.NONE, false).copy(success = true)),
        )

    private fun result(category: FailureCategory, retryable: Boolean): TaskResult =
        TaskResult(
            game = "原神",
            gameKey = "Genshin",
            accountLabel = "账号A",
            accountId = "account-1",
            taskId = "account-1|MYS|Genshin",
            success = false,
            skipped = false,
            message = "结构化结果",
            failureCategory = category,
            retryable = retryable,
        )
}
