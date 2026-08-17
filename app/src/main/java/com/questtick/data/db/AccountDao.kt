package com.questtick.data.db

/** 账号实体的 Room 数据访问接口。 */

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface AccountDao {
    @Query("SELECT * FROM accounts ORDER BY sortOrder ASC, updatedAt ASC")
    fun getAll(): List<AccountEntity>

    @Query("SELECT * FROM accounts ORDER BY sortOrder ASC, updatedAt ASC LIMIT :limit OFFSET :offset")
    fun getPage(
        limit: Int,
        offset: Int,
    ): List<AccountEntity>

    @Query("SELECT * FROM accounts WHERE id = :id LIMIT 1")
    fun getById(id: String): AccountEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(item: AccountEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertAll(items: List<AccountEntity>)

    @Query("DELETE FROM accounts WHERE id = :id")
    fun delete(id: String)

    @Query("DELETE FROM accounts")
    fun clear()

    @Query("SELECT COUNT(*) FROM accounts")
    fun count(): Int
}
