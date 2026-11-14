package com.cue.offlinebrain.accessibility

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.view.accessibility.AccessibilityManager
import com.cue.offlinebrain.diagnostics.CrashLog

object AccessibilityStatus {
    fun isBrainServiceEnabled(context: Context): Boolean {
        return try {
            val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager ?: return false
            val expected = ComponentName(context, BrainAccessibilityService::class.java)
            manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any { info ->
                val service = info.resolveInfo?.serviceInfo ?: return@any false
                service.packageName == expected.packageName && service.name == expected.className
            }
        } catch (error: Exception) {
            CrashLog.record("ACCESSIBILITY", "enabled-service check failed", error)
            false
        }
    }
}
