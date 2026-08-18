package com.questtick.repository.run

import androidx.room.withTransaction
import com.questtick.data.FailureCategory
import com.questtick.data.RunRecord
import com.questtick.data.dayKey
import com.questtick.data.db.AccountExecutionGuardEntity
import com.questtick.data.db.AppDatabase
import com.questtick.data.db.ScheduleExecutionSlotEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** 定时执行槽位与账号级后台暂停状态的唯一仓储。 */
@Singleton
class ExecutionStateRepository
    @Inject
    constructor(
        private val database: AppDatabase,
    ) {
        enum class SlotClaim { CLAIMED, DUPLICATE, NOT_BEFORE }

        data class AccountGuard(
            val accountId: String,
            val reason: FailureCategory,
            val errorCode: String,
            val pausedAt: Long,
            val resumeAfter: Long,
        )

        suspend fun claimScheduledSlot(
            workId: String,
            attemptCount: Int,
            businessDayKey: String = dayKey(System.currentTimeMillis()),
            now: Long = System.currentTimeMillis(),
        ): SlotClaim =
            withContext(Dispatchers.IO) {
                database.withTransaction {
                    val dao = database.executionStateDao()
                    val key = businessDayKey
                    val inserted =
                        dao.insertScheduleSlot(
                            ScheduleExecutionSlotEntity(
                                dayKey = key,
                                ownerWorkId = workId,
                                status = SLOT_RUNNING,
                                attemptCount = attemptCount,
                                runCount = 0,
                                runId = "",
                                lastFailureCategory = FailureCategory.NONE.name,
                                notBefore = 0L,
                                claimedAt = now,
                                updatedAt = now,
                            ),
                        )
                    if (inserted != -1L) return@withTransaction SlotClaim.CLAIMED
                    val existing = dao.getScheduleSlot(key) ?: return@withTransaction SlotClaim.DUPLICATE
                    if (existing.ownerWorkId == workId && existing.status == SLOT_RETRY_WAIT && existing.notBefore > now) {
                        return@withTransaction SlotClaim.NOT_BEFORE
                    }
                    val reclaimed =
                        dao.reclaimScheduleSlot(
                            dayKey = key,
                            ownerWorkId = workId,
                            attemptCount = attemptCount,
                            now = now,
                            staleBefore = now - STALE_RUNNING_SLOT_MS,
                        )
                    if (reclaimed == 1) SlotClaim.CLAIMED else SlotClaim.DUPLICATE
                }
            }

        suspend fun completeScheduledSlot(
            workId: String,
            businessDayKey: String,
            record: RunRecord?,
            now: Long = System.currentTimeMillis(),
        ) = finishSlot(workId, businessDayKey, SLOT_COMPLETED, record?.runId.orEmpty(), FailureCategory.NONE, 0L, now)

        suspend fun failScheduledSlot(
            workId: String,
            businessDayKey: String,
            record: RunRecord?,
            category: FailureCategory,
            retryAfterMillis: Long?,
            now: Long = System.currentTimeMillis(),
        ) =
            finishSlot(
                workId = workId,
                businessDayKey = businessDayKey,
                status = if (retryAfterMillis != null) SLOT_RETRY_WAIT else SLOT_TERMINAL_FAILURE,
                runId = record?.runId.orEmpty(),
                category = category,
                notBefore = retryAfterMillis?.let { now + it.coerceAtLeast(MIN_RETRY_DELAY_MS) } ?: 0L,
                now = now,
            )

        private suspend fun finishSlot(
            workId: String,
            businessDayKey: String,
            status: String,
            runId: String,
            category: FailureCategory,
            notBefore: Long,
            now: Long,
        ) =
            withContext(Dispatchers.IO) {
                val updated =
                    database.executionStateDao().finishScheduleSlot(
                        dayKey = businessDayKey,
                        ownerWorkId = workId,
                        status = status,
                        runId = runId,
                        failureCategory = category.name,
                        notBefore = notBefore,
                        now = now,
                    )
                check(updated == 1) { "Scheduled slot ownership was lost" }
            }

        /** 真正进入运行器前递增实际运行次数；WorkManager 空转重试不会消耗该计数。 */
        suspend fun markScheduledRunStarted(
            workId: String,
            businessDayKey: String,
            now: Long = System.currentTimeMillis(),
        ): Int =
            withContext(Dispatchers.IO) {
                val updated = database.executionStateDao().markScheduledRunStarted(businessDayKey, workId, now)
                check(updated == 1) { "Scheduled slot ownership was lost before run start" }
                database.executionStateDao().getScheduleSlot(businessDayKey)?.runCount
                    ?: error("Scheduled slot disappeared after run start")
            }

        /** 返回槽位上一轮中允许自动重试的任务；null 表示这是首次执行，应运行完整计划。 */
        suspend fun retryableTaskIdsForSlot(
            workId: String,
            businessDayKey: String,
        ): Set<String>? =
            withContext(Dispatchers.IO) {
                database.withTransaction {
                    val slot = database.executionStateDao().getScheduleSlot(businessDayKey)
                        ?: return@withTransaction emptySet()
                    if (slot.ownerWorkId != workId) return@withTransaction emptySet()
                    // 尚未真正进入运行器时执行完整计划；已开始但没有形成记录时禁止盲目重放。
                    if (slot.runCount == 0) return@withTransaction null
                    if (slot.runId.isBlank()) return@withTransaction emptySet()
                    val record = database.historyDao().getByRunId(slot.runId)?.toModel()
                        ?: return@withTransaction emptySet()
                    record.results.asSequence()
                        .filter { !it.success && !it.skipped && it.retryable }
                        .filter { it.failureCategory != FailureCategory.RESULT_UNKNOWN }
                        .map { it.taskId }
                        .filter(String::isNotBlank)
                        .toSet()
                }
            }

        suspend fun activeAccountGuards(now: Long = System.currentTimeMillis()): List<AccountGuard> =
            withContext(Dispatchers.IO) {
                database.withTransaction {
                    val dao = database.executionStateDao()
                    dao.deleteExpiredAccountGuards(now)
                    dao.getAllAccountGuards().mapNotNull { it.toDomainOrNull() }
                }
            }

        suspend fun applyRunGuards(
            record: RunRecord,
            now: Long = System.currentTimeMillis(),
        ) =
            withContext(Dispatchers.IO) {
                database.withTransaction {
                    // 单个签到任务失败只记录任务结果，不再冻结整个账号；Root 阻断仍由运行器单独处理。
                    database.executionStateDao().deleteAllAccountGuards()
                }
            }

        /** 清理旧版本遗留的账号冻结记录，避免历史状态阻止本次签到。 */
        suspend fun clearAccountGuards() =
            withContext(Dispatchers.IO) {
                database.executionStateDao().deleteAllAccountGuards()
            }

        suspend fun resumeAccount(accountId: String) =
            withContext(Dispatchers.IO) {
                database.executionStateDao().deleteAccountGuard(accountId)
            }

        private fun AccountExecutionGuardEntity.toDomainOrNull(): AccountGuard? =
            runCatching {
                AccountGuard(accountId, FailureCategory.valueOf(reason), errorCode, pausedAt, resumeAfter)
            }.getOrNull()

        private fun guardPriority(category: FailureCategory): Int =
            when (category) {
                FailureCategory.CAPTCHA_REQUIRED -> 3
                FailureCategory.SMS_REQUIRED -> 3
                FailureCategory.AUTH_EXPIRED -> 2
                else -> 0
            }

        companion object {
            const val SLOT_RUNNING = "RUNNING"
            const val SLOT_RETRY_WAIT = "RETRY_WAIT"
            const val SLOT_COMPLETED = "COMPLETED"
            const val SLOT_TERMINAL_FAILURE = "TERMINAL_FAILURE"
            const val RATE_LIMIT_RETRY_DELAY_MS = 30L * 60 * 1000
            const val NETWORK_RETRY_DELAY_MS = 5L * 60 * 1000
            private const val MIN_RETRY_DELAY_MS = 60_000L
            private const val STALE_RUNNING_SLOT_MS = 2L * 60 * 60 * 1000
        }
    }
