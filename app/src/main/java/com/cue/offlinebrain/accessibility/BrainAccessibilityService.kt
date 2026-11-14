package com.cue.offlinebrain.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Path
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.cue.offlinebrain.diagnostics.CrashLog
import com.cue.offlinebrain.engine.CommandActionKind
import com.cue.offlinebrain.engine.CommandPlan
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.Locale
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

object DeviceActions {
    @Volatile internal var service: BrainAccessibilityService? = null
    fun available() = service != null
    fun execute(command: String): String = runCatching {
        service?.executeCommand(command) ?: "To use screen actions, enable Offline Brain Agent in Android Accessibility settings."
    }.getOrElse { error ->
        CrashLog.record("ACCESSIBILITY", "command dispatch failed", error)
        "The accessibility action failed safely. Open Diagnostics for details."
    }
    fun testScreenshot(onResult: (String) -> Unit) {
        val current = service
        if (current == null) { onResult("FAIL — accessibility service is not bound."); return }
        current.requestScreenshotTest(onResult)
    }

    suspend fun executeCompound(plan: CommandPlan, onStep: (String) -> Unit): String {
        val current = service ?: return "To run screen actions, enable Offline Brain Agent in Android Accessibility settings. Nothing was run."
        return current.executeCompound(plan, onStep)
    }
}

class BrainAccessibilityService : AccessibilityService() {
    private val worker = Executors.newSingleThreadExecutor()
    private val macroCaptureHandler = Handler(Looper.getMainLooper())
    private val pendingMacroText = mutableMapOf<String, String>()
    private val pendingMacroCallbacks = mutableMapOf<String, Runnable>()
    private var lastMacroAppPackage: String? = null
    private val recognizerLock = Any()
    @Volatile private var textRecognizer: TextRecognizer? = null

