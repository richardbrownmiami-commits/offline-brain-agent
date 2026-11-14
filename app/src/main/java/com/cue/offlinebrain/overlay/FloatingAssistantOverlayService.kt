package com.cue.offlinebrain.overlay

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.runtime.R
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.cue.offlinebrain.MainActivity
import com.cue.offlinebrain.OfflineBrainApplication
import com.cue.offlinebrain.data.BrainRepository
import com.cue.offlinebrain.diagnostics.CrashLog
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

/** Immutable status snapshot safe to read from diagnostics without touching service state. */
data class OverlayDiagnosticsSnapshot(val permissionGranted: Boolean, val serviceRunning: Boolean, val viewAttached: Boolean, val lastError: String?, val updatedAt: Long)

object OverlayDiagnostics {
    @Volatile private var current = OverlayDiagnosticsSnapshot(false, false, false, null, 0L)
    @JvmStatic fun snapshot(): OverlayDiagnosticsSnapshot = current
    internal fun update(permissionGranted: Boolean = current.permissionGranted, serviceRunning: Boolean = current.serviceRunning, viewAttached: Boolean = current.viewAttached, lastError: String? = current.lastError) {
        current = OverlayDiagnosticsSnapshot(permissionGranted, serviceRunning, viewAttached, lastError, System.currentTimeMillis())
    }
}

