package com.questtick.data

import android.content.Context
import com.questtick.data.db.AppDatabase
import com.questtick.data.db.LogEntity
import com.questtick.sign.Mask

/** Room 版日志存储。 */
internal class RoomLogStore(
    context: Context,
) {
    private val db = AppDatabase.get(context)
    private val dao = db.logDao()
    private val lock = Any()

    fun get(): List<LogEntry> =
        synchronized(lock) {
            trimLocked()
            dao.getAll().map { it.toModel() }
        }

    /**
     * 用完整日志快照替换数据库内容。
     *
     * clear + insertAll 必须放在同一个事务中，否则插入异常时可能留下空日志表。
     * 一般新增日志应优先使用 append()，避免多个调用方基于旧快照互相覆盖。
     */
    fun save(
        logs: List<LogEntry>,
        maxKeep: Int = DEFAULT_MAX_KEEP,
    ) = synchronized(lock) {
        db.runInTransaction {
            dao.clear()
            val trimmed = trimForSave(logs, maxKeep)
            if (trimmed.isNotEmpty()) dao.insertAll(trimmed.map(LogEntity::fromModel))
            trimLocked(maxKeep)
        }
    }

    /** 追加日志并在同一事务内裁剪，避免全量读-改-写导致并发覆盖。 */
    fun append(
        logs: List<LogEntry>,
        maxKeep: Int = DEFAULT_MAX_KEEP,
    ) = synchronized(lock) {
        if (logs.isEmpty()) return@synchronized
        db.runInTransaction {
            val normalized = normalizeForInsert(logs)
            if (normalized.isNotEmpty()) dao.insertAll(normalized.map(LogEntity::fromModel))
            trimLocked(maxKeep)
        }
    }

    fun clear() =
        synchronized(lock) {
            dao.clear()
        }

    private fun trimLocked(maxKeep: Int = DEFAULT_MAX_KEEP) {
        // 按日期删除超过 30 天的旧日志
        val thirtyDaysAgo = System.currentTimeMillis() - LOG_RETENTION_MILLIS
        dao.deleteOlderThan(thirtyDaysAgo)

        // 按数量裁剪（保留最新 maxKeep 条）
        val overflow = dao.count() - maxKeep.coerceAtLeast(1)
        if (overflow > 0) dao.deleteOldest(overflow)
    }

    private fun trimForSave(
        logs: List<LogEntry>,
        maxKeep: Int = DEFAULT_MAX_KEEP,
    ): List<LogEntry> =
        normalizeForInsert(logs)
            .sortedBy { it.timestamp }
            .takeLast(maxKeep.coerceAtLeast(1))

    private fun normalizeForInsert(logs: List<LogEntry>): List<LogEntry> {
        if (logs.isEmpty()) return emptyList()
        val cutoff = System.currentTimeMillis() - LOG_RETENTION_MILLIS
        return logs
            .asSequence()
            .filter { it.timestamp <= 0L || it.timestamp >= cutoff }
            .map {
                it.copy(
                    level = LogLevel.normalize(it.level),
                    message = Mask.sensitive(it.message),
                    detail = Mask.sensitive(it.detail),
                )
            }.toList()
    }

    companion object {
        private const val TAG = "RoomLogStore"
        private const val LOG_RETENTION_MILLIS = 30L * 24 * 60 * 60 * 1000

        // 以 30 天为业务保留边界；数量上限只防止异常日志风暴拖垮列表和数据库。
        const val DEFAULT_MAX_KEEP = 10_000
    }
}
