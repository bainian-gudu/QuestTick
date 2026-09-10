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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 签到运行收尾持久化服务。
 *
 * 负责运行结束后的收尾落盘：汇总日志、保存 act_id 缓存、
 * 生成通知/邮件待办、原子提交运行记录，并刷新观察流。
 * 落盘在 [NonCancellable] 上下文执行，取消也不会丢失已确认的终态。
 */
// 收尾代码从编排器原样迁入，以下抑制项与迁入前在 SignInRunner.kt 中的 detekt 结论保持一致
//（存量项原本由 baseline.xml 冻结，按文件归属随代码迁移，避免 baseline 签名失配）。
@Suppress("MagicNumber", "TooGenericExceptionCaught")
internal class SignInRunFinalizer(
    private val store: SecureStore,
    private val runPersistenceRepository: RunPersistenceRepository,
    private val historyRepository: HistoryRepository,
    private val signInCalendarRepository: SignInCalendarRepository,
    private val postRunActionScheduler: PostRunActionScheduler,
    private val logs: ConcurrentLinkedQueue<LogEntry>,
    private val log: (level: String, message: String, detail: String) -> Unit,
) {
    suspend fun finish(
        record: RunRecord,
        actIdCache: ConcurrentHashMap<String, String>,
        startTime: Long,
        enqueuePostRunActions: Boolean = true,
    ) {
        val totalElapsed = System.currentTimeMillis() - startTime
        val freeMem = Runtime.getRuntime().freeMemory() / 1024 / 1024

        log(
            "INFO",
            "签到完成：成功 ${record.succeeded}，已签 ${record.alreadySigned}，" +
                "失败 ${record.failed}，待确认 ${record.resultUnknown}，跳过 ${record.skipped}",
            "总耗时 ${formatSignInElapsed(totalElapsed)}, freeHeap=${freeMem}MB",
        )
        log("INFO", "========== 签到结束 (${formatSignInElapsed(totalElapsed)}) ==========", "")

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
            val currentRunLogs = logs.toList().sortedBy { it.timestamp }
            runPersistenceRepository.finishRun(record, currentRunLogs, postRunActions)
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
