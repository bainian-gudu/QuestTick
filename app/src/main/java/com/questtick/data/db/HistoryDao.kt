package com.questtick.data.db

/** 签到历史记录的 Room 数据访问接口。 */

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface HistoryDao {
    @Query("SELECT * FROM sign_history ORDER BY timestamp DESC, id DESC")
    fun getAll(): List<SignHistoryEntity>

    @Query("SELECT * FROM sign_history ORDER BY timestamp DESC, id DESC LIMIT :limit OFFSET :offset")
    fun getPage(
        limit: Int,
        offset: Int,
    ): List<SignHistoryEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(item: SignHistoryEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertAll(items: List<SignHistoryEntity>): List<Long>

    @Query("SELECT * FROM sign_history WHERE runId = :runId LIMIT 1")
    fun getByRunId(runId: String): SignHistoryEntity?

    @Query("DELETE FROM sign_history")
    fun clear()

    @Query("DELETE FROM sign_history WHERE id IN (:ids)")
    fun deleteByIds(ids: List<Long>)

    @Query("DELETE FROM sign_history WHERE timestamp > 0 AND timestamp < :cutoff")
    fun deleteOlderThan(cutoff: Long)

    @Query("SELECT COUNT(*) FROM sign_history")
    fun count(): Int

    @Query(
        """
        DELETE FROM sign_history
        WHERE id IN (
            SELECT id FROM sign_history ORDER BY id ASC LIMIT :count
        )
        """,
    )
    fun deleteOldest(count: Int)
}
