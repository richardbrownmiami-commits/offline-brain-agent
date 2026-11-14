package com.cue.offlinebrain.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import com.cue.offlinebrain.diagnostics.CrashLog

object RecognitionServiceResolver {
    fun installedServices(context: Context): List<ComponentName> {
        val intent = Intent(RecognitionService.SERVICE_INTERFACE)
        @Suppress("DEPRECATION")
        val infos = context.packageManager.queryIntentServices(intent, 0)
        return infos.mapNotNull { info -> info.serviceInfo?.let { ComponentName(it.packageName, it.name) } }
            .distinctBy { it.flattenToString() }
            .sortedWith(compareBy<ComponentName> { if (it.packageName == RecognitionServiceOrder.GOOGLE_PACKAGE) 0 else 1 }.thenBy { it.packageName }.thenBy { it.className })
    }

    fun create(context: Context): SpeechRecognizer {
        if (Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            return SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        }
        val candidates = installedServices(context)
        val preferred = candidates.firstOrNull()
        if (preferred != null) {
            try { return SpeechRecognizer.createSpeechRecognizer(context, preferred) }
            catch (error: RuntimeException) {
                CrashLog.record("VOICE", "could not create preferred recognition service ${preferred.flattenToShortString()}; using Android default", error)
            }
        }
        // Generic service selection remains supported on API 30 and as a safe final fallback.
        return SpeechRecognizer.createSpeechRecognizer(context)
    }
}
