package com.questtick.ui.components.home

// 首页账号、签到结果和运行状态摘要的计算逻辑。

import com.questtick.data.Account
import com.questtick.data.RunRecord
import com.questtick.data.TaskResult

internal const val LATEST_RUN_VISIBLE_RESULT_LIMIT = 6

internal data class HomeDashboardStats(
    val enabledAccounts: Int,
    val totalAccounts: Int,
    val success: Int,
    val alreadySigned: Int,
    val failed: Int,
)

internal data class LatestRunSummary(
    val succeeded: Int,
    val alreadySigned: Int,
    val failed: Int,
    val resultUnknown: Int,
    val totalCount: Int,
    val topResults: List<TaskResult>,
    val hasMore: Boolean,
    val remainingCount: Int,
)

internal fun latestRunOrNull(history: List<RunRecord>): RunRecord? = history.firstOrNull()

internal fun buildHomeDashboardStats(
    accounts: List<Account>,
    history: List<RunRecord>,
): HomeDashboardStats {
    val latest = latestRunOrNull(history)
    return HomeDashboardStats(
        enabledAccounts = accounts.count { it.enabled },
        totalAccounts = accounts.size,
        success = latest?.succeeded ?: 0,
        alreadySigned = latest?.alreadySigned ?: 0,
        failed = latest?.failed ?: 0,
    )
}

internal fun buildLatestRunSummary(
    record: RunRecord,
    visibleResultLimit: Int = LATEST_RUN_VISIBLE_RESULT_LIMIT,
): LatestRunSummary {
    val safeLimit = visibleResultLimit.coerceAtLeast(0)
    val totalCount = record.results.size
    return LatestRunSummary(
        succeeded = record.succeeded,
        alreadySigned = record.alreadySigned,
        failed = record.failed,
        resultUnknown = record.resultUnknown,
        totalCount = totalCount,
        topResults = record.results.take(safeLimit),
        hasMore = totalCount > safeLimit,
        remainingCount = (totalCount - safeLimit).coerceAtLeast(0),
    )
}
