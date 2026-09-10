package com.questtick.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/**
 * 数据库升级回归测试。
 *
 * 当前数据库版本为 1，尚无历史版本需要迁移。本测试验证 v1 导出 schema 与实体定义一致，
 * 并作为后续版本升级的回归基线：数据库每次提升版本都必须提供显式 Migration，
 * 追加到 [AppDatabase.MIGRATIONS] 并传入本测试，同时保留下方预置数据的断言，
 * 防止缺少迁移路径时静默丢失账号与记录。
 */
@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {
    private val testDbName = "migration-test"

    @get:Rule
    val helper: MigrationTestHelper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            AppDatabase::class.java,
        )

    @Test
    @Throws(IOException::class)
    fun v1DataSurvivesUpgradeToLatest() {
        // 以 v1 建库并预置数据，模拟旧版本用户的本地数据。
        helper.createDatabase(testDbName, 1).apply {
            execSQL(
                "INSERT INTO accounts (id, label, enabled, payloadCipher, sortOrder, createdAt, updatedAt) " +
                    "VALUES ('acc-1', '账号A', 1, 'cipher', 0, 1, 1)",
            )
            execSQL("INSERT INTO logs (timestamp, level, message, detail) VALUES (1, 'INFO', '开始', '')")
            close()
        }

        // 版本 1 即当前最新版本：无迁移路径时仅校验 schema 与导出 JSON 一致。
        // 提升到 version 2 时，在此把目标版本改为 2 并依赖 AppDatabase.MIGRATIONS 中的新迁移。
        val db =
            helper.runMigrationsAndValidate(
                testDbName,
                1,
                true,
                *AppDatabase.MIGRATIONS,
            )
        db.query("SELECT id FROM accounts").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("acc-1", cursor.getString(0))
        }
        db.query("SELECT COUNT(*) FROM logs").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }
        db.close()
    }
}
