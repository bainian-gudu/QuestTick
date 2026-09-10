package com.questtick.repository.history

import com.questtick.data.RunRecord
import com.questtick.data.SecureStore
import com.questtick.repository.base.StatefulRepository
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** 签到历史仓库：负责记录读写与简单统计分析。 */
@Singleton
class HistoryRepository
    @Inject
    constructor(
        private val store: SecureStore,
    ) : StatefulRepository<List<RunRecord>>(emptyList(), List<RunRecord>::isEmpty) {
        data class HistoryStats(
            val runs: Int,
            val tasks: Int,
            val succeeded: Int,
            val alreadySigned: Int,
            val failed: Int,
            val resultUnknown: Int,
            val skipped: Int,
        )

        val history: StateFlow<List<RunRecord>> = state

        override suspend fun loadFromStore(): List<RunRecord> = withContext(kotlinx.coroutines.Dispatchers.IO) { store.getHistory() }

        override suspend fun saveToStore(data: List<RunRecord>) {
            // 历史记录只追加，不通过 StatefulRepository.updateAndPersist 全量保存
            throw UnsupportedOperationException("HistoryRepository.saveToStore is not supported; use add()")
        }

        override suspend fun reload() {
            super.reload()
        }

        suspend fun getHistory(): List<RunRecord> = loadFromStore()

        suspend fun add(record: RunRecord) {
            withContext(kotlinx.coroutines.Dispatchers.IO) { store.addRunRecord(record) }
            reload()
        }

        suspend fun clear() {
            withContext(kotlinx.coroutines.Dispatchers.IO) { store.clearHistory() }
            reload()
        }

        fun stats(records: List<RunRecord> = history.value): HistoryStats =
            HistoryStats(
                runs = records.size,
                tasks = records.sumOf { it.total },
                succeeded = records.sumOf { it.succeeded },
                alreadySigned = records.sumOf { it.alreadySigned },
                failed = records.sumOf { it.failed },
                resultUnknown = records.sumOf { it.resultUnknown },
                skipped = records.sumOf { it.skipped },
            )
    }
