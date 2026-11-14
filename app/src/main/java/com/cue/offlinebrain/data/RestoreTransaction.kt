package com.cue.offlinebrain.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

class RestoreRollbackPendingException(cause: Exception) : IllegalStateException(
    "The restore failed and its rollback could not complete. Keep the app open and retry recovery.", cause
)

/** One ordered transaction boundary with rollback across two independent stores. */
object RestoreTransaction {
    suspend fun apply(
        replaceDatabase: suspend () -> Unit,
        replaceSettings: suspend () -> Unit,
        rollbackBoth: suspend () -> Unit,
        afterDatabase: suspend () -> Unit = {},
        onRollbackFailure: (Exception) -> Unit = {}
    ) {
        try {
            replaceDatabase()
            afterDatabase()
            replaceSettings()
        } catch (cancelled: CancellationException) {
            rollbackOrThrow(cancelled, rollbackBoth, onRollbackFailure)
            throw cancelled
        } catch (error: Exception) {
            rollbackOrThrow(error, rollbackBoth, onRollbackFailure)
            throw error
        }
    }

    private suspend fun rollbackOrThrow(
        original: Exception,
        rollbackBoth: suspend () -> Unit,
        onRollbackFailure: (Exception) -> Unit
    ) {
        try {
            withContext(NonCancellable) { rollbackBoth() }
        } catch (rollbackError: Exception) {
            original.addSuppressed(rollbackError)
            runCatching { onRollbackFailure(rollbackError) }
            throw RestoreRollbackPendingException(original)
        }
    }
}
