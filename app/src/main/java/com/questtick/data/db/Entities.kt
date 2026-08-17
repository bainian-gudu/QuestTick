package com.questtick.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.questtick.data.LogEntry
import com.questtick.data.RunRecord
import org.json.JSONObject

@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey val id: String,
    val label: String,
    val enabled: Boolean,
    val payloadCipher: String,
    val sortOrder: Int,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "logs")
data class LogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val timestamp: Long,
    val level: String,
    val message: String,
    val detail: String,
) {
    fun toModel(): LogEntry =
        LogEntry(
            timestamp = timestamp,
            level = level,
            message = message,
            detail = detail,
        )

    companion object {
        fun fromModel(model: LogEntry): LogEntity =
            LogEntity(
                timestamp = model.timestamp,
                level = model.level,
                message = model.message,
                detail = model.detail,
            )
    }
}

@Entity(
    tableName = "sign_history",
    indices = [Index(value = ["runId"], unique = true)],
)
data class SignHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val timestamp: Long,
    val recordJson: String,
    val runId: String,
) {
    fun toModel(): RunRecord = RunRecord.fromJson(JSONObject(recordJson))

    companion object {
        fun fromModel(model: RunRecord): SignHistoryEntity {
            require(model.runId.isNotBlank()) { "History record must have a runId" }
            return SignHistoryEntity(
                timestamp = model.timestamp,
                recordJson = model.toJson().toString(),
                runId = model.runId,
            )
        }
    }
}

/** 一次签到运行的持久化会话；RUNNING 会在进程下次启动时恢复为 INTERRUPTED。 */
@Entity(
    tableName = "sign_runs",
    indices = [Index(value = ["status"]), Index(value = ["startedAt"])],
)
data class SignRunEntity(
    @PrimaryKey val runId: String,
    val trigger: String,
    val status: String,
    val terminationReason: String,
    val startedAt: Long,
    val finishedAt: Long,
    val totalTasks: Int,
    val completedTasks: Int,
    val recordJson: String,
    val updatedAt: Long,
)

/** 运行内单项任务的状态快照；(runId, taskId) 保证每次运行内结果唯一。 */
@Entity(
    tableName = "sign_run_tasks",
    primaryKeys = ["runId", "taskId"],
    indices = [Index(value = ["status"])],
)
data class SignRunTaskEntity(
    val runId: String,
    val taskId: String,
    val accountId: String,
    val accountLabel: String,
    val taskType: String,
    val gameKey: String,
    val targetName: String,
    val status: String,
    val message: String,
    val resultJson: String,
    val startedAt: Long,
    val finishedAt: Long,
    val updatedAt: Long,
)

/** 按业务日期和稳定任务 ID 保存日历状态，后续成功可覆盖同日同任务的失败状态。 */
@Entity(
    tableName = "calendar_task_states",
    primaryKeys = ["dayKey", "taskId"],
    indices = [Index(value = ["updatedAt"]), Index(value = ["sourceRunId"])],
)
data class CalendarTaskStateEntity(
    val dayKey: String,
    val taskId: String,
    val accountId: String,
    val taskType: String,
    val gameKey: String,
    val status: String,
    val updatedAt: Long,
    val sourceRunId: String,
)

/** 每个上海业务日只允许一个定时签到执行槽位；WorkRequest ID 用于识别同一任务的退避重试。 */
@Entity(
    tableName = "schedule_execution_slots",
    indices = [Index(value = ["status"]), Index(value = ["updatedAt"])],
)
data class ScheduleExecutionSlotEntity(
    @PrimaryKey val dayKey: String,
    val ownerWorkId: String,
    val status: String,
    val attemptCount: Int,
    val runCount: Int,
    val runId: String,
    val lastFailureCategory: String,
    val notBefore: Long,
    val claimedAt: Long,
    val updatedAt: Long,
)

/** 按账号保存后台暂停状态；不修改用户的 enabled 选择，用户处理凭证后可显式恢复。 */
@Entity(
    tableName = "account_execution_guards",
    indices = [Index(value = ["reason"]), Index(value = ["updatedAt"])],
)
data class AccountExecutionGuardEntity(
    @PrimaryKey val accountId: String,
    val reason: String,
    val errorCode: String,
    val pausedAt: Long,
    val resumeAfter: Long,
    val updatedAt: Long,
)

/** 每次运行内任务执行尝试的审计快照；重试运行使用新的 runId，因此不会覆盖上一次尝试。 */
@Entity(
    tableName = "sign_task_attempts",
    primaryKeys = ["runId", "taskId"],
    indices = [Index(value = ["status"]), Index(value = ["updatedAt"])],
)
data class SignTaskAttemptEntity(
    val runId: String,
    val taskId: String,
    val accountId: String,
    val taskType: String,
    val gameKey: String,
    val status: String,
    val failureCategory: String,
    val errorCode: String,
    val retryable: Boolean,
    val startedAt: Long,
    val finishedAt: Long,
    val updatedAt: Long,
)

/** 运行完成后独立投递的通知或邮件；payload 仅保存不含凭证的 RunRecord 快照。 */
@Entity(
    tableName = "post_run_actions",
    indices = [
        Index(value = ["runId", "type"], unique = true),
        Index(value = ["status", "nextAttemptAt"]),
        Index(value = ["updatedAt"]),
    ],
)
data class PostRunActionEntity(
    @PrimaryKey val actionId: String,
    val runId: String,
    val type: String,
    val status: String,
    val payloadJson: String,
    val attemptCount: Int,
    val nextAttemptAt: Long,
    val leaseUntil: Long,
    val lastErrorCategory: String,
    val lastErrorCode: String,
    val createdAt: Long,
    val updatedAt: Long,
    val deliveredAt: Long,
)
