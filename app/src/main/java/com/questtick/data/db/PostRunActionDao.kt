package com.questtick.data.db

/** 签到完成后通知和邮件投递队列的 Room 数据访问接口。 */

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PostRunActionDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertAll(actions: List<PostRunActionEntity>)

    @Query(
        """
        UPDATE post_run_actions
        SET status = :processingStatus,
            attemptCount = attemptCount + 1,
            leaseUntil = :leaseUntil,
            updatedAt = :now
        WHERE actionId = :actionId
          AND ((status = :pendingStatus AND nextAttemptAt <= :now)
            OR (status = :processingStatus AND leaseUntil <= :now))
        """,
    )
    fun claim(
        actionId: String,
        now: Long,
        leaseUntil: Long,
        pendingStatus: String,
        processingStatus: String,
    ): Int

    @Query("SELECT * FROM post_run_actions WHERE actionId = :actionId LIMIT 1")
    fun getById(actionId: String): PostRunActionEntity?

    @Query("SELECT * FROM post_run_actions WHERE status IN (:statuses) ORDER BY createdAt ASC")
    fun getByStatuses(statuses: List<String>): List<PostRunActionEntity>

    @Query("SELECT * FROM post_run_actions ORDER BY createdAt DESC, actionId ASC")
    fun observeAll(): Flow<List<PostRunActionEntity>>

    @Query("SELECT * FROM post_run_actions WHERE runId = :runId ORDER BY type ASC")
    fun getByRunId(runId: String): List<PostRunActionEntity>

    @Query(
        """
        UPDATE post_run_actions
        SET status = :deliveredStatus,
            leaseUntil = 0,
            lastErrorCategory = '',
            lastErrorCode = '',
            deliveredAt = :now,
            updatedAt = :now
        WHERE actionId = :actionId AND status = :processingStatus
        """,
    )
    fun markDelivered(
        actionId: String,
        now: Long,
        processingStatus: String,
        deliveredStatus: String,
    ): Int

    @Query(
        """
        UPDATE post_run_actions
        SET status = :newStatus,
            nextAttemptAt = :nextAttemptAt,
            leaseUntil = 0,
            lastErrorCategory = :errorCategory,
            lastErrorCode = :errorCode,
            updatedAt = :now
        WHERE actionId = :actionId AND status = :processingStatus
        """,
    )
    fun markFailed(
        actionId: String,
        newStatus: String,
        nextAttemptAt: Long,
        errorCategory: String,
        errorCode: String,
        now: Long,
        processingStatus: String,
    ): Int

    @Query(
        """
        UPDATE post_run_actions
        SET status = :unknownStatus,
            nextAttemptAt = 0,
            leaseUntil = 0,
            lastErrorCategory = :errorCategory,
            lastErrorCode = :errorCode,
            updatedAt = :now
        WHERE actionId = :actionId AND status = :processingStatus AND leaseUntil <= :now
        """,
    )
    fun markExpiredDeliveryUnknown(
        actionId: String,
        now: Long,
        processingStatus: String,
        unknownStatus: String,
        errorCategory: String,
        errorCode: String,
    ): Int

    @Query(
        """
        UPDATE post_run_actions
        SET status = :pendingStatus,
            attemptCount = 0,
            nextAttemptAt = :now,
            leaseUntil = 0,
            lastErrorCategory = '',
            lastErrorCode = '',
            updatedAt = :now,
            deliveredAt = 0
        WHERE actionId = :actionId AND status IN (:retryableStatuses)
        """,
    )
    fun resetForManualRetry(
        actionId: String,
        retryableStatuses: List<String>,
        pendingStatus: String,
        now: Long,
    ): Int

    @Query("DELETE FROM post_run_actions WHERE status IN (:terminalStatuses) AND updatedAt < :cutoff")
    fun deleteTerminalOlderThan(
        terminalStatuses: List<String>,
        cutoff: Long,
    )
}