class FloatingAssistantOverlayService : Service(), LifecycleOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    private val savedStateController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry
    private var windowManager: WindowManager? = null
    private var overlayView: ComposeView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var commandJob: Job? = null
    private var testJob: Job? = null
    private var testRun = false
    private val commandRunning = AtomicBoolean(false)
    private var expanded by mutableStateOf(false)
    private var reply by mutableStateOf("Hi. Type a request and tap Send.")
    private var inputText by mutableStateOf("")

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        OverlayDiagnostics.update(permissionGranted = canDrawOverlay(), serviceRunning = true, viewAttached = false, lastError = null)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            if (lifecycleRegistry.currentState == Lifecycle.State.CREATED) lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            when (intent?.action) {
                ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
                ACTION_TEST_BUBBLE -> testRun = true
            }
            val granted = canDrawOverlay()
            OverlayDiagnostics.update(permissionGranted = granted, serviceRunning = true)
            if (!granted) {
                CrashLog.record("OVERLAY", "overlay service refused because user permission is off")
                OverlayDiagnostics.update(serviceRunning = false, viewAttached = false, lastError = "Overlay permission is off")
                stopSelf(startId)
                return START_NOT_STICKY
            }
            startForeground(NOTIFICATION_ID, createOngoingNotification())
            if (overlayView == null) attachOverlay()
            if (testRun) {
                testJob?.cancel()
                testJob = serviceScope.launch { delay(TEST_DURATION_MS); stopSelf() }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            CrashLog.record("OVERLAY", "overlay could not be started or attached", error)
            OverlayDiagnostics.update(serviceRunning = false, viewAttached = false, lastError = error.message ?: error.javaClass.simpleName)
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun canDrawOverlay() = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this)

    private fun attachOverlay() {
        val manager = getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: error("Android WindowManager is unavailable")
        val view = ComposeView(this).apply {
            OverlayComposeOwners.install(this, this@FloatingAssistantOverlayService, this@FloatingAssistantOverlayService)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                FloatingPanel(
                    expanded = expanded, reply = reply, inputText = inputText,
                    onExpand = { expanded = true; setInputPanelOpen(true); overlayView?.requestFocus() },
                    onCollapse = { expanded = false; setInputPanelOpen(false) }, onSend = ::sendCommand,
                    onInputChange = { inputText = it.take(MAX_COMMAND_LENGTH) },
                    onStop = { stopSelf() }, onOpen = ::openApp,
                    onDrag = ::moveBy, onLongPress = ::stopSelf
                )
            }
        }
        val dm = resources.displayMetrics
        val saved = getSharedPreferences(PREFS, MODE_PRIVATE)
        val initial = OverlayPosition(saved.getInt(KEY_X, dm.widthPixels - (72 * dm.density).toInt()), saved.getInt(KEY_Y, (160 * dm.density).toInt()))
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = initial.x.coerceAtLeast(0); y = initial.y.coerceAtLeast(0); windowAnimations = 0
            if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
        }
        try {
            manager.addView(view, params)
        } catch (error: WindowManager.BadTokenException) {
            CrashLog.record("OVERLAY", "Android rejected overlay window token", error)
            OverlayDiagnostics.update(viewAttached = false, lastError = "${error.javaClass.simpleName}: ${error.message ?: "Bad overlay token"}")
            view.disposeComposition()
            throw error
        } catch (error: Exception) {
            CrashLog.record("OVERLAY", "overlay window attachment failed", error)
            OverlayDiagnostics.update(viewAttached = false, lastError = "${error.javaClass.simpleName}: ${error.message ?: "no detail supplied"}")
            view.disposeComposition()
            throw error
        }
        windowManager = manager; overlayView = view; layoutParams = params
        view.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> clampCurrentPosition(v) }
        view.post { clampCurrentPosition(view) }
        OverlayDiagnostics.update(permissionGranted = true, serviceRunning = true, viewAttached = true, lastError = null)
        CrashLog.record("OVERLAY", "floating assistant overlay attached")
    }

    private fun clampCurrentPosition(view: View) {
        val p = layoutParams ?: return
        val dm = resources.displayMetrics
        val density = dm.density
        val insets = OverlayInsets(0, (24 * density).toInt(), 0, (24 * density).toInt())
        val next = OverlayPositionPolicy.clamp(OverlayPosition(p.x, p.y), dm.widthPixels, dm.heightPixels, view.width.coerceAtLeast((64*density).toInt()), view.height.coerceAtLeast((64*density).toInt()), insets)
        if (next.x != p.x || next.y != p.y) { p.x = next.x; p.y = next.y; updateWindow(view, p); persistPosition(next) }
    }

    private fun moveBy(dx: Int, dy: Int) {
        val v = overlayView ?: return; val p = layoutParams ?: return
        val dm = resources.displayMetrics
        val density = dm.density
        val next = OverlayPositionPolicy.applyDrag(OverlayPosition(p.x,p.y), dx,dy,dm.widthPixels,dm.heightPixels,v.width.coerceAtLeast((64*density).toInt()),v.height.coerceAtLeast((64*density).toInt()), OverlayInsets(0,(24*density).toInt(),0,(24*density).toInt()))
        p.x = next.x; p.y = next.y; updateWindow(v,p); persistPosition(next)
    }

    private fun updateWindow(view: View, params: WindowManager.LayoutParams) {
        try { windowManager?.updateViewLayout(view, params) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { CrashLog.record("OVERLAY", "overlay position update failed", error); OverlayDiagnostics.update(lastError = error.message ?: "Position update failed") }
    }
    private fun persistPosition(pos: OverlayPosition) { getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(KEY_X,pos.x).putInt(KEY_Y,pos.y).apply() }

    private fun setInputPanelOpen(open: Boolean) {
        val view = overlayView ?: return
        val params = layoutParams ?: return
        val focusFlag = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        val nextFlags = if (open) params.flags and focusFlag.inv() else params.flags or focusFlag
        if (nextFlags != params.flags) {
            params.flags = nextFlags
            updateWindow(view, params)
        }
    }

    private fun sendCommand(text: String) {
        val clean = text.trim().take(MAX_COMMAND_LENGTH)
        if (clean.isBlank()) { reply = "Type a request first."; return }
        if (!commandRunning.compareAndSet(false, true)) { reply = "A request is already running; please wait."; return }
        inputText = clean
        reply = "Working…"
        commandJob = serviceScope.launch {
            try {
                val app = application as OfflineBrainApplication
                reply = withContext(Dispatchers.IO) { app.repository.handle(clean).plainText }.take(MAX_REPLY_LENGTH)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { CrashLog.record("OVERLAY", "assistant request failed", error); reply = "I couldn't complete that request: ${error.message ?: "check the app"}" }
            finally { commandRunning.set(false) }
        }
    }

    private fun openApp() {
        try { startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { CrashLog.record("OVERLAY", "could not open app from floating panel", error) }
    }

    private fun createOngoingNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: error("Android NotificationManager is unavailable")
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Floating assistant", NotificationManager.IMPORTANCE_LOW))
        val stopIntent = PendingIntent.getService(this, 0, Intent(this, FloatingAssistantOverlayService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        return builder.setSmallIcon(android.R.drawable.ic_menu_view).setContentTitle("Floating assistant is active").setContentText("Your assistant bubble is visible. Tap Stop to close it.").setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), "Stop", stopIntent).build()).build()
    }

    override fun onDestroy() {
        testJob?.cancel(); testJob = null
        commandJob?.cancel(); commandJob = null
        serviceScope.cancel()
        try {
            val view = overlayView
            val manager = windowManager
            if (view != null) {
                try { manager?.removeView(view) } catch (cancelled: CancellationException) { throw cancelled } catch (error: Exception) { CrashLog.record("OVERLAY", "overlay view removal failed", error) }
                try { view.disposeComposition() } catch (cancelled: CancellationException) { throw cancelled } catch (error: Exception) { CrashLog.record("OVERLAY", "overlay composition disposal failed", error) }
            }
            overlayView = null; windowManager = null; layoutParams = null
            try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (cancelled: CancellationException) { throw cancelled } catch (error: Exception) { CrashLog.record("OVERLAY", "foreground notification removal failed", error) }
            if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.STARTED)) lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            OverlayDiagnostics.update(serviceRunning = false, viewAttached = false)
            CrashLog.record("OVERLAY", "floating assistant stopped")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { CrashLog.record("OVERLAY", "overlay cleanup failed", error); OverlayDiagnostics.update(serviceRunning = false, viewAttached = false, lastError = error.message) }
        try { super.onDestroy() } catch (error: Exception) { CrashLog.record("OVERLAY", "service destroy callback failed", error) }
    }

    companion object {
        const val ACTION_STOP = "com.cue.offlinebrain.action.STOP_OVERLAY"
        const val ACTION_TEST_BUBBLE = "com.cue.offlinebrain.action.TEST_OVERLAY_BUBBLE"
        private const val CHANNEL_ID = "floating_assistant"
        private const val NOTIFICATION_ID = 2107
        private const val TEST_DURATION_MS = 10_000L
        private const val MAX_COMMAND_LENGTH = 2_000
        private const val MAX_REPLY_LENGTH = 8_000
        private const val PREFS = "floating_assistant_overlay"
        private const val KEY_X = "x"
        private const val KEY_Y = "y"
    }
}

