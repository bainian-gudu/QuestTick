package com.questtick.data.db

/** 任务级签到日历状态的 Room 数据访问接口。 */

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface CalendarDao {
    @Query("SELECT * FROM calendar_task_states ORDER BY dayKey ASC, taskId ASC")
    fun getAllTaskStates(): List<CalendarTaskStateEntity>

    @Query("SELECT * FROM calendar_task_states WHERE dayKey = :dayKey ORDER BY taskId ASC")
    fun getTaskStatesForDay(dayKey: String): List<CalendarTaskStateEntity>

    @Query("SELECT taskId FROM calendar_task_states WHERE dayKey = :dayKey AND status = :status")
    fun getTaskIdsByStatus(
        dayKey: String,
        status: String,
    ): List<String>

    @Query("SELECT * FROM calendar_task_states WHERE dayKey = :dayKey AND taskId = :taskId LIMIT 1")
    fun getTaskState(
        dayKey: String,
        taskId: String,
    ): CalendarTaskStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(item: CalendarTaskStateEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertAll(items: List<CalendarTaskStateEntity>)

    @Query("DELETE FROM calendar_task_states")
    fun clear()

    @Query("DELETE FROM calendar_task_states WHERE dayKey < :minimumDayKey")
    fun deleteOlderThan(minimumDayKey: String)
}
