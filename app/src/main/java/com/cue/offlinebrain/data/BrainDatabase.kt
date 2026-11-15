package com.cue.offlinebrain.data

import android.content.Context
import android.util.Base64
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SupportFactory
import java.security.SecureRandom

@Database(
    entities = [
        MessageEntity::class, AlarmEntity::class, NoteEntity::class, NotificationEntity::class, FaqEntity::class,
        AutomationRuleEntity::class, MacroEntity::class, ClipboardEntryEntity::class, WorkerCacheEntity::class
    ],
    version = 5,
    exportSchema = true
)
abstract class BrainDatabase : RoomDatabase() {
    abstract fun dao(): BrainDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `automation_rules` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `triggerPhrase` TEXT NOT NULL, `actionText` TEXT NOT NULL, `enabled` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_automation_rules_triggerPhrase` ON `automation_rules` (`triggerPhrase`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `macros` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `steps` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_macros_updatedAt` ON `macros` (`updatedAt`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `clipboard_history` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `text` TEXT NOT NULL, `copiedAt` INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_clipboard_history_copiedAt` ON `clipboard_history` (`copiedAt`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `worker_cache` (`key` TEXT NOT NULL, `content` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`key`))")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `notifications` ADD COLUMN `dedupeKey` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `notifications` ADD COLUMN `textHash` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `notifications` ADD COLUMN `category` TEXT NOT NULL DEFAULT 'Other'")
                db.execSQL("ALTER TABLE `notifications` ADD COLUMN `isOngoing` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `notifications` ADD COLUMN `groupName` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `notifications` ADD COLUMN `isRead` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE `notifications` SET `dedupeKey` = 'legacy-' || `id`, `textHash` = 'legacy-' || `id`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_notifications_category` ON `notifications` (`category`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_notifications_dedupeKey_textHash` ON `notifications` (`dedupeKey`, `textHash`)")
            }
        }

        internal val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `replyJson` TEXT DEFAULT NULL")
                db.execSQL("""
                    UPDATE `notifications` SET `category` = 'Calls'
                    WHERE lower(`packageName`) IN (
                        'com.android.server.telecom', 'com.android.phone',
                        'com.android.incallui', 'com.android.dialer'
                    ) AND `category` IN ('System', 'Other')
                """.trimIndent())
                db.execSQL("""
                    UPDATE `notifications` SET `category` = 'System'
                    WHERE (lower(`packageName`) LIKE 'com.xiaomi.%' OR lower(`packageName`) LIKE 'com.miui.%')
                      AND `category` = 'Other'
                """.trimIndent())
            }
        }

        internal val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE `notifications_v5` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `packageName` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `text` TEXT NOT NULL,
                        `postedAt` INTEGER NOT NULL,
                        `dedupeKey` TEXT NOT NULL DEFAULT '',
                        `textHash` TEXT NOT NULL DEFAULT '',
                        `category` TEXT NOT NULL DEFAULT 'Other',
                        `isOngoing` INTEGER NOT NULL DEFAULT 0,
                        `groupName` TEXT NOT NULL DEFAULT '',
                        `isUnread` INTEGER NOT NULL DEFAULT 1,
                        `readAt` INTEGER DEFAULT NULL,
                        `deletedAt` INTEGER DEFAULT NULL
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO `notifications_v5`
                        (`id`, `packageName`, `title`, `text`, `postedAt`, `dedupeKey`, `textHash`, `category`, `isOngoing`, `groupName`, `isUnread`, `readAt`, `deletedAt`)
                    SELECT `id`, `packageName`, `title`, `text`, `postedAt`, `dedupeKey`, `textHash`, `category`, `isOngoing`, `groupName`, 0, `postedAt`, NULL
                    FROM `notifications`
                """.trimIndent())
                db.execSQL("DROP TABLE `notifications`")
                db.execSQL("ALTER TABLE `notifications_v5` RENAME TO `notifications`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_notifications_postedAt` ON `notifications` (`postedAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_notifications_packageName` ON `notifications` (`packageName`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_notifications_category` ON `notifications` (`category`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_notifications_dedupeKey_textHash` ON `notifications` (`dedupeKey`, `textHash`)")
            }
        }

        @Volatile private var instance: BrainDatabase? = null
        fun get(context: Context): BrainDatabase = instance ?: synchronized(this) {
            instance ?: build(context.applicationContext).also { instance = it }
        }

        private fun build(context: Context): BrainDatabase {
            SQLiteDatabase.loadLibs(context)
            val savedPassphrase = KeystoreSecretStore.get(context, "sqlcipher_passphrase")
                ?: KeystoreSecretStore.migrateLegacy(context, "brain_db_key", "passphrase", "sqlcipher_passphrase")
            val passphrase = savedPassphrase ?: Base64.encodeToString(ByteArray(32).also(SecureRandom()::nextBytes), Base64.NO_WRAP)
                .also { KeystoreSecretStore.put(context, "sqlcipher_passphrase", it) }
            val factory = SupportFactory(passphrase.toByteArray(Charsets.UTF_8))
            return Room.databaseBuilder(context, BrainDatabase::class.java, "offline_brain.db")
                .openHelperFactory(factory)
                .addMigrations(MIGRATION_1_2)
                .addMigrations(MIGRATION_2_3)
                .addMigrations(MIGRATION_3_4)
                .addMigrations(MIGRATION_4_5)
                .fallbackToDestructiveMigration(false)
                .build()
        }
    }
}
