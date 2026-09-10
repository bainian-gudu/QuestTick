package com.questtick.repository.run

/** 管理签到完成后动作的领取、状态更新和重试持久化。 */

import com.questtick.data.db.AppDatabase
import com.questtick.data.db.PostRunActionEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

@Singleton
class PostRunActionRepository
    @Inject
    constructor(
        private val db: AppDatabase,
    ) {
        private val dao = db.postRunActionDao()

        fun get(actionId: String): PostRunActionEntity? = dao.getById(actionId)

        fun outstanding(): List<PostRunActionEntity> = dao.getByStatuses(listOf(STATUS_PENDING, STATUS_PROCESSING))

        fun observeAll(): Flow<List<PostRunActionEntity>> = dao.observeAll()

        fun claim(
            actionId: String,
            now: Long = System.currentTimeMillis(),
        ): ClaimResult =
            db.runInTransaction<ClaimResult> {
                val current = dao.getById(actionId) ?: return@runInTransaction ClaimResult.Missing
                if (
                    current.status == STATUS_PROCESSING &&
                    current.type == PostRunActionType.EMAIL.name &&
                    current.leaseUntil <= now
                ) {
                    check(
                        dao.markExpiredDeliveryUnknown(
                            actionId = actionId,
                            now = now,
                            processingStatus = STATUS_PROCESSING,
                            unknownStatus = STATUS_RESULT_UNKNOWN,
                            errorCategory = ERROR_CATEGORY_RESULT_UNKNOWN,
                            errorCode = "process-ended-after-mail-send-started",
                        ) == 1,
                    ) { "Unable to mark expired mail delivery unknown" }
                    return@runInTransaction ClaimResult.Terminal
                }
                val ready =
                    when (current.status) {
                        STATUS_PENDING -> current.nextAttemptAt <= now
                        STATUS_PROCESSING -> current.leaseUntil <= now
                        else -> false
                    }
                if (!ready) {
                    return@runInTransaction if (current.status in TERMINAL_STATUSES) {
                        ClaimResult.Terminal
                    } else {
                        ClaimResult.NotReady(maxOf(current.nextAttemptAt, current.leaseUntil))
                    }
                }
                val changed =
                    dao.claim(
                        actionId = actionId,
                        now = now,
                        leaseUntil = now + LEASE_MILLIS,
                        pendingStatus = STATUS_PENDING,
                        processingStatus = STATUS_PROCESSING,
                    )
                if (changed != 1) return@runInTransaction ClaimResult.NotReady(now + CLAIM_RETRY_MILLIS)
                ClaimResult.Claimed(requireNotNull(dao.getById(actionId)))
            }

        fun markDelivered(
            actionId: String,
            now: Long = System.currentTimeMillis(),
        ) {
            check(dao.markDelivered(actionId, now, STATUS_PROCESSING, STATUS_DELIVERED) == 1) {
                "Unable to complete post-run action"
            }
        }

        fun markCancelled(
            actionId: String,
            errorCode: String,
            now: Long = System.currentTimeMillis(),
        ) {
            check(
                dao.markFailed(
                    actionId = actionId,
                    newStatus = STATUS_CANCELLED,
                    nextAttemptAt = 0L,
                    errorCategory = ERROR_CATEGORY_CONFIGURATION,
                    errorCode = sanitizeCode(errorCode),
                    now = now,
                    processingStatus = STATUS_PROCESSING,
                ) == 1,
            ) { "Unable to cancel post-run action" }
        }

        fun resetEmailForManualRetry(
            actionId: String,
            now: Long = System.currentTimeMillis(),
        ): PostRunActionEntity {
            val current = requireNotNull(dao.getById(actionId)) { "Post-run action not found" }
            require(current.type == PostRunActionType.EMAIL.name) { "Only email actions support manual retry" }
            check(
                dao.resetForManualRetry(
                    actionId = actionId,
                    retryableStatuses = listOf(STATUS_FAILED, STATUS_RESULT_UNKNOWN, STATUS_CANCELLED),
                    pendingStatus = STATUS_PENDING,
                    now = now,
                ) == 1,
            ) { "Email action is not retryable" }
            return requireNotNull(dao.getById(actionId))
        }

        fun markDeliveryFailure(
            action: PostRunActionEntity,
            errorCategory: String,
            errorCode: String,
            retryable: Boolean,
            now: Long = System.currentTimeMillis(),
        ): PostRunActionEntity {
            val shouldRetry = retryable && action.attemptCount < MAX_ATTEMPTS
            val nextAttemptAt = if (shouldRetry) now + retryDelayMillis(action.attemptCount) else 0L
            check(
                dao.markFailed(
                    actionId = action.actionId,
                    newStatus = if (shouldRetry) STATUS_PENDING else STATUS_FAILED,
                    nextAttemptAt = nextAttemptAt,
                    errorCategory = sanitizeCode(errorCategory),
                    errorCode = sanitizeCode(errorCode),
                    now = now,
                    processingStatus = STATUS_PROCESSING,
                ) == 1,
            ) { "Unable to persist post-run delivery failure" }
            return requireNotNull(dao.getById(action.actionId))
        }

        private fun retryDelayMillis(attemptCount: Int): Long {
            val exponent = (attemptCount - 1).coerceIn(0, 10)
            return min(BASE_RETRY_MILLIS * (1L shl exponent), MAX_RETRY_MILLIS)
        }

        private fun sanitizeCode(value: String): String = value.trim().replace(Regex("[^A-Za-z0-9_.-]"), "-").take(80)

        sealed interface ClaimResult {
            data class Claimed(
                val action: PostRunActionEntity,
            ) : ClaimResult

            data class NotReady(
                val readyAt: Long,
            ) : ClaimResult

            data object Missing : ClaimResult

            data object Terminal : ClaimResult
        }

        companion object {
            const val STATUS_PENDING = "PENDING"
            const val STATUS_PROCESSING = "PROCESSING"
            const val STATUS_DELIVERED = "DELIVERED"
            const val STATUS_FAILED = "FAILED"
            const val STATUS_CANCELLED = "CANCELLED"
            const val STATUS_RESULT_UNKNOWN = "RESULT_UNKNOWN"
            const val ERROR_CATEGORY_CONFIGURATION = "CONFIGURATION"
            const val ERROR_CATEGORY_DELIVERY = "DELIVERY"
            const val ERROR_CATEGORY_RESULT_UNKNOWN = "RESULT_UNKNOWN"
            const val MAX_ATTEMPTS = 5
            const val LEASE_MILLIS = 5L * 60 * 1000
            private const val CLAIM_RETRY_MILLIS = 30_000L
            private const val BASE_RETRY_MILLIS = 5L * 60 * 1000
            private const val MAX_RETRY_MILLIS = 6L * 60 * 60 * 1000
            private val TERMINAL_STATUSES = setOf(STATUS_DELIVERED, STATUS_FAILED, STATUS_CANCELLED, STATUS_RESULT_UNKNOWN)
        }
    }
