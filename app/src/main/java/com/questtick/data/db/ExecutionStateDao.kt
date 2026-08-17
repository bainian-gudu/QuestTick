package com.questtick.data.db

/** 定时签到执行槽位和账号保护状态的 Room 数据访问接口。 */

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ExecutionStateDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertScheduleSlot(slot: ScheduleExecutionSlotEntity): Long

    @Query("SELECT * FROM schedule_execution_slots WHERE dayKey = :dayKey LIMIT 1")
    fun getScheduleSlot(dayKey: String): ScheduleExecutionSlotEntity?

    @Query(
        """
        UPDATE schedule_execution_slots
        SET ownerWorkId = :ownerWorkId,
            status = 'RUNNING',
            attemptCount = :attemptCount,
            notBefore = 0,
            claimedAt = :now,
            updatedAt = :now
        WHERE dayKey = :dayKey AND (
            (ownerWorkId = :ownerWorkId AND status = 'RETRY_WAIT' AND attemptCount < :attemptCount AND notBefore <= :now)
            OR (status = 'RUNNING' AND updatedAt < :staleBefore)
        )
        """,
    )
    fun reclaimScheduleSlot(
        dayKey: String,
        ownerWorkId: String,
        attemptCount: Int,
        now: Long,
        staleBefore: Long,
    ): Int

    @Query(
        """
        UPDATE schedule_execution_slots
        SET runCount = runCount + 1,
            updatedAt = :now
        WHERE dayKey = :dayKey AND ownerWorkId = :ownerWorkId AND status = 'RUNNING'
        """,
    )
    fun markScheduledRunStarted(
        dayKey: String,
        ownerWorkId: String,
        now: Long,
    ): Int

    @Query(
        """
        UPDATE schedule_execution_slots
        SET status = :status,
            runId = :runId,
            lastFailureCategory = :failureCategory,
            notBefore = :notBefore,
            updatedAt = :now
        WHERE dayKey = :dayKey AND ownerWorkId = :ownerWorkId
        """,
    )
    fun finishScheduleSlot(
        dayKey: String,
        ownerWorkId: String,
        status: String,
        runId: String,
        failureCategory: String,
        notBefore: Long,
        now: Long,
    ): Int

    @Query("DELETE FROM schedule_execution_slots WHERE dayKey < :minimumDayKey")
    fun deleteScheduleSlotsOlderThan(minimumDayKey: String)

    @Query("SELECT * FROM account_execution_guards ORDER BY pausedAt ASC")
    fun getAllAccountGuards(): List<AccountExecutionGuardEntity>

    @Query("SELECT * FROM account_execution_guards WHERE accountId = :accountId LIMIT 1")
    fun getAccountGuard(accountId: String): AccountExecutionGuardEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertAccountGuard(guard: AccountExecutionGuardEntity)

    @Query("DELETE FROM account_execution_guards WHERE accountId = :accountId")
    fun deleteAccountGuard(accountId: String): Int

    @Query("DELETE FROM account_execution_guards WHERE resumeAfter > 0 AND resumeAfter <= :now")
    fun deleteExpiredAccountGuards(now: Long): Int
}
