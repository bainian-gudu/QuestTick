package com.questtick.data.db

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 数据库升级回归测试。
 *
 * 保证未来任何 schema 版本升级时：
 * 1. 导出的 schema 与当前实体模型一致（Room identity 校验）；
 * 2. 已登记的 Migration 路径完整，升级后账号数据保留；
 * 3. 遇到未登记迁移的版本时显式失败并保留原库文件，
 *    而不是通过破坏性回退删除账号、签到历史等本地表。
 */
@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private lateinit var helper: MigrationTestHelper

    @Before
    fun setUp() {
        helper = MigrationTestHelper(context, AppDatabase::class.java, FrameworkSQLiteOpenHelperFactory())
    }

    @After
    fun tearDown() {
        helper.close()
        TEST_DATABASE_NAMES.forEach { name ->
            context.getDatabasePath(name).absoluteFile.delete()
        }
    }

    @Test
    fun `v1 exported schema opens with current models and preserves account data`() {
        val db: AppDatabase = helper.openDatabase(V1_DATABASE, 1)
        try {
            db.accountDao().upsert(testAccount())

            assertEquals(1, helper.queryForLong("SELECT COUNT(*) FROM accounts"))
            val loaded = db.accountDao().getById("account-1")
            assertNotNull(loaded)
            assertEquals("account-1", loaded?.id)
            assertEquals("测试账号", loaded?.label)
        } finally {
            db.close()
        }
    }

    @Test
    fun `all registered migrations form a complete path from v1 and preserve data`() {
        val db: AppDatabase = helper.openDatabase(MIGRATION_DATABASE, 1)
        db.accountDao().upsert(testAccount())
        db.close()

        // 迁移链为空时等价于“当前版本与导出 schema 一致”的校验；
        // 登记 MIGRATION_1_2 并升级版本后，同一测试会真实执行迁移并保留数据。
        val migrated =
            helper.runMigrationsAndValidate(
                AppDatabase.SUPPORTED_VERSION,
                AppDatabase.MIGRATIONS,
            )
        try {
            assertEquals(1, helper.queryForLong("SELECT COUNT(*) FROM accounts"))
        } finally {
            migrated.close()
        }
    }

    @Test
    fun `unknown higher version is rejected explicitly and keeps the database file intact`() {
        val db: AppDatabase = helper.openDatabase(UNKNOWN_VERSION_DATABASE, 1)
        db.accountDao().upsert(testAccount())
        db.close()

        // 模拟未登记的未来 schema 版本：仅提升 user_version，表结构保持不变。
        context.openOrCreateDatabase(UNKNOWN_VERSION_DATABASE, Context.MODE_PRIVATE).use { raw ->
            raw.execSQL("PRAGMA user_version = ${AppDatabase.SUPPORTED_VERSION + 1}")
        }

        assertThrows(IllegalStateException::class.java) {
            AppDatabase.createDatabaseBuilder(context, UNKNOWN_VERSION_DATABASE).build()
        }

        // 原数据必须原样保留——证明不再存在破坏性回退。
        context.openOrCreateDatabase(UNKNOWN_VERSION_DATABASE, Context.MODE_PRIVATE).use { raw ->
            raw.rawQuery("SELECT COUNT(*) FROM accounts", null).use { cursor ->
                assertEquals(true, cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }
        }
    }

    private fun testAccount(): AccountEntity =
        AccountEntity(
            id = "account-1",
            label = "测试账号",
            enabled = true,
            payloadCipher = "test-cipher",
            sortOrder = 0,
            createdAt = 1L,
            updatedAt = 1L,
        )

    private companion object {
        const val V1_DATABASE = "migration_test_v1"
        const val MIGRATION_DATABASE = "migration_test_migrate"
        const val UNKNOWN_VERSION_DATABASE = "migration_test_unknown"
        val TEST_DATABASE_NAMES = listOf(V1_DATABASE, MIGRATION_DATABASE, UNKNOWN_VERSION_DATABASE)
    }
}
