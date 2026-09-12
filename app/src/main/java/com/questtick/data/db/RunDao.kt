package com.questtick.data.db

// 签到运行会话、任务和尝试状态的 Room 数据访问接口。

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface RunDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertRun(run: SignRunEntity)

    @Update
    fun updateRun(run: SignRunEntity): Int

    @Query("SELECT * FROM sign_runs WHERE runId = :runId LIMIT 1")
    fun getRun(runId: String): SignRunEntity?

    @Query("SELECT * FROM sign_runs WHERE status = :status ORDER BY startedAt ASC")
    fun getRunsByStatus(status: String): List<SignRunEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertTasks(tasks: List<SignRunTaskEntity>)

    @Query("SELECT * FROM sign_run_tasks WHERE runId = :runId ORDER BY taskId ASC")
    fun getTasks(runId: String): List<SignRunTaskEntity>

    @Query("SELECT * FROM sign_run_tasks WHERE runId = :runId AND taskId = :taskId LIMIT 1")
    fun getTask(
        runId: String,
        taskId: String,
    ): SignRunTaskEntity?

    @Query(
        """
        UPDATE sign_run_tasks
        SET status = :newStatus,
            message = :message,
            startedAt = CASE WHEN startedAt > 0 THEN startedAt ELSE :startedAt END,
            updatedAt = :startedAt
        WHERE runId = :runId AND taskId = :taskId AND status = :expectedStatus
        """,
    )
    fun transitionTaskToRunning(
        runId: String,
        taskId: String,
        expectedStatus: String,
        newStatus: String,
        message: String,
        startedAt: Long,
    ): Int

    @Query(
        """
        UPDATE sign_run_tasks
        SET status = :newStatus,
            message = :message,
            resultJson = :resultJson,
            finishedAt = :finishedAt,
            updatedAt = :finishedAt
        WHERE runId = :runId AND taskId = :taskId AND status IN (:expectedStatuses)
        """,
    )
    fun transitionTaskToTerminal(
        runId: String,
        taskId: String,
        expectedStatuses: List<String>,
        newStatus: String,
        message: String,
        resultJson: String,
        finishedAt: Long,
    ): Int

    @Query(
        """
        UPDATE sign_run_tasks
        SET status = :newStatus,
            message = :message,
            finishedAt = :finishedAt,
            updatedAt = :finishedAt
        WHERE runId = :runId AND status IN (:expectedStatuses)
        """,
    )
    fun transitionUnfinishedTasks(
        runId: String,
        expectedStatuses: List<String>,
        newStatus: String,
        message: String,
        finishedAt: Long,
    ): Int

    @Query("UPDATE sign_runs SET completedTasks = :completedTasks, updatedAt = :updatedAt WHERE runId = :runId AND status = 'RUNNING'")
    fun updateCompletedTaskCount(
        runId: String,
        completedTasks: Int,
        updatedAt: Long,
    ): Int

    @Query("SELECT runId FROM sign_runs WHERE status != 'RUNNING' ORDER BY rowid DESC LIMIT -1 OFFSET :maxKeep")
    fun getPrunableRunIds(maxKeep: Int): List<String>

    @Query("DELETE FROM sign_run_tasks WHERE runId IN (:runIds)")
    fun deleteTasksForRuns(runIds: List<String>)

    @Query("DELETE FROM sign_runs WHERE runId IN (:runIds)")
    fun deleteRuns(runIds: List<String>)

    @Query("SELECT COUNT(*) FROM sign_run_tasks WHERE runId = :runId AND status IN (:terminalStatuses)")
    fun countTerminalTasks(
        runId: String,
        terminalStatuses: List<String>,
    ): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertTaskAttempts(attempts: List<SignTaskAttemptEntity>)

    @Query("SELECT * FROM sign_task_attempts WHERE runId = :runId ORDER BY taskId ASC")
    fun getTaskAttempts(runId: String): List<SignTaskAttemptEntity>

    @Query(
        """
        UPDATE sign_task_attempts
        SET status = :newStatus,
            startedAt = CASE WHEN startedAt > 0 THEN startedAt ELSE :startedAt END,
            updatedAt = :startedAt
        WHERE runId = :runId AND taskId = :taskId AND status = :expectedStatus
        """,
    )
    fun transitionAttemptToRunning(
        runId: String,
        taskId: String,
        expectedStatus: String,
        newStatus: String,
        startedAt: Long,
    ): Int

    @Query(
        """
        UPDATE sign_task_attempts
        SET status = :newStatus,
            failureCategory = :failureCategory,
            errorCode = :errorCode,
            retryable = :retryable,
            finishedAt = :finishedAt,
            updatedAt = :finishedAt
        WHERE runId = :runId AND taskId = :taskId AND status IN (:expectedStatuses)
        """,
    )
    fun transitionAttemptToTerminal(
        runId: String,
        taskId: String,
        expectedStatuses: List<String>,
        newStatus: String,
        failureCategory: String,
        errorCode: String,
        retryable: Boolean,
        finishedAt: Long,
    ): Int

    @Query(
        """
        UPDATE sign_task_attempts
        SET status = :newStatus,
            failureCategory = :failureCategory,
            errorCode = :errorCode,
            retryable = :retryable,
            finishedAt = :finishedAt,
            updatedAt = :finishedAt
        WHERE runId = :runId AND status IN (:expectedStatuses)
        """,
    )
    fun transitionUnfinishedAttempts(
        runId: String,
        expectedStatuses: List<String>,
        newStatus: String,
        failureCategory: String,
        errorCode: String,
        retryable: Boolean,
        finishedAt: Long,
    ): Int

    @Query("DELETE FROM sign_task_attempts WHERE runId IN (:runIds)")
    fun deleteAttemptsForRuns(runIds: List<String>)
}
