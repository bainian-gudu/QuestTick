package com.questtick.repository.calendar

import com.questtick.data.RunRecord
import com.questtick.data.SecureStore
import com.questtick.data.SignInCalendarDay
import com.questtick.repository.base.StatefulRepository
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** 独立签到日历仓库：展示用状态不再从签到记录列表推导。 */
@Singleton
class SignInCalendarRepository
    @Inject
    constructor(
        private val store: SecureStore,
    ) : StatefulRepository<List<SignInCalendarDay>>(emptyList(), List<SignInCalendarDay>::isEmpty) {
        val days: StateFlow<List<SignInCalendarDay>> = state

        override suspend fun loadFromStore(): List<SignInCalendarDay> =
            withContext(kotlinx.coroutines.Dispatchers.IO) { store.getSignInCalendarDays() }

        override suspend fun saveToStore(data: List<SignInCalendarDay>) {
            throw UnsupportedOperationException("SignInCalendarRepository saves incrementally; use updateFromRecord()")
        }

        suspend fun updateFromRecord(record: RunRecord) {
            withContext(kotlinx.coroutines.Dispatchers.IO) { store.updateSignInCalendar(record) }
            reload()
        }

        suspend fun clear() {
            withContext(kotlinx.coroutines.Dispatchers.IO) { store.clearSignInCalendar() }
            reload()
        }
    }
