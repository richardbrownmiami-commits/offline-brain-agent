package com.cue.offlinebrain.notifications

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import com.cue.offlinebrain.diagnostics.CrashLog
import java.util.LinkedHashMap

/** Bounded label/icon cache; a missing/hidden package is logged once, without a stack trace. */
object PackageLabelResolver {
    private const val MAX_ENTRIES = 256
    private val labels = LinkedHashMap<String, String>(32, 0.75f, true)
    private val missingLogged = LinkedHashSet<String>()

    @Synchronized
    fun label(context: Context, packageName: String): String {
        val pkg = packageName.trim().take(180)
        if (pkg.isBlank()) return "Unknown app"
        labels[pkg]?.let { return it }
        val value = try {
            val app = context.applicationContext.packageManager.getApplicationInfo(pkg, 0)
            context.applicationContext.packageManager.getApplicationLabel(app).toString().trim().take(100)
                .ifBlank { fallback(pkg) }
        } catch (_: PackageManager.NameNotFoundException) {
            logMissingOnce(pkg)
            fallback(pkg)
        } catch (error: Exception) {
            CrashLog.record("PACKAGE", "app label lookup failed for package=${safe(pkg)}", error)
            fallback(pkg)
        }
        labels[pkg] = value
        trimCache()
        return value
    }

    @Synchronized
    fun icon(context: Context, packageName: String): Drawable? {
        val pkg = packageName.trim().take(180)
        if (pkg.isBlank()) return null
        return try {
            context.applicationContext.packageManager.getApplicationIcon(pkg)
        } catch (_: PackageManager.NameNotFoundException) {
            logMissingOnce(pkg)
            null
        } catch (error: Exception) {
            CrashLog.record("PACKAGE", "app icon lookup failed for package=${safe(pkg)}", error)
            null
        }
    }

    @Synchronized
    fun clearForTests() { labels.clear(); missingLogged.clear() }

    private fun logMissingOnce(pkg: String) {
        if (missingLogged.add(pkg)) {
            CrashLog.record("PACKAGE", "app package is hidden, removed, or unavailable: ${safe(pkg)}")
            while (missingLogged.size > MAX_ENTRIES) missingLogged.remove(missingLogged.first())
        }
    }

    private fun fallback(pkg: String): String {
        val known = when (pkg) {
            "com.android.incallui" -> "Phone calls"
            "com.cue.diagnostic" -> "Diagnostic app"
            "com.sgiggle.production" -> "Tango"
            else -> null
        }
        return known ?: pkg.substringAfterLast('.').replace('_', ' ').replaceFirstChar { it.uppercase() }.take(100)
    }

    private fun trimCache() { while (labels.size > MAX_ENTRIES) labels.remove(labels.entries.firstOrNull()?.key ?: break) }
    private fun safe(value: String) = value.replace(Regex("[^A-Za-z0-9._-]"), "_").take(180)
}