@Composable
private fun FloatingPanel(expanded: Boolean, reply: String, inputText: String, onExpand: () -> Unit, onCollapse: () -> Unit, onSend: (String) -> Unit, onInputChange: (String) -> Unit, onStop: () -> Unit, onOpen: () -> Unit, onDrag: (Int,Int) -> Unit, onLongPress: () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF8FE3D8), surface = Color(0xFF1A1B25))) {
        Surface(color = Color(0xFF1A1B25), shape = RoundedCornerShape(16.dp), shadowElevation = 10.dp) {
            Column(Modifier.widthIn(min=74.dp,max=280.dp).padding(8.dp), verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement=Arrangement.spacedBy(6.dp), modifier=Modifier.fillMaxWidth()) {
                    Button(onClick=onExpand, modifier=Modifier.weight(1f).semantics { contentDescription = "Open assistant input" }) { Text(if(expanded) "Assistant" else "OB · Assistant") }
                    Box(Modifier.size(44.dp).semantics { contentDescription = "Drag handle. Long press to stop the floating assistant." }.pointerInput(Unit) {
                        detectDragGestures(onDrag = { change, drag -> change.consume(); onDrag(drag.x.toInt(), drag.y.toInt()) })
                    }.pointerInput(Unit) {
                        detectTapGestures(onLongPress = { onLongPress() })
                    }) { Text("⋮⋮", color=Color.White, modifier=Modifier.padding(12.dp)) }
                }
                if (expanded) {
                    Text(reply, color=Color.White, modifier=Modifier.heightIn(max=120.dp).verticalScroll(rememberScrollState()))
                    BasicTextField(value=inputText, onValueChange=onInputChange, modifier=Modifier.fillMaxWidth().padding(8.dp).semantics { contentDescription="Assistant request" }, textStyle=androidx.compose.ui.text.TextStyle(color=Color.White), decorationBox={ inner -> if(inputText.isEmpty()) Text("Type a request…",color=Color.LightGray); inner() })
                    Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                        Button(onClick={onSend(inputText)}) { Text("Send") }
                        OutlinedButton(onClick=onOpen) { Text("Open full Chat") }
                    }
                    OutlinedButton(onClick=onStop, modifier=Modifier.fillMaxWidth()) { Text("Stop floating assistant") }
                    TextButton(onClick=onCollapse) { Text("Close panel") }
                } else Text(reply.take(100), color=Color.White, maxLines=2)
            }
        }
    }
}
