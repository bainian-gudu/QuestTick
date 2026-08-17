package com.questtick.repository.log

import com.questtick.data.LogEntry
import com.questtick.sign.ErrorText
import com.questtick.sign.Mask
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** 应用级错误入口：立即更新日志页，并在独立 IO 协程中持久化原始异常详情。 */
@Singleton
class AppErrorLogger
    @Inject
    constructor(
        private val logRepository: LogRepository,
    ) {
        private val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun record(
            feature: String,
            error: Throwable,
            message: String = error.message.orEmpty(),
        ) {
            runCatching {
                val entry =
                    LogEntry(
                        timestamp = System.currentTimeMillis(),
                        level = "ERROR",
                        message = Mask.sensitive("[$feature] ${message.ifBlank { error.javaClass.simpleName }}"),
                        detail = Mask.sensitive(ErrorText.detailOf(error)),
                    )
                logRepository.append(entry)
                persistenceScope.launch {
                    runCatching { logRepository.persistAppended(listOf(entry), updateState = false) }
                }
            }
        }
    }
