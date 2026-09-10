package com.questtick.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.questtick.repository.run.PostRunActionRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PostRunActionDatabaseTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: PostRunActionRepository

    @Before
    fun setUp() {
        database =
            Room
                .inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    AppDatabase::class.java,
                ).allowMainThreadQueries()
                .build()
        repository = PostRunActionRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun notificationCanOnlyBeClaimedOnceUntilLeaseExpires() {
        insertAction(actionId = "run-1:NOTIFICATION", runId = "run-1", type = "NOTIFICATION", now = 1_000L)

        val first = repository.claim("run-1:NOTIFICATION", now = 1_000L)
        assertTrue(first is PostRunActionRepository.ClaimResult.Claimed)
        val claimed = (first as PostRunActionRepository.ClaimResult.Claimed).action
        assertEquals(PostRunActionRepository.STATUS_PROCESSING, claimed.status)
        assertEquals(1, claimed.attemptCount)

        val duplicate = repository.claim("run-1:NOTIFICATION", now = 1_001L)
        assertTrue(duplicate is PostRunActionRepository.ClaimResult.NotReady)

        val recovered = repository.claim("run-1:NOTIFICATION", now = 1_000L + PostRunActionRepository.LEASE_MILLIS)
        assertTrue(recovered is PostRunActionRepository.ClaimResult.Claimed)
        assertEquals(2, (recovered as PostRunActionRepository.ClaimResult.Claimed).action.attemptCount)
    }

    @Test
    fun expiredEmailLeaseBecomesUnknownUntilUserExplicitlyRetries() {
        insertAction(actionId = "run-mail-unknown:EMAIL", runId = "run-mail-unknown", type = "EMAIL", now = 1_000L)
        repository.claim("run-mail-unknown:EMAIL", now = 1_000L)

        val expired =
            repository.claim(
                "run-mail-unknown:EMAIL",
                now = 1_000L + PostRunActionRepository.LEASE_MILLIS,
            )

        assertTrue(expired is PostRunActionRepository.ClaimResult.Terminal)
        val unknown = requireNotNull(database.postRunActionDao().getById("run-mail-unknown:EMAIL"))
        assertEquals(PostRunActionRepository.STATUS_RESULT_UNKNOWN, unknown.status)
        assertEquals(PostRunActionRepository.ERROR_CATEGORY_RESULT_UNKNOWN, unknown.lastErrorCategory)

        val reset = repository.resetEmailForManualRetry(unknown.actionId, now = 999_000L)
        assertEquals(PostRunActionRepository.STATUS_PENDING, reset.status)
        assertEquals(0, reset.attemptCount)
        assertEquals(999_000L, reset.nextAttemptAt)
        assertEquals("", reset.lastErrorCategory)
    }

    @Test
    fun failedEmailBacksOffWithoutChangingNotificationAction() {
        insertAction(actionId = "run-2:EMAIL", runId = "run-2", type = "EMAIL", now = 2_000L)
        insertAction(actionId = "run-2:NOTIFICATION", runId = "run-2", type = "NOTIFICATION", now = 2_000L)

        val email = (repository.claim("run-2:EMAIL", 2_000L) as PostRunActionRepository.ClaimResult.Claimed).action
        val pending =
            repository.markDeliveryFailure(
                action = email,
                errorCategory = PostRunActionRepository.ERROR_CATEGORY_DELIVERY,
                errorCode = "SocketTimeoutException: secret detail",
                retryable = true,
                now = 3_000L,
            )
        assertEquals(PostRunActionRepository.STATUS_PENDING, pending.status)
        assertEquals(303_000L, pending.nextAttemptAt)
        assertEquals("SocketTimeoutException--secret-detail", pending.lastErrorCode)

        val notification =
            (repository.claim("run-2:NOTIFICATION", 2_000L) as PostRunActionRepository.ClaimResult.Claimed).action
        repository.markDelivered(notification.actionId, now = 3_100L)

        assertEquals(
            PostRunActionRepository.STATUS_PENDING,
            database.postRunActionDao().getById(email.actionId)?.status,
        )
        assertEquals(
            PostRunActionRepository.STATUS_DELIVERED,
            database.postRunActionDao().getById(notification.actionId)?.status,
        )
    }

    @Test
    fun actionStopsAfterMaximumDeliveryAttempts() {
        insertAction(actionId = "run-3:EMAIL", runId = "run-3", type = "EMAIL", now = 10_000L)
        var now = 10_000L
        repeat(PostRunActionRepository.MAX_ATTEMPTS) { index ->
            val action =
                (repository.claim("run-3:EMAIL", now) as PostRunActionRepository.ClaimResult.Claimed).action
            val updated =
                repository.markDeliveryFailure(
                    action = action,
                    errorCategory = PostRunActionRepository.ERROR_CATEGORY_DELIVERY,
                    errorCode = "smtp-${index + 1}",
                    retryable = true,
                    now = now,
                )
            if (index < PostRunActionRepository.MAX_ATTEMPTS - 1) {
                assertEquals(PostRunActionRepository.STATUS_PENDING, updated.status)
                now = updated.nextAttemptAt
            } else {
                assertEquals(PostRunActionRepository.STATUS_FAILED, updated.status)
                assertEquals(0L, updated.nextAttemptAt)
            }
        }

        assertTrue(repository.claim("run-3:EMAIL", now + 1L) is PostRunActionRepository.ClaimResult.Terminal)
    }

    @Test
    fun disabledActionBecomesCancelledTerminalState() {
        insertAction(actionId = "run-4:NOTIFICATION", runId = "run-4", type = "NOTIFICATION", now = 5_000L)
        repository.claim("run-4:NOTIFICATION", 5_000L)

        repository.markCancelled("run-4:NOTIFICATION", "permission unavailable", now = 5_100L)

        val action = requireNotNull(database.postRunActionDao().getById("run-4:NOTIFICATION"))
        assertEquals(PostRunActionRepository.STATUS_CANCELLED, action.status)
        assertEquals(PostRunActionRepository.ERROR_CATEGORY_CONFIGURATION, action.lastErrorCategory)
        assertEquals("permission-unavailable", action.lastErrorCode)
    }

    private fun insertAction(
        actionId: String,
        runId: String,
        type: String,
        now: Long,
    ) {
        database.postRunActionDao().insertAll(
            listOf(
                PostRunActionEntity(
                    actionId = actionId,
                    runId = runId,
                    type = type,
                    status = PostRunActionRepository.STATUS_PENDING,
                    payloadJson = "{}",
                    attemptCount = 0,
                    nextAttemptAt = now,
                    leaseUntil = 0L,
                    lastErrorCategory = "",
                    lastErrorCode = "",
                    createdAt = now,
                    updatedAt = now,
                    deliveredAt = 0L,
                ),
            ),
        )
    }
}
