package com.questtick.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Migration
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * 当前唯一受支持的数据库结构。
 *
 * 升级 schema 版本时必须同步登记 [MIGRATIONS] 中的 Migration 并导出新 schema，
 * Room 只沿已登记路径升级；缺少迁移路径时显式失败（见 [buildDatabase]），
 * 不使用破坏性回退，避免账号、签到历史等本地表被静默删除。
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

        /** 当前数据库版本；必须与 @Database(version = ...) 保持一致。 */
        const val SUPPORTED_VERSION = 1

        /**
         * 已登记的全部跨版本迁移。
         *
         * 升级到新版本时在此登记，例如：
         * ```
         * val MIGRATION_1_2 = object : Migration(1, 2) {
         *     override fun migrate(db: SupportSQLiteDatabase) { ... }
         * }
         * ```
         * 并运行 `./gradlew :app:connectedDebugAndroidTest` 中的迁移回归测试（AppDatabaseMigrationTest）验证数据保留。
         */
        val MIGRATIONS: Array<Migration> = arrayOf(
            // MIGRATION_1_2, // 下一个 schema 版本时在此登记
        )

        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: buildDatabase(context.applicationContext).also { instance = it }
            }

        /**
         * 数据库构建入口；升级回归测试复用同一配置（含迁移注册，且不含破坏性回退）。
         *
         * @param name 数据库文件名；默认使用应用固定库名，测试可传入独立名称。
         */
        internal fun createDatabaseBuilder(
            context: Context,
            name: String = DATABASE_NAME,
        ): RoomDatabase.Builder<AppDatabase> =
            Room
                .databaseBuilder(context, AppDatabase::class.java, name)
                .addMigrations(*MIGRATIONS)
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
                )

        private fun buildDatabase(context: Context): AppDatabase =
            try {
                createDatabaseBuilder(context).build()
            } catch (e: IllegalArgumentException) {
                // 存在缺少迁移路径的旧版本数据库：显式失败并保留原库文件，
                // 绝不删除本地表——账号与签到数据不可重建。
                throw IllegalStateException(
                    "本地数据库无法安全打开：缺少 Migration 路径。" +
                        "请为数据库版本升级登记 Migration 并导出 schema 后重试。",
                    e,
                )
            }
    }
}
