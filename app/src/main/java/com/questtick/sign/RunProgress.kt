package com.questtick.sign

import com.questtick.data.Account
import com.questtick.data.TaskResult

/** 签到运行阶段，用于首页展示当前任务而不是只依赖百分比。 */
enum class RunProgressPhase(val label: String) {
    IDLE("待机"),
    PREPARING("准备任务"),
    CHECKING("检查环境"),
    REFRESHING("刷新凭证"),
    SIGNING("执行签到"),
    SAVING("保存结果"),
    NOTIFYING("发送通知"),
    FINISHED("完成"),
    BLOCKED("已阻断"),
    CANCELLED("已取消"),
    FAILED("异常结束"),
}

/** 单个签到任务类型。 */
enum class RunTaskType(val label: String) {
    MYS("米游社"),
    CLOUD("云游戏"),
}

/** 单个签到任务状态。 */
enum class RunTaskStatus(val label: String) {
    PENDING("等待中"),
    RUNNING("进行中"),
    SUCCESS("成功"),
    ALREADY_SIGNED("已签到"),
    FAILED("失败"),
    SKIPPED("跳过"),
    RESULT_UNKNOWN("结果待确认"),
    CANCELLED("已取消"),
}

val RunTaskStatus.isTerminal: Boolean
    get() = this == RunTaskStatus.SUCCESS ||
        this == RunTaskStatus.ALREADY_SIGNED ||
        this == RunTaskStatus.FAILED ||
        this == RunTaskStatus.SKIPPED ||
        this == RunTaskStatus.RESULT_UNKNOWN ||
        this == RunTaskStatus.CANCELLED

data class RunTaskProgress(
    val id: String,
    val accountLabel: String,
    val targetName: String,
    val type: RunTaskType,
    val status: RunTaskStatus = RunTaskStatus.PENDING,
    val message: String = "",
    val startedAt: Long = 0L,
    val finishedAt: Long = 0L,
) {
    val title: String get() = "$accountLabel · $targetName"
}

data class RunProgressState(
    val running: Boolean = false,
    val phase: RunProgressPhase = RunProgressPhase.IDLE,
    val startedAt: Long = 0L,
    val finishedAt: Long = 0L,
    val message: String = "",
    val currentTaskId: String = "",
    val tasks: List<RunTaskProgress> = emptyList(),
) {
    val total: Int get() = tasks.size
    val done: Int get() = tasks.count { it.status.isTerminal }
    val success: Int get() = tasks.count { it.status == RunTaskStatus.SUCCESS }
    val alreadySigned: Int get() = tasks.count { it.status == RunTaskStatus.ALREADY_SIGNED }
    val failed: Int get() = tasks.count { it.status == RunTaskStatus.FAILED }
    val skipped: Int get() = tasks.count { it.status == RunTaskStatus.SKIPPED }
    val resultUnknown: Int get() = tasks.count { it.status == RunTaskStatus.RESULT_UNKNOWN }
    val cancelled: Int get() = tasks.count { it.status == RunTaskStatus.CANCELLED }
    val currentTask: RunTaskProgress? get() = tasks.firstOrNull { it.id == currentTaskId }
    val visible: Boolean get() = running || phase != RunProgressPhase.IDLE || tasks.isNotEmpty()
}

internal fun progressTaskId(
    account: Account,
    type: RunTaskType,
    gameKey: String,
): String = "${account.id}|${type.name}|$gameKey"

internal fun buildRunTaskPlan(
    accounts: List<Account>,
    cloudGameBindings: List<CloudGameBinding>,
): List<RunTaskProgress> =
    accounts.flatMap { account ->
        val mysTasks =
            account.selectedMysGames().map { game ->
                RunTaskProgress(
                    id = progressTaskId(account, RunTaskType.MYS, game.key),
                    accountLabel = account.label,
                    targetName = game.name,
                    type = RunTaskType.MYS,
                )
            }
        val coinTask =
            if (account.mysCoinEnabled) {
                listOf(
                    RunTaskProgress(
                        id = progressTaskId(account, RunTaskType.MYS, MysCoinCheckIn.GAME_KEY),
                        accountLabel = account.label,
                        targetName = MysCoinCheckIn.DISPLAY_NAME,
                        type = RunTaskType.MYS,
                    ),
                )
            } else {
                emptyList()
            }
        val cloudTasks =
            account.selectedCloudBindings(cloudGameBindings).map { binding ->
                RunTaskProgress(
                    id = progressTaskId(account, RunTaskType.CLOUD, binding.game.key),
                    accountLabel = account.label,
                    targetName = binding.game.name,
                    type = RunTaskType.CLOUD,
                )
            }
        mysTasks + coinTask + cloudTasks
    }

internal fun taskStatusFromResult(result: TaskResult): RunTaskStatus =
    when {
        result.failureCategory == com.questtick.data.FailureCategory.RESULT_UNKNOWN -> RunTaskStatus.RESULT_UNKNOWN
        result.skipped -> RunTaskStatus.SKIPPED
        result.success && result.alreadySigned -> RunTaskStatus.ALREADY_SIGNED
        result.success -> RunTaskStatus.SUCCESS
        else -> RunTaskStatus.FAILED
    }

internal fun taskIdForResult(
    account: Account,
    result: TaskResult,
    cloudGameBindings: List<CloudGameBinding>,
): String {
    val cloudKeys = cloudGameBindings.map { it.game.key }.toSet()
    val type = if (result.gameKey in cloudKeys) RunTaskType.CLOUD else RunTaskType.MYS
    return progressTaskId(account, type, result.gameKey)
}

fun runProgressTaskPreview(
    tasks: List<RunTaskProgress>,
    maxItems: Int = 4,
): List<RunTaskProgress> {
    if (tasks.isEmpty()) return emptyList()
    val safeMax = maxItems.coerceAtLeast(1)
    if (tasks.size <= safeMax) return tasks

    val anchorIndex =
        tasks.indexOfFirst { it.status == RunTaskStatus.RUNNING }
            .takeIf { it >= 0 }
            ?: tasks.indexOfFirst { !it.status.isTerminal }
                .takeIf { it >= 0 }
            ?: tasks.indexOfLast { it.status.isTerminal }.coerceAtLeast(0)
    val start = (anchorIndex - safeMax / 2).coerceIn(0, tasks.size - safeMax)
    return tasks.subList(start, start + safeMax)
}
