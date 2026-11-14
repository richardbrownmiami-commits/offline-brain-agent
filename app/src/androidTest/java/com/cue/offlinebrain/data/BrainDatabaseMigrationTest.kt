package com.cue.offlinebrain.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BrainDatabaseMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        BrainDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test fun version3RowsMigrateToTypedRepliesAndCurrentNotificationFamilies() {
        val name = "brain-migration-3-4"
        helper.createDatabase(name, 3).apply {
            execSQL("INSERT INTO messages (id, role, text, timestamp) VALUES (1, 'assistant', 'keep this answer', 1234)")
            insertNotification(this, 1, "com.android.server.telecom", "System")
            insertNotification(this, 2, "com.android.phone", "Other")
            insertNotification(this, 3, "com.android.incallui", "System")
            insertNotification(this, 4, "com.xiaomi.mipicks", "Other")
            insertNotification(this, 5, "com.xiaomi.discover", "Other")
            insertNotification(this, 6, "com.miui.msa.global", "Other")
            insertNotification(this, 7, "com.miui.appstore", "Other")
            insertNotification(this, 8, "com.xiaomi.finance", "Finance")
            insertNotification(this, 9, "com.google.android.apps.messages", "Other")
            close()
        }

        val migrated = helper.runMigrationsAndValidate(name, 4, true, BrainDatabase.MIGRATION_3_4)
        migrated.query("SELECT text, replyJson FROM messages WHERE id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("keep this answer", cursor.getString(0))
            assertTrue("Existing messages should start with no typed payload", cursor.isNull(1))
        }
        migrated.query("SELECT packageName, category, isRead FROM notifications ORDER BY id").use { cursor ->
            val actual = linkedMapOf<String, String>()
            val readFlags = linkedMapOf<String, Boolean>()
            while (cursor.moveToNext()) {
                actual[cursor.getString(0)] = cursor.getString(1)
                readFlags[cursor.getString(0)] = cursor.getInt(2) == 1
            }
            assertEquals("Calls", actual["com.android.server.telecom"])
            assertEquals("Calls", actual["com.android.phone"])
            assertEquals("Calls", actual["com.android.incallui"])
            assertEquals("System", actual["com.xiaomi.mipicks"])
            assertEquals("System", actual["com.xiaomi.discover"])
            assertEquals("System", actual["com.miui.msa.global"])
            assertEquals("System", actual["com.miui.appstore"])
            assertEquals("Finance", actual["com.xiaomi.finance"])
            assertEquals("Other", actual["com.google.android.apps.messages"])
            assertFalse(readFlags["com.android.server.telecom"] ?: true)
            assertTrue(readFlags["com.xiaomi.finance"] ?: false)
        }
        migrated.close()
    }

    private fun insertNotification(db: androidx.sqlite.db.SupportSQLiteDatabase, id: Long, packageName: String, category: String) {
        db.execSQL(
            "INSERT INTO notifications (id, packageName, title, text, postedAt, dedupeKey, textHash, category, isOngoing, groupName, isRead) " +
                "VALUES ($id, '$packageName', 'title-$id', 'body-$id', 1234, 'key-$id', 'hash-$id', '$category', 0, '', ${if (id % 2L == 0L) 1 else 0})"
        )
    }
}
