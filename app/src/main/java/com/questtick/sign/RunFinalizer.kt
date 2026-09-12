package com.questtick.sign

import com.questtick.core.throwIfCancellation
import com.questtick.data.LogEntry
import com.questtick.data.RunRecord
import com.questtick.data.SecureStore
import com.questtick.repository.calendar.SignInCalendarRepository
import com.questtick.repository.history.HistoryRepository
import com.questtick.repository.run.PostRunActionRequest
import com.questtick.repository.run.PostRunActionType
import com.questtick.repository.run.RunPersistenceRepository
import com.questtick.work.PostRunActionScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * 运行收尾持久化：原子提交运行结果与日志、登记通知/邮件后置动作、刷新观察流。
 *
 * 收尾分为两个阶段：先落盘（不可取消，保证结果不丢），再调度投递与刷新视图
 * （允许失败，仅影响通知及时性，不回滚已提交结果）。
 */
class RunFinalizer(
    private val store: SecureStore,
    private val runPersistenceRepository: RunPersistenceRepository,
    private val postRunActionScheduler: PostRunActionScheduler,
    private val historyRepository: HistoryRepository,
    private val signInCalendarRepository: SignInCalendarRepository,
    private val log: (level: String, message: String, detail: String) -> Unit,
) {
    private companion object {
        const val BYTES_PER_MB = 1024L * 1024L
    }

    /**
     * @param logs 本次运行的日志快照；由调用方在进入不可取消区间前完成排序。
     * @param enqueuePostRunActions Root 阻断等无需通知的场景传 false。
     */
    suspend fun finish(
        record: RunRecord,
        actIdCache: Map<String, String>,
        startTime: Long,
        logs: List<LogEntry>,
        enqueuePostRunActions: Boolean = true,
    ) {
        val totalElapsed = System.currentTimeMillis() - startTime
        val freeMem = Runtime.getRuntime().freeMemory() / BYTES_PER_MB

        log(
            "INFO",
            "签到完成：成功 ${record.succeeded}，已签 ${record.alreadySigned}，" +
                "失败 ${record.failed}，待确认 ${record.resultUnknown}，跳过 ${record.skipped}",
            "总耗时 ${formatSignInElapsed(totalElapsed)}, freeHeap=${freeMem}MB",
        )
        log("INFO", "========== 签到结束 (${formatSignInElapsed(totalElapsed)}) ==========", "")

        // NonCancellable：收尾步骤必须跑完，不能因为外部取消而中断。
        // 下面几个 try/catch 是刻意的「尽力而为」降级——失败只记日志并跳过对应能力，
        // 绝不向上抛出，否则会覆盖掉本次签到已经产生的真实结果。
        withContext(NonCancellable + Dispatchers.IO) {
            try {
                store.saveActIdCache(actIdCache.toMap())
            } catch (e: Exception) {
                log("WARN", "保存 act_id 缓存失败", "${e.javaClass.simpleName}: ${e.message}")
            }
            val postRunActions =
                if (enqueuePostRunActions) {
                    buildList {
                        try {
                            if (store.getAppSettings().notifyEnabled) {
                                add(PostRunActionRequest(PostRunActionType.NOTIFICATION))
                            }
                        } catch (e: Exception) {
                            log("WARN", "读取通知设置失败，本次不创建通知待办", e.javaClass.simpleName)
                        }
                        try {
                            if (record.total > 0 && store.getMailSettings().enabled) {
                                add(PostRunActionRequest(PostRunActionType.EMAIL))
                            }
                        } catch (e: Exception) {
                            log("WARN", "读取邮件设置失败，本次不创建邮件待办", e.javaClass.simpleName)
                        }
                    }
                } else {
                    emptyList()
                }
            runPersistenceRepository.finishRun(record, logs, postRunActions)
        }
        try {
            postRunActionScheduler.scheduleForRun(record.runId)
        } catch (e: Exception) {
            e.throwIfCancellation()
            log("WARN", "运行结果已保存，投递任务将在下次启动恢复", e.javaClass.simpleName)
        }
        currentCoroutineContext().ensureActive()
        // 数据已经原子提交；以下仅刷新观察流，失败不会回滚已提交结果。
        try {
            historyRepository.reload()
            signInCalendarRepository.reload()
        } catch (e: Exception) {
            e.throwIfCancellation()
            log("WARN", "刷新签到结果视图失败", "${e.javaClass.simpleName}: ${e.message}")
        }
        currentCoroutineContext().ensureActive()
    }
}
