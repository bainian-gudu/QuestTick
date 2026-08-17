package com.questtick.data

import android.content.Context
import android.util.Log
import com.questtick.data.db.AppDatabase
import com.questtick.data.db.SignHistoryEntity

/** Room 版签到历史存储。 */
internal class RoomHistoryStore(
    context: Context,
) {
    private val db = AppDatabase.get(context)
    private val dao = db.historyDao()
    private val lock = Any()

    fun get(): List<RunRecord> =
        synchronized(lock) {
            trimLocked()
            loadValidRecordsAndDeleteBrokenRowsLocked().take(DEFAULT_MAX_KEEP)
        }

    fun add(
        record: RunRecord,
        maxKeep: Int = DEFAULT_MAX_KEEP,
    ) = synchronized(lock) {
        db.runInTransaction {
            dao.insert(SignHistoryEntity.fromModel(record))
            trimLocked(maxKeep)
        }
    }

    fun clear() =
        synchronized(lock) {
            dao.clear()
        }

    private fun loadValidRecordsAndDeleteBrokenRowsLocked(): List<RunRecord> {
        val brokenIds = mutableListOf<Long>()
        val records =
            dao.getAll().mapNotNull { entity ->
                try {
                    entity.toModel()
                } catch (e: Exception) {
                    brokenIds += entity.id
                    Log.w(TAG, "Drop broken history row ${entity.id}: ${e.message}")
                    null
                }
            }
        if (brokenIds.isNotEmpty()) {
            dao.deleteByIds(brokenIds)
        }
        return records
    }

    private fun trimLocked(maxKeep: Int = DEFAULT_MAX_KEEP) {
        dao.deleteOlderThan(System.currentTimeMillis() - HISTORY_RETENTION_MILLIS)
        val overflow = dao.count() - maxKeep.coerceAtLeast(1)
        if (overflow > 0) dao.deleteOldest(overflow)
    }

    companion object {
        private const val TAG = "RoomHistoryStore"
        private const val HISTORY_RETENTION_MILLIS = 30L * 24 * 60 * 60 * 1000
        // 以 30 天为业务保留边界；数量上限只作为异常高频运行时的数据库保护。
        const val DEFAULT_MAX_KEEP = 10_000
    }
}
