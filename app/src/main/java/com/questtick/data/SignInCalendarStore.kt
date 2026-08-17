package com.questtick.data

import android.content.Context
import com.questtick.data.db.AppDatabase
import com.questtick.data.db.calendarDaysFromTaskStates
import com.questtick.data.db.calendarTaskStatesFromRecord
import com.questtick.data.db.mergeCalendarTaskState

/** Room 任务级签到日历。 */
internal class SignInCalendarStore(
    context: Context,
) {
    private val db = AppDatabase.get(context)
    private val dao = db.calendarDao()
    private val lock = Any()

    fun get(): List<SignInCalendarDay> =
        synchronized(lock) {
            dao.deleteOlderThan(dayKey(System.currentTimeMillis() - CALENDAR_RETENTION_MILLIS))
            calendarDaysFromTaskStates(dao.getAllTaskStates())
        }

    fun updateFromRecord(record: RunRecord) =
        synchronized(lock) {
            db.runInTransaction {
                calendarTaskStatesFromRecord(record).forEach { incoming ->
                    dao.upsert(mergeCalendarTaskState(dao.getTaskState(incoming.dayKey, incoming.taskId), incoming))
                }
                dao.deleteOlderThan(dayKey(System.currentTimeMillis() - CALENDAR_RETENTION_MILLIS))
            }
        }

    fun clear() =
        synchronized(lock) {
            dao.clear()
        }

    companion object {
        private const val CALENDAR_RETENTION_MILLIS = 370L * 24 * 60 * 60 * 1000
    }
}
