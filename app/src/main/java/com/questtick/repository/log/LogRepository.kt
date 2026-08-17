package com.questtick.repository.log

import android.content.Context
import com.questtick.data.LogEntry
import com.questtick.data.RoomLogStore
import com.questtick.data.SecureStore
import com.questtick.log.LogExporter
import com.questtick.repository.base.StatefulRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** 日志仓库：负责日志读取、写入、清理与导出。 */
@Singleton
class LogRepository
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val store: SecureStore,
    ) : StatefulRepository<List<LogEntry>>(emptyList(), List<LogEntry>::isEmpty) {
        val logs: StateFlow<List<LogEntry>> = state

        override suspend fun loadFromStore(): List<LogEntry> {
            return withContext(kotlinx.coroutines.Dispatchers.IO) { store.getLogs() }
        }

        override suspend fun saveToStore(data: List<LogEntry>) {
            withContext(kotlinx.coroutines.Dispatchers.IO) { store.saveLogs(data) }
        }

        override fun mergeReloadedData(
            current: List<LogEntry>,
            loaded: List<LogEntry>,
        ): List<LogEntry> = mergeLogSnapshots(loaded, current)

        suspend fun getLogs(): List<LogEntry> = loadFromStore()

        /** 追加到内存日志流；落盘仍由 SignInRunner 在一次运行结束时批量保存。 */
        fun append(entry: LogEntry) {
            updateStateAtomically { current ->
                (current + entry).takeLast(RoomLogStore.DEFAULT_MAX_KEEP)
            }
        }

        suspend fun save(logs: List<LogEntry>) {
            updateAndPersist { logs }
        }

        suspend fun persistAppended(
            entries: List<LogEntry>,
            updateState: Boolean = true,
        ) {
            if (entries.isEmpty()) return
            mutex.withLock {
                withContext(kotlinx.coroutines.Dispatchers.IO) {
                    store.appendLogs(entries)
                }
                if (updateState) {
                    updateStateAtomically { current ->
                        (current + entries).takeLast(RoomLogStore.DEFAULT_MAX_KEEP)
                    }
                }
            }
        }

        suspend fun clear() {
            updateAndPersist { emptyList() }
        }

        suspend fun buildExportText(verbose: Boolean): String =
            withContext(kotlinx.coroutines.Dispatchers.IO) {
                // 使用内存 state，确保导出包含尚未批量落盘的当前运行日志
                LogExporter.buildText(logs.value, verbose)
            }

        suspend fun buildExportBytes(
            verbose: Boolean,
            format: LogExporter.ExportFormat,
        ): ByteArray =
            withContext(kotlinx.coroutines.Dispatchers.IO) {
                LogExporter.buildBytes(logs.value, verbose, format)
            }

        fun suggestExportFileName(format: LogExporter.ExportFormat = LogExporter.ExportFormat.TXT): String =
            LogExporter.suggestFileName(format = format)

        suspend fun exportAndShare(
            verbose: Boolean,
            format: LogExporter.ExportFormat = LogExporter.ExportFormat.TXT,
        ): Result<Unit> =
            withContext(kotlinx.coroutines.Dispatchers.IO) {
                LogExporter.exportAndShare(context, logs.value, verbose, format)
            }
    }

internal fun mergeLogSnapshots(
    persisted: List<LogEntry>,
    current: List<LogEntry>,
    maxKeep: Int = RoomLogStore.DEFAULT_MAX_KEEP,
): List<LogEntry> {
    if (persisted.isEmpty()) return current.takeLast(maxKeep.coerceAtLeast(1))
    if (current.isEmpty()) return persisted.takeLast(maxKeep.coerceAtLeast(1))

    val seen = LinkedHashSet<String>(persisted.size + current.size)
    val merged = ArrayList<LogEntry>(persisted.size + current.size)
    fun add(entry: LogEntry) {
        if (seen.add(entry.identityKey())) merged.add(entry)
    }
    persisted.forEach(::add)
    current.forEach(::add)
    return merged.takeLast(maxKeep.coerceAtLeast(1))
}

private fun LogEntry.identityKey(): String =
    buildString {
        append(timestamp)
        append('|')
        append(level)
        append('|')
        append(message)
        append('|')
        append(detail)
    }
