package com.cue.offlinebrain.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import com.cue.offlinebrain.diagnostics.CrashLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

/** Downloads only after a user taps the microphone. Model and recognizer live only during recording. */
class VoskSpeechController(context: Context) {
    private val appContext = context.applicationContext
    private val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).callTimeout(30, TimeUnit.MINUTES).build()
    fun modelDirectory(): File = File(appContext.filesDir, MODEL_DIRECTORY)

    suspend fun ensureModel(onProgress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        try {
            val modelDir = modelDirectory()
            if (isValidModel(modelDir)) { onProgress(100); return@withContext modelDir }
            val archive = File(appContext.filesDir, "$MODEL_DIRECTORY.zip.part")
            val offset = if (archive.isFile) archive.length() else 0L
            val request = Request.Builder().url(MODEL_URL).apply { if (offset > 0) header("Range", "bytes=$offset-") }.build()
            CrashLog.record("VOICE", "user-started Vosk model download offset=$offset")
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful && response.code != 206) throw IllegalStateException("Model download returned HTTP ${response.code}")
                val append = offset > 0 && response.code == 206
                val body = response.body ?: throw IllegalStateException("Model download returned no data")
                val total = response.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull() ?: if (body.contentLength() > 0) body.contentLength() + if (append) offset else 0 else -1L
                var transferred = if (append) offset else 0L
                body.byteStream().use { input -> FileOutputStream(archive, append).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) { coroutineContext.ensureActive(); val count = input.read(buffer); if (count < 0) break; output.write(buffer, 0, count); transferred += count; if (total > 0) onProgress(((transferred * 100) / total).toInt().coerceIn(0, 99)) }
                    output.fd.sync()
                } }
            }
            if (archive.length() < 1_000_000L) throw IllegalStateException("Downloaded model archive is unexpectedly small")
            val staging = File(appContext.filesDir, "$MODEL_DIRECTORY.installing"); staging.deleteRecursively()
            if (!staging.mkdirs()) throw IllegalStateException("Could not create model directory")
            try {
                ZipInputStream(archive.inputStream().buffered()).use { zip ->
                    var entry = zip.nextEntry; val buffer = ByteArray(32 * 1024)
                    while (entry != null) {
                        coroutineContext.ensureActive(); val name = entry.name.replace('\\', '/'); val relative = name.removePrefix("$MODEL_DIRECTORY/")
                        if (relative.isNotBlank() && !relative.startsWith("/") && relative.split('/').none { it == ".." }) {
                            val target = File(staging, relative); val root = staging.canonicalPath + File.separator
                            if (!target.canonicalPath.startsWith(root)) throw IllegalStateException("Unsafe path inside Vosk archive")
                            if (entry.isDirectory) target.mkdirs() else { target.parentFile?.mkdirs(); FileOutputStream(target).use { output -> while (true) { coroutineContext.ensureActive(); val n = zip.read(buffer); if (n < 0) break; output.write(buffer, 0, n) } } }
                        }
                        zip.closeEntry(); entry = zip.nextEntry
                    }
                }
                if (!isValidModel(staging)) throw IllegalStateException("Downloaded archive does not contain expected Vosk English model files")
                modelDir.deleteRecursively(); if (!staging.renameTo(modelDir)) throw IllegalStateException("Could not finalize downloaded Vosk model")
                archive.delete(); onProgress(100); CrashLog.record("VOICE", "Vosk English model installed"); modelDir
            } catch (e: CancellationException) { staging.deleteRecursively(); throw e }
            catch (e: Exception) { staging.deleteRecursively(); CrashLog.record("VOICE", "Vosk model extraction failed", e); throw e }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { CrashLog.record("VOICE", "Vosk model download or install failed", e); throw e }
    }

    suspend fun listen(modelDirectory: File, stop: Deferred<Unit>, onStarted: () -> Unit): String = withContext(Dispatchers.IO) {
        if (appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            val e = SecurityException("Microphone permission is missing. Allow Microphone in app settings, then retry."); CrashLog.record("VOICE", "Vosk microphone permission missing", e); throw e
        }
        var model: Model? = null; var decoder: Recognizer? = null; var audio: AudioRecord? = null
        var focusRequest: AudioFocusRequest? = null; var focusHeld = false
        try {
            val manager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            focusHeld = if (Build.VERSION.SDK_INT >= 26) {
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()).setOnAudioFocusChangeListener { }.build()
                focusRequest = req; manager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            } else @Suppress("DEPRECATION") (manager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            if (!focusHeld) throw IllegalStateException("Audio focus is unavailable. Stop other audio playback and try again.")
            try {
                model = Model(modelDirectory.absolutePath)
                val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                if (minBuffer <= 0) throw IllegalStateException("Android could not configure the microphone. Check microphone access and retry.")
                val bufferSize = maxOf(minBuffer, SAMPLE_RATE / 5 * 2)
                audio = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize)
                if (audio?.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("Microphone recorder did not initialize. Check microphone permission and close other recording apps.")
                decoder = Recognizer(model, SAMPLE_RATE.toFloat())
                audio!!.startRecording()
                if (audio!!.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw IllegalStateException("Microphone did not start. Check microphone permission and retry.")
                onStarted(); SpeechDiagnostics.record("VOSK", null, "Listening."); CrashLog.record("VOICE", "Vosk microphone recording started")
                val pcm = ByteArray(bufferSize.coerceAtMost(SAMPLE_RATE * 2)); var lastFinal = ""
                while (!stop.isCompleted) {
                    coroutineContext.ensureActive(); val count = audio!!.read(pcm, 0, pcm.size)
                    if (count > 0 && decoder!!.acceptWaveForm(pcm, count)) { val phrase = parseText(decoder!!.result); if (phrase.isNotBlank()) lastFinal = phrase }
                    else if (count < 0 && !stop.isCompleted) throw IllegalStateException("Microphone read failed (code $count). Check microphone permission and close other recording apps.")
                }
                val finalText = parseText(decoder!!.finalResult); (finalText.ifBlank { lastFinal }).trim()
            } catch (e: OutOfMemoryError) { CrashLog.record("VOICE", "Vosk model/recognizer/audio allocation ran out of memory", e); throw IllegalStateException("Offline speech needs more memory than is currently available. Close other apps and retry, or use Android speech.", e) }
        } catch (e: CancellationException) { SpeechDiagnostics.record("VOSK", null, "Cancelled."); CrashLog.record("VOICE", "Vosk listening cancelled"); throw e }
        catch (e: OutOfMemoryError) { SpeechDiagnostics.record("VOSK", null, "Error: offline speech needs more memory. Close other apps and retry."); CrashLog.record("VOICE", "Vosk allocation ran out of memory", e); throw IllegalStateException("Offline speech needs more memory than is currently available. Close other apps and retry.", e) }
        catch (e: Exception) { SpeechDiagnostics.record("VOSK", null, "Error: offline speech failed; check model files and microphone permission."); CrashLog.record("VOICE", "Vosk listening failed", e); throw e }
        finally {
            runCatching { audio?.stop() }.onFailure { CrashLog.record("VOICE", "microphone stop failed", it) }
            runCatching { audio?.release() }.onFailure { CrashLog.record("VOICE", "microphone release failed", it) }
            runCatching { decoder?.close() }.onFailure { CrashLog.record("VOICE", "Vosk recognizer release failed", it) }
            runCatching { model?.close() }.onFailure { CrashLog.record("VOICE", "Vosk model release failed", it) }
            try { val manager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager; if (focusHeld) { if (Build.VERSION.SDK_INT >= 26) focusRequest?.let { manager.abandonAudioFocusRequest(it) } else @Suppress("DEPRECATION") manager.abandonAudioFocus(null) } }
            catch (e: Exception) { CrashLog.record("VOICE", "Vosk audio focus abandon failed", e) }
            CrashLog.record("VOICE", "Vosk recording resources released")
        }
    }
    private fun parseText(json: String): String = runCatching { JSONObject(json).optString("text").trim() }.getOrDefault("")
    private fun isValidModel(directory: File): Boolean = File(directory, "am/final.mdl").isFile && File(directory, "conf/model.conf").isFile && File(directory, "graph/Gr.fst").isFile
    companion object { private const val SAMPLE_RATE = 16_000; private const val MODEL_DIRECTORY = "vosk-model-small-en-us-0.15"; private const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip" }
}
