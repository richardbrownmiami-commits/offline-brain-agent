package com.cue.offlinebrain.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RestoreTransactionTest {
    @Test fun failureAfterDatabaseReplacementRestoresDatabaseAndSettings() = runBlocking {
        val oldDatabase = listOf("old row")
        val oldSettings = mapOf("city" to "Delhi", "lock" to "on")
        var database = oldDatabase
        var settings = oldSettings
        var failure: Exception? = null

        try {
            RestoreTransaction.apply(
                replaceDatabase = { database = listOf("new row") },
                replaceSettings = { settings = mapOf("city" to "Mumbai", "lock" to "off") },
                rollbackBoth = { database = oldDatabase; settings = oldSettings },
                afterDatabase = { throw IllegalStateException("injected after database stage") },
                onRollbackFailure = { failure = it }
            )
            throw AssertionError("The injected restore failure should be returned to the caller")
        } catch (error: IllegalStateException) {
            assertEquals("injected after database stage", error.message)
        }
        assertEquals(oldDatabase, database)
        assertEquals(oldSettings, settings)
        assertEquals(null, failure)
    }

    @Test fun rollbackFailureIsSurfacedAsPendingRecovery() = runBlocking {
        try {
            RestoreTransaction.apply(
                replaceDatabase = { throw IllegalStateException("database stage failed") },
                replaceSettings = {},
                rollbackBoth = { throw IllegalStateException("rollback failed") }
            )
            throw AssertionError("Rollback failure must not be swallowed")
        } catch (error: RestoreRollbackPendingException) {
            assertTrue(error.message.orEmpty().contains("rollback", ignoreCase = true))
            assertEquals(1, error.cause?.suppressed?.size)
        }
    }
}
