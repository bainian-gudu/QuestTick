package com.questtick.sign

import com.questtick.data.TaskResult
import java.util.concurrent.ConcurrentHashMap

/** 任务终态写入结果；用于区分正常完成、重复完成和计划外结果。 */
internal enum class TaskResultAcceptance {
    ACCEPTED,
    DUPLICATE,
    UNPLANNED,
}

/**
 * 单次签到运行的任务结果收集器。
 *
 * 只有任务计划中的 ID 可以写入，且同一任务只接受第一个终态结果。并行账号同时完成时通过
 * ConcurrentHashMap.putIfAbsent 保证检查与写入原子化，避免同一任务同时出现成功和失败记录。
 */
internal class TaskResultAccumulator(
    expectedTaskIds: Collection<String>,
) {
    private val expected = expectedTaskIds.toHashSet().also { uniqueIds ->
        require(uniqueIds.size == expectedTaskIds.size) { "Task plan contains duplicate task IDs" }
    }
    private val resultsByTaskId = ConcurrentHashMap<String, TaskResult>()

    fun accept(
        taskId: String,
        result: TaskResult,
    ): TaskResultAcceptance {
        if (taskId !in expected) return TaskResultAcceptance.UNPLANNED
        val normalized =
            result.copy(
                taskId = taskId,
                accountId = result.accountId.ifBlank { taskId.substringBefore('|') },
            )
        return if (resultsByTaskId.putIfAbsent(taskId, normalized) == null) {
            TaskResultAcceptance.ACCEPTED
        } else {
            TaskResultAcceptance.DUPLICATE
        }
    }

    fun isExpected(taskId: String): Boolean = taskId in expected

    fun contains(taskId: String): Boolean = resultsByTaskId.containsKey(taskId)

    fun values(): List<TaskResult> = resultsByTaskId.values.toList()

    val size: Int
        get() = resultsByTaskId.size
}
