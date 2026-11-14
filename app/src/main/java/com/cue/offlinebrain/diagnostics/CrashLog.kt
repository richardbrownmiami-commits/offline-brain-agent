package com.cue.offlinebrain.diagnostics

import android.content.Context
import android.content.ClipData
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Small bounded log for app/system failures. Chat contents, credentials, and API headers are never written here. */
object CrashLog {
    private const val TAG = "OfflineBrain"
    private const val MAX_BYTES = 512 * 1024L
    private const val KEEP_BYTES = 320 * 1024
    private val lock = Any()
    @Volatile private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            record("UNCAUGHT", "thread=${thread.name}", error)
            runCatching { previous?.uncaughtException(thread, error) }
        }
        record("APP", "diagnostic logger ready")
    }

    fun record(stage: String, message: String, error: Throwable? = null) {
        val safeStage = stage.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(32)
        val line = buildString {
            append(timestamp()).append(' ').append(safeStage).append(' ').append(message.take(500))
            if (error != null) append("\n").append(error.stackTraceToString().take(16000))
            append('\n')
        }
        if (error == null) Log.i(TAG, "$safeStage: $message") else Log.e(TAG, "$safeStage: $message", error)
        val ctx = appContext ?: return
        runCatching {
            synchronized(lock) {
                val file = logFile(ctx)
                FileOutputStream(file, true).bufferedWriter().use { it.write(line) }
                if (file.length() > MAX_BYTES) {
                    val bytes = file.readBytes()
                    val from = (bytes.size - KEEP_BYTES).coerceAtLeast(0)
                    FileOutputStream(file, false).use { it.write(bytes, from, bytes.size - from) }
                }
            }
        }.onFailure { Log.e(TAG, "Could not append diagnostics log", it) }
    }

    fun logFile(context: Context): File {
        val root = context.applicationContext.filesDir
        val directory = File(root, "diagnostics")
        val target = File(directory, "diagnostics.log")
        runCatching {
            if (!directory.exists() && !directory.mkdirs()) error("Could not create diagnostics directory")
            val legacy = File(root, "diagnostics.log")
            if (legacy.isFile && !target.exists()) {
                if (!legacy.renameTo(target)) {
                    legacy.copyTo(target, overwrite = false)
                    legacy.delete()
                }
            }
        }.onFailure { Log.w(TAG, "Could not prepare diagnostics log directory", it) }
        return target
    }

    fun readText(context: Context): String = runCatching {
        logFile(context).takeIf { it.exists() }?.readText()?.takeLast(320_000)
            ?: "No diagnostic events have been recorded yet."
    }.getOrElse { "Could not read the diagnostic log: ${it.message ?: it.javaClass.simpleName}" }

    fun shareIntent(context: Context): android.content.Intent {
        val file = logFile(context)
        if (!file.exists()) file.writeText("No diagnostic events have been recorded.\n")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        return android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            putExtra(android.content.Intent.EXTRA_SUBJECT, "Offline Brain diagnostics log")
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newUri(context.contentResolver, "Offline Brain diagnostics log", uri)
        }
    }

    private fun timestamp(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date())
}
