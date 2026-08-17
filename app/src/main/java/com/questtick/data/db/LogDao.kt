package com.questtick.data.db

/** 运行日志的 Room 数据访问接口。 */

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface LogDao {
    @Query("SELECT * FROM logs ORDER BY timestamp ASC, id ASC")
    fun getAll(): List<LogEntity>

    @Query("SELECT * FROM logs ORDER BY timestamp ASC, id ASC LIMIT :limit OFFSET :offset")
    fun getPage(
        limit: Int,
        offset: Int,
    ): List<LogEntity>

    @Insert
    fun insertAll(items: List<LogEntity>)

    @Query("DELETE FROM logs")
    fun clear()

    @Query("DELETE FROM logs WHERE timestamp > 0 AND timestamp < :cutoff")
    fun deleteOlderThan(cutoff: Long)

    @Query("SELECT COUNT(*) FROM logs")
    fun count(): Int

    @Query("DELETE FROM logs WHERE id IN (SELECT id FROM logs ORDER BY timestamp ASC, id ASC LIMIT :count)")
    fun deleteOldest(count: Int)
}