    /** Called from the screenshot worker, never from the UI thread. */
    private fun recognizerOnWorker(): TextRecognizer = synchronized(recognizerLock) {
        textRecognizer ?: TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).also { textRecognizer = it }
    }

    override fun onServiceConnected() {
        try {
            super.onServiceConnected()
            DeviceActions.service = this
            CrashLog.record("ACCESSIBILITY", "service connected")
        } catch (error: Throwable) {
            CrashLog.record("ACCESSIBILITY", "onServiceConnected failed", error)
            DeviceActions.service = null
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            // Event text is ignored unless the user explicitly started a macro recording.
            if (!MacroCaptureSession.isActive) { lastMacroAppPackage = null; return }
            if (event == null) return
            val pkg = event.packageName?.toString().orEmpty()
            if (!shouldCaptureMacroPackage(pkg)) return
            when (event.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    if (lastMacroAppPackage != pkg) {
                        lastMacroAppPackage = pkg
                        val label = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
                        MacroCaptureSession.append("Open ${label.take(80)}")
                    }
                }
                AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                    val label = (event.text.joinToString(" ").ifBlank { event.contentDescription?.toString().orEmpty() }).trim()
                    if (label.isNotBlank() && label.length <= 100) MacroCaptureSession.append("Tap $label")
                }
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> queueMacroText(event, pkg)
            }
        } catch (error: Throwable) { CrashLog.record("ACCESSIBILITY", "onAccessibilityEvent failed", error) }
    }

    private fun shouldCaptureMacroPackage(pkg: String): Boolean {
        val lower = pkg.lowercase(Locale.ROOT)
        return lower.isNotBlank() && lower != packageName &&
            !lower.startsWith("com.android.") && !lower.startsWith("com.miui.") &&
            !lower.contains("inputmethod") && !lower.contains("keyboard") && !lower.contains("gboard")
    }

    private fun queueMacroText(event: AccessibilityEvent, pkg: String) {
        val node = runCatching { event.source }.getOrNull() ?: return
        try {
            if (node.isPassword) return
            val current = event.text.firstOrNull()?.toString()?.take(160).orEmpty()
            if (current.isBlank()) return
            val before = event.beforeText?.toString()?.take(160).orEmpty()
            val added = if (event.addedCount in 1..100 && event.fromIndex in 0..current.length && event.addedCount <= current.length - event.fromIndex)
                current.substring(event.fromIndex, event.fromIndex + event.addedCount)
            else if (current.startsWith(before)) current.removePrefix(before)
            else return
            if (added.isBlank()) return
            val key = "$pkg:${node.viewIdResourceName ?: node.className?.toString().orEmpty()}"
            pendingMacroText[key] = (pendingMacroText[key].orEmpty() + added).take(100)
            pendingMacroCallbacks.remove(key)?.let(macroCaptureHandler::removeCallbacks)
            val callback = Runnable {
                val typed = pendingMacroText.remove(key).orEmpty()
                pendingMacroCallbacks.remove(key)
                if (MacroCaptureSession.isActive && typed.isNotBlank()) MacroCaptureSession.append("Type $typed")
            }
            pendingMacroCallbacks[key] = callback
            macroCaptureHandler.postDelayed(callback, 750L)
        } finally { runCatching { node.recycle() } }
    }

    override fun onInterrupt() { CrashLog.record("ACCESSIBILITY", "service interrupted") }

    override fun onUnbind(intent: Intent?): Boolean {
        try {
            if (DeviceActions.service === this) DeviceActions.service = null
            CrashLog.record("ACCESSIBILITY", "service unbound")
        } catch (error: Throwable) { CrashLog.record("ACCESSIBILITY", "onUnbind failed", error) }
        return try { super.onUnbind(intent) } catch (error: Throwable) {
            CrashLog.record("ACCESSIBILITY", "super.onUnbind failed", error)
            false
        }
    }

    override fun onDestroy() {
        pendingMacroCallbacks.values.forEach { macroCaptureHandler.removeCallbacks(it) }
        pendingMacroCallbacks.clear()
        pendingMacroText.clear()
        try { if (DeviceActions.service === this) DeviceActions.service = null } catch (error: Throwable) { CrashLog.record("ACCESSIBILITY", "clear service reference failed", error) }
        runCatching { worker.shutdown() }.onFailure { CrashLog.record("ACCESSIBILITY", "worker shutdown failed", it) }
        synchronized(recognizerLock) {
            runCatching { textRecognizer?.close() }.onFailure { CrashLog.record("ACCESSIBILITY", "OCR recognizer close failed", it) }
            textRecognizer = null
        }
        CrashLog.record("ACCESSIBILITY", "service destroyed")
        try { super.onDestroy() } catch (error: Throwable) { CrashLog.record("ACCESSIBILITY", "super.onDestroy failed", error) }
    }

    internal fun executeCommand(command: String): String = try {
        val lower = command.trim().lowercase(Locale.ROOT)
        when {
            lower.startsWith("open ") || lower.startsWith("launch ") -> openApp(command.substringAfter(' '))
            lower == "back" || lower == "go back" -> if (performGlobalAction(GLOBAL_ACTION_BACK)) "Went back." else "Back action wasn't available."
            lower == "home" || lower == "press home" -> if (performGlobalAction(GLOBAL_ACTION_HOME)) "Went to the home screen." else "Home action wasn't available."
            lower == "recents" || lower == "recent apps" -> if (performGlobalAction(GLOBAL_ACTION_RECENTS)) "Opened recent apps." else "Recent apps weren't available."
            lower.startsWith("scroll down") -> scroll(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            lower.startsWith("scroll up") -> scroll(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            lower.startsWith("tap at ") -> tapCoordinates(command.substringAfter("tap at ").trim())
            lower.startsWith("tap ") || lower.startsWith("click ") -> tapText(command.substringAfter(' '))
            lower.startsWith("type ") -> typeText(command.substringAfter(' '))
            else -> "Try: open [app], tap [visible text], type [text], scroll down, back, home, or recents."
        }
    } catch (error: Throwable) {
        CrashLog.record("ACCESSIBILITY", "command execution crashed safely", error)
        "That screen action could not be completed. Check Settings → Diagnostics."
    }

    internal suspend fun executeCompound(plan: CommandPlan, onStep: (String) -> Unit): String {
        val steps = plan.actions
        if (steps.isEmpty()) return "I couldn't find any steps to run."
        for ((index, action) in steps.withIndex()) {
            currentCoroutineContext().ensureActive()
            val number = index + 1
            onStep("Step $number of ${steps.size}: ${action.description}")
            val failure: String? = try {
                when (action.kind) {
                    CommandActionKind.OPEN_APP -> withContext(Dispatchers.Main.immediate) {
                        val launch = packageManager.getLaunchIntentForPackage(action.packageName)
                            ?: return@withContext "That app no longer has a launch button."
                        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(launch)
                        null
                    }
                    CommandActionKind.WEB_SEARCH -> withContext(Dispatchers.Main.immediate) {
                        val imageSearch = Regex("(?i)^images?\\b").containsMatchIn(action.value.trim())
                        val uri = Uri.parse("https://www.google.com/search").buildUpon()
                            .appendQueryParameter("q", action.value)
                            .apply { if (imageSearch) appendQueryParameter("tbm", "isch") }
                            .build()
                        val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        if (action.packageName.isNotBlank()) intent.setPackage(action.packageName)
                        if (intent.resolveActivity(packageManager) == null) "No browser app can open this search."
                        else { startActivity(intent); null }
                    }
                    CommandActionKind.IN_APP_SEARCH -> searchInApp(action.packageName, action.value)
                    CommandActionKind.TYPE_TEXT -> withContext(Dispatchers.Main.immediate) {
                        val result = typeText(action.value)
                        if (result.startsWith("Typed") || result.startsWith("Pasted")) null else result
                    }
                    CommandActionKind.TAP_TEXT -> withContext(Dispatchers.Main.immediate) {
                        val result = tapText(action.value)
                        if (result.startsWith("Tapped") || result.startsWith("Reading the current screen")) null else result
                    }
                    CommandActionKind.SCROLL -> withContext(Dispatchers.Main.immediate) {
                        val direction = if (action.value.endsWith("up")) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                        val result = scroll(direction)
                        if (result.startsWith("Scrolled")) null else result
                    }
                    CommandActionKind.BACK -> withContext(Dispatchers.Main.immediate) { if (performGlobalAction(GLOBAL_ACTION_BACK)) null else "Android did not allow the Back action." }
                    CommandActionKind.HOME -> withContext(Dispatchers.Main.immediate) { if (performGlobalAction(GLOBAL_ACTION_HOME)) null else "Android did not allow the Home action." }
                    CommandActionKind.SETTINGS_PAGE -> withContext(Dispatchers.Main.immediate) {
                        val actionName = when (action.value) {
                            "wifi" -> Settings.ACTION_WIFI_SETTINGS
                            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
                            else -> Settings.ACTION_DISPLAY_SETTINGS
                        }
                        val intent = Intent(actionName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        if (intent.resolveActivity(packageManager) == null) "Android has no settings page for that option."
                        else { startActivity(intent); null }
                    }
                    CommandActionKind.DIAL -> withContext(Dispatchers.Main.immediate) {
                        val number = action.value.trim()
                        if (number.isBlank()) "I need a phone number or contact name before opening the dialer."
                        else { startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); null }
                    }
                    CommandActionKind.PLAY -> withContext(Dispatchers.Main.immediate) {
                        val intent = Intent("android.media.action.MEDIA_PLAY_FROM_SEARCH").putExtra("query", action.value).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        if (intent.resolveActivity(packageManager) == null) "No installed app accepted the play request."
                        else { startActivity(intent); null }
                    }
                    CommandActionKind.SEND -> withContext(Dispatchers.Main.immediate) {
                        if (action.value.isBlank()) "Say what message to prepare."
                        else {
                            val share = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, action.value)
                            startActivity(Intent.createChooser(share, "Choose an app to send this text").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            null
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                CrashLog.record("COMMAND", "compound action failed at step $number (${action.kind})", error)
                error.message?.takeIf(String::isNotBlank) ?: "Android rejected this action."
            }
            if (failure != null) return "I stopped at step $number of ${steps.size}: $failure No later steps were run."
        }
        return "Done. I finished all ${steps.size} step(s)."
    }

    private suspend fun searchInApp(packageName: String, query: String): String? {
        if (packageName.isBlank() || query.isBlank()) return "The app or search words are missing."
        val deadline = SystemClock.elapsedRealtime() + 10_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            currentCoroutineContext().ensureActive()
            val foreground = withContext(Dispatchers.Main.immediate) { rootInActiveWindow?.packageName?.toString() }
            if (foreground == packageName) break
            delay(200L)
        }
        val foreground = withContext(Dispatchers.Main.immediate) { rootInActiveWindow?.packageName?.toString() }
        if (foreground != packageName) return "${packageName.substringAfterLast('.')} did not come to the foreground within 10 seconds."
        val field = withContext(Dispatchers.Main.immediate) { findSearchField() }
            ?: return "I couldn't find a visible search box in that app. I did not type anywhere."
        return try {
            val success = withContext(Dispatchers.Main.immediate) {
                val textSet = field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, query)
                })
                textSet && field.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            }
            if (success) null else "The app's search box did not accept the words or Enter key."
        } finally {
            withContext(Dispatchers.Main.immediate) {
                try { field.recycle() } catch (error: Exception) { CrashLog.record("COMMAND", "search field node could not be released", error) }
            }
        }
    }

    private fun findSearchField(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val pending = java.util.ArrayDeque<AccessibilityNodeInfo>()
        pending.addLast(root)
        var rootReleased = false
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst()
            val label = listOfNotNull(node.hintText?.toString(), node.contentDescription?.toString(), node.text?.toString(), node.viewIdResourceName)
                .joinToString(" ").lowercase(Locale.ROOT)
            if (node.isVisibleToUser && node.isEditable && "search" in label) {
                while (pending.isNotEmpty()) {
                    try { pending.removeFirst().recycle() } catch (error: Exception) { CrashLog.record("COMMAND", "unused search node could not be released", error) }
                }
                if (node !== root && !rootReleased) try { root.recycle() } catch (error: Exception) { CrashLog.record("COMMAND", "root search node could not be released", error) }
                return node
            }
            for (childIndex in 0 until node.childCount) node.getChild(childIndex)?.let(pending::addLast)
            if (node === root) rootReleased = true
            try { node.recycle() } catch (error: Exception) { CrashLog.record("COMMAND", "accessibility node could not be released", error) }
        }
        return null
    }

    private fun openApp(name: String): String {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val matches = packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        val app = matches.firstOrNull { runCatching { it.loadLabel(packageManager).toString().contains(name, true) }.getOrDefault(false) }
            ?: return "I couldn't find an installed app named “$name”."
        val launch = packageManager.getLaunchIntentForPackage(app.activityInfo.packageName) ?: return "That app doesn't expose a launch action."
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(launch)
        return "Opening ${app.loadLabel(packageManager)}."
    }

    private fun tapText(text: String): String {
        val root = rootInActiveWindow ?: return "I can't inspect the current screen."
        val nodes = root.findAccessibilityNodeInfosByText(text)
        val target = nodes.firstOrNull { it.isVisibleToUser } ?: return captureAndTap(text)
        var clickable = target
        while (clickable.parent != null && !clickable.isClickable) clickable = clickable.parent
        val ok = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        return if (ok) "Tapped “$text”." else captureAndTap(text)
    }

    private fun typeText(text: String): String {
        val root = rootInActiveWindow ?: return "I can't inspect the current screen."
        val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return "Select a text box first, then ask me to type."
        if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) })) return "Typed the requested text."
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return "Clipboard service isn't available."
        clipboard.setPrimaryClip(ClipData.newPlainText("Offline Brain", text))
        val pasted = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        runCatching { clipboard.clearPrimaryClip() }
        return if (pasted) "Pasted the requested text." else "This text box did not allow typing."
    }

    private fun scroll(action: Int): String {
        val root = rootInActiveWindow ?: return "I can't inspect the current screen."
        fun findScrollable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isScrollable) return node
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                val match = findScrollable(child)
                if (match != null) return match
            }
            return null
        }
        val target = findScrollable(root)
        return if (target?.performAction(action) == true) "Scrolled the screen." else "No scrollable area was found."
    }

    @SuppressLint("NewApi")
    private fun tapCoordinates(value: String): String {
        if (Build.VERSION.SDK_INT < 24) return "Coordinate taps need Android 7 or newer."
        val parts = value.split(',').mapNotNull { it.trim().toFloatOrNull() }
        val x = parts.getOrNull(0) ?: return "Use coordinates like ‘tap at 300,600’."
        val y = parts.getOrNull(1) ?: return "Use coordinates like ‘tap at 300,600’."
        if (parts.size != 2) return "Use coordinates like ‘tap at 300,600’."
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 80)).build()
        return if (dispatchGesture(gesture, null, null)) "Tapped at ${x.toInt()}, ${y.toInt()}." else "The coordinate tap was rejected."
    }

    @SuppressLint("NewApi")
    private fun captureAndTap(text: String): String {
        if (Build.VERSION.SDK_INT < 30) return "That text isn't exposed in the screen tree, and screenshot reading needs Android 11+."
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, worker, object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val buffer = screenshot.hardwareBuffer
                    try {
                        val hardwareBitmap = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                        val bitmap = hardwareBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                        hardwareBitmap?.recycle()
                        if (bitmap == null) { CrashLog.record("SCREENSHOT", "capture returned no bitmap"); return }
                        val image = InputImage.fromBitmap(bitmap, 0)
                        recognizerOnWorker().process(image).addOnSuccessListener(worker) { found ->
                            try {
                                val line = found.textBlocks.flatMap { it.lines }.firstOrNull { it.text.contains(text, true) }
                                val box = line?.boundingBox
                                if (box != null) {
                                    val dm = resources.displayMetrics
                                    val x = box.exactCenterX() * dm.widthPixels / bitmap.width
                                    val y = box.exactCenterY() * dm.heightPixels / bitmap.height
                                    val path = Path().apply { moveTo(x, y) }
                                    val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 80)).build()
                                    if (!dispatchGesture(gesture, null, null)) CrashLog.record("SCREENSHOT", "OCR matched text but tap gesture was rejected")
                                    else CrashLog.record("SCREENSHOT", "OCR matched text and dispatched density-scaled tap")
                                } else CrashLog.record("SCREENSHOT", "OCR completed but target text was not found")
                            } catch (error: Exception) { CrashLog.record("SCREENSHOT", "OCR result/tap failed", error) }
                            finally { bitmap.recycle() }
                        }.addOnFailureListener(worker) { error ->
                            bitmap.recycle(); CrashLog.record("SCREENSHOT", "OCR failed", error)
                        }
                    } catch (error: Exception) { CrashLog.record("SCREENSHOT", "screenshot bitmap conversion failed", error) }
                    finally { runCatching { buffer.close() }.onFailure { CrashLog.record("SCREENSHOT", "hardware buffer close failed", it) } }
                }
                override fun onFailure(errorCode: Int) { CrashLog.record("SCREENSHOT", "capture failed: ${screenshotFailureReason(errorCode)} (code=$errorCode)") }
            })
            CrashLog.record("SCREENSHOT", "capture requested for OCR tap")
            return "Reading the current screen and looking for “$text”. Secure screens and system restrictions cannot be read."
        } catch (error: Exception) {
            CrashLog.record("SCREENSHOT", "capture request failed", error)
            return "Screenshot capture failed: ${error.message ?: "system error"}. Open Diagnostics for details."
        }
    }

    @SuppressLint("NewApi")
    internal fun requestScreenshotTest(onResult: (String) -> Unit) {
        if (Build.VERSION.SDK_INT < 30) { onResult("FAIL — Android 11/API 30 or newer is required."); return }
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, worker, object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    runCatching { screenshot.hardwareBuffer.close() }
                    CrashLog.record("DIAGNOSTIC", "screenshot capture PASS")
                    onResult("PASS — Android returned a screenshot for the current window.")
                }
                override fun onFailure(errorCode: Int) {
                    val reason = screenshotFailureReason(errorCode)
                    CrashLog.record("DIAGNOSTIC", "screenshot capture FAIL: $reason code=$errorCode")
                    onResult("FAIL — $reason (Android screenshot API error code=$errorCode).")
                }
            })
        } catch (error: Exception) {
            CrashLog.record("DIAGNOSTIC", "screenshot capability test failed", error)
            onResult("FAIL — ${error.message ?: "screenshot request was rejected"}")
        }
    }

    private fun screenshotFailureReason(code: Int): String = when (code) {
        ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR -> "the system screenshot service reported an internal error"
        ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "screenshot is rate-limited; wait before retrying"
        ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> "the requested display is no longer available"
        ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "accessibility screenshot capability is not available"
        ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> "the current screen is marked secure and cannot be captured"
        else -> "the system rejected the screenshot request"
    }
}
