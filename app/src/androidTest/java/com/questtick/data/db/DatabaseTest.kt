package com.questtick.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.questtick.data.LogEntry
import com.questtick.data.RunRecord
import com.questtick.data.RunTrigger
import com.questtick.data.TaskResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class DatabaseTest {
    private var database: AppDatabase? = null

    @After
    @Throws(IOException::class)
    fun closeDb() {
        database?.close()
    }

    @Test
    fun logDaoInsertQueryTrimAndPaging() {
        val dao = openDatabase().logDao()

        dao.insertAll(
            listOf(
                LogEntity.fromModel(LogEntry(1L, "INFO", "开始", "")),
                LogEntity.fromModel(LogEntry(2L, "OK", "完成", "detail")),
                LogEntity.fromModel(LogEntry(3L, "WARN", "警告", "detail")),
            ),
        )

        val logs = dao.getAll().map { it.toModel() }
        assertEquals(3, logs.size)
        assertEquals("开始", logs[0].message)
        assertEquals("完成", logs[1].message)
        assertEquals("警告", dao.getPage(limit = 1, offset = 2).first().message)

        dao.deleteOlderThan(2L)
        assertEquals(2, dao.count())
        dao.deleteOldest(1)
        assertEquals(1, dao.count())
    }

    @Test
    fun historyDaoStoresOnlyCurrentRunFormatAndUsesRunIdForUniqueness() {
        val dao = openDatabase().historyDao()
        val first = record(1L, "run-1", "Genshin", success = true)
        val second = record(2L, "run-2", "StarRail", success = false)
        val third = record(3L, "run-3", "ZZZ", success = true)

        dao.insertAll(listOf(first, second, third).map(SignHistoryEntity::fromModel))

        val records = dao.getAll().map { it.toModel() }
        assertEquals(3, records.size)
        assertEquals("run-3", records.first().runId)
        assertEquals("ZZZ", records.first().results.first().game)
        assertEquals("StarRail", dao.getPage(limit = 1, offset = 1).first().toModel().results.first().game)
        assertEquals(-1L, dao.insert(SignHistoryEntity.fromModel(first)))

        dao.deleteOldest(1)
        assertEquals(2, dao.count())
    }

    @Test
    fun accountDaoStoresEncryptedPayloadColumnAndPagesBySortOrder() {
        val dao = openDatabase().accountDao()

        dao.upsertAll(
            listOf(
                AccountEntity("id-2", "账号B", true, "cipher_b", sortOrder = 1, createdAt = 1L, updatedAt = 2L),
                AccountEntity("id-1", "账号A", true, "cipher_a", sortOrder = 0, createdAt = 1L, updatedAt = 2L),
            ),
        )

        val accounts = dao.getAll()
        assertEquals(2, accounts.size)
        assertEquals("id-1", accounts.first().id)
        assertEquals("cipher_a", accounts.first().payloadCipher)
        assertTrue(accounts.first().payloadCipher.contains("cookie").not())
        assertEquals("id-2", dao.getPage(limit = 1, offset = 1).first().id)

        dao.delete("id-1")
        assertEquals(1, dao.count())
    }

    private fun openDatabase(): AppDatabase =
        Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build().also { database = it }

    private fun record(
        timestamp: Long,
        runId: String,
        gameKey: String,
        success: Boolean,
    ): RunRecord =
        RunRecord(
            timestamp = timestamp,
            runId = runId,
            trigger = RunTrigger.MANUAL,
            results =
                listOf(
                    TaskResult(
                        game = gameKey,
                        gameKey = gameKey,
                        accountLabel = "A",
                        accountId = "account-1",
                        taskId = "account-1|MYS|$gameKey",
                        success = success,
                        skipped = false,
                        message = if (success) "OK" else "FAIL",
                    ),
                ),
        )
}
