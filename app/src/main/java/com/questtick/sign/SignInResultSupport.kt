package com.questtick.sign

/** 将各类签到执行结果转换为统一的任务记录模型。 */

import com.questtick.data.Account
import com.questtick.data.FailureCategory
import com.questtick.data.TaskResult

internal fun buildMysTaskResult(
    game: String,
    gameKey: String,
    account: Account,
    outcome: MysSignIn.Outcome,
): TaskResult {
    val failure =
        outcome.failure.takeUnless { it.category == FailureCategory.NONE }
            ?: TaskFailureClassifier.classify(
                success = outcome.success,
                skipped = outcome.skipped,
                message = outcome.message,
                detail = outcome.detail,
            )
    return TaskResult(
        game = game,
        gameKey = gameKey,
        accountLabel = account.label,
        success = outcome.success,
        skipped = outcome.skipped,
        alreadySigned = outcome.alreadySigned,
        message = Mask.sensitive(outcome.message),
        rewardName = outcome.reward?.name.orEmpty(),
        rewardCount = outcome.reward?.cnt.orEmpty(),
        rewardIcon = outcome.reward?.icon.orEmpty(),
        totalSignDay = outcome.reward?.day ?: 0,
        taskId = progressTaskId(account, RunTaskType.MYS, gameKey),
        accountId = account.id,
        failureCategory = failure.category,
        errorCode = failure.errorCode,
        retryable = failure.retryable,
    )
}

internal fun buildCloudTaskResult(
    game: String,
    gameKey: String,
    account: Account,
    outcome: CloudSignIn.Outcome,
): TaskResult {
    val failure =
        outcome.failure.takeUnless { it.category == FailureCategory.NONE }
            ?: TaskFailureClassifier.classify(
                success = outcome.success,
                skipped = outcome.skipped,
                message = outcome.message,
                detail = outcome.detail,
            )
    return TaskResult(
        game = game,
        gameKey = gameKey,
        accountLabel = account.label,
        success = outcome.success,
        skipped = outcome.skipped,
        message = Mask.sensitive(outcome.message),
        taskId = progressTaskId(account, RunTaskType.CLOUD, gameKey),
        accountId = account.id,
        failureCategory = failure.category,
        errorCode = failure.errorCode,
        retryable = failure.retryable,
    )
}

internal fun buildMysCoinTaskResult(
    account: Account,
    outcome: MysCoinCheckIn.Outcome,
): TaskResult {
    val failure =
        TaskFailureClassifier.classify(
            success = outcome.success,
            skipped = false,
            message = outcome.message,
            detail = outcome.detail,
        )
    return TaskResult(
        game = MysCoinCheckIn.DISPLAY_NAME,
        gameKey = MysCoinCheckIn.GAME_KEY,
        accountLabel = account.label,
        success = outcome.success,
        skipped = false,
        alreadySigned = outcome.alreadyDone,
        message = Mask.sensitive(outcome.message),
        rewardName = if (outcome.coinGained >= 0) "米游币" else "",
        rewardCount = outcome.coinGained.takeIf { it >= 0 }?.toString().orEmpty(),
        rewardIcon = outcome.rewardIcon,
        totalSignDay = outcome.signDay,
        coinBalance = outcome.coinBalance,
        coinGained = outcome.coinGained,
        taskId = progressTaskId(account, RunTaskType.MYS, MysCoinCheckIn.GAME_KEY),
        accountId = account.id,
        failureCategory = failure.category,
        errorCode = failure.errorCode,
        retryable = failure.retryable,
    )
}

internal fun buildMysOutcomeDetail(outcome: MysSignIn.Outcome): String {
    val parts = mutableListOf<String>()
    if (outcome.detail.isNotBlank()) parts.add(outcome.detail)
    if (outcome.alreadySigned) parts.add("alreadySigned=true")
    if (outcome.reward != null) {
        parts.add("reward={day=${outcome.reward.day}, name=${outcome.reward.name}, cnt=${outcome.reward.cnt}}")
    }
    if (outcome.skipped) parts.add("skipped=true")
    return parts.joinToString(", ")
}

internal fun formatSignInElapsed(ms: Long): String =
    when {
        ms < 1000 -> "${ms}ms"
        ms < 60_000 -> "%.1fs".format(ms / 1000.0)
        else -> "%dm%ds".format(ms / 60_000, (ms % 60_000) / 1000)
    }

internal fun buildFailedTaskResults(
    account: Account,
    errorMessage: String,
    cloudGameBindings: List<CloudGameBinding>,
    failure: TaskFailureDescriptor? = null,
): List<TaskResult> = buildTerminalTaskResults(account, errorMessage, cloudGameBindings, skipped = false, explicitFailure = failure)

internal fun buildSkippedTaskResults(
    account: Account,
    message: String,
    cloudGameBindings: List<CloudGameBinding>,
    failure: TaskFailureDescriptor? = null,
): List<TaskResult> = buildTerminalTaskResults(account, message, cloudGameBindings, skipped = true, explicitFailure = failure)

private fun buildTerminalTaskResults(
    account: Account,
    message: String,
    cloudGameBindings: List<CloudGameBinding>,
    skipped: Boolean,
    explicitFailure: TaskFailureDescriptor?,
): List<TaskResult> {
    val safeMessage = Mask.sensitive(message)
    val failure =
        explicitFailure
            ?: TaskFailureClassifier.classify(
                success = false,
                skipped = skipped,
                message = safeMessage,
            )
    val mysResults =
        account.selectedMysGames().map { game ->
            TaskResult(
                game = game.name,
                gameKey = game.key,
                accountLabel = account.label,
                success = false,
                skipped = skipped,
                message = safeMessage,
                taskId = progressTaskId(account, RunTaskType.MYS, game.key),
                accountId = account.id,
                failureCategory = failure.category,
                errorCode = failure.errorCode,
                retryable = failure.retryable,
            )
        }
    val cloudResults =
        account.selectedCloudBindings(cloudGameBindings).map { binding ->
            TaskResult(
                game = binding.game.name,
                gameKey = binding.game.key,
                accountLabel = account.label,
                success = false,
                skipped = skipped,
                message = safeMessage,
                taskId = progressTaskId(account, RunTaskType.CLOUD, binding.game.key),
                accountId = account.id,
                failureCategory = failure.category,
                errorCode = failure.errorCode,
                retryable = failure.retryable,
            )
        }
    val coinResults =
        if (account.mysCoinEnabled) {
            listOf(
                TaskResult(
                    game = MysCoinCheckIn.DISPLAY_NAME,
                    gameKey = MysCoinCheckIn.GAME_KEY,
                    accountLabel = account.label,
                    success = false,
                    skipped = skipped,
                    message = safeMessage,
                    taskId = progressTaskId(account, RunTaskType.MYS, MysCoinCheckIn.GAME_KEY),
                    accountId = account.id,
                    failureCategory = failure.category,
                    errorCode = failure.errorCode,
                    retryable = failure.retryable,
                ),
            )
        } else {
            emptyList()
        }
    return mysResults + coinResults + cloudResults
}

internal fun computeSignInTaskTotal(
    accounts: List<Account>,
    cloudGameBindings: List<CloudGameBinding>,
): Int =
    accounts.sumOf { account ->
        account.selectedMysGames().size +
            account.selectedCloudBindings(cloudGameBindings).size +
            if (account.mysCoinEnabled) 1 else 0
    }
