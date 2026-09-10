package com.questtick.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * 当前唯一受支持的数据库结构。
 *
 * 本库存放账号、签到记录与运行日志等用户数据，不可丢弃：升级版本时必须提供显式
 * [androidx.room.migration.Migration] 并通过 [MIGRATIONS] 注册，同时在
 * androidTest 的 AppDatabaseMigrationTest 中补充升级回归用例。禁止重新引入
 * fallbackToDestructiveMigration——缺少迁移路径时应让构建/测试失败，而不是清空用户数据。
 */
@Database(
    entities = [
        AccountEntity::class,
        LogEntity::class,
        SignHistoryEntity::class,
        SignRunEntity::class,
        SignRunTaskEntity::class,
        CalendarTaskStateEntity::class,
        ScheduleExecutionSlotEntity::class,
        AccountExecutionGuardEntity::class,
        SignTaskAttemptEntity::class,
        PostRunActionEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao

    abstract fun logDao(): LogDao

    abstract fun historyDao(): HistoryDao

    abstract fun runDao(): RunDao

    abstract fun calendarDao(): CalendarDao

    abstract fun executionStateDao(): ExecutionStateDao

    abstract fun postRunActionDao(): PostRunActionDao

    companion object {
        private const val DATABASE_NAME = "1"

        /**
         * 已注册的数据库升级迁移路径。
         *
         * 每次提升数据库版本时，在此追加对应的 Migration（如 MIGRATION_1_2），
         * 并在 AppDatabaseMigrationTest 中登记同一条路径做升级回归验证。
         */
        @JvmField
        val MIGRATIONS: Array<androidx.room.migration.Migration> = emptyArray()

        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room
                    .databaseBuilder(
                        context.applicationContext,
                        AppDatabase::class.java,
                        DATABASE_NAME,
                    ).addMigrations(*MIGRATIONS)
                    // 使用 WAL 提升读写并发，减少主线程等待。
                    .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                    .setQueryExecutor(
                        java.util.concurrent.Executors
                            .newFixedThreadPool(2),
                    ).addCallback(
                        object : RoomDatabase.Callback() {
                            override fun onOpen(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                                super.onOpen(db)
                                // 调整 SQLite 运行参数，在后台写入频繁时保持更平衡的性能表现。
                                db.execSQL("PRAGMA synchronous = NORMAL")
                                db.execSQL("PRAGMA cache_size = 2000")
                                db.execSQL("PRAGMA temp_store = MEMORY")
                            }
                        },
                    ).build()
                    .also { instance = it }
            }
    }
}
