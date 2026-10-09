package dev.beka.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.spring
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.ServiceCompat
import dev.beka.BekaApp
import dev.beka.MainActivity
import dev.beka.accessibility.BekaAccessibilityService
import dev.beka.debug.DebugReporter
import dev.beka.rules.Callout
import dev.beka.ui.BekaTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Foreground service that owns the floating bubble and the chat panel windows. */
class OverlayService : Service() {
    private lateinit var windowManager: WindowManager
    private val owner = OverlayOwner()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val app get() = application as BekaApp
    private val chat get() = app.chat

    private var bubble: ComposeView? = null
    private var panel: ComposeView? = null
    private var strip: ComposeView? = null
    private var callout: ComposeView? = null

    /** Drives the panel's enter/exit animation; the window is removed once the exit has played. */
    private var panelState: MutableTransitionState<Boolean>? = null
    private var panelRemoval: Job? = null
    private var snap: Job? = null

    /** True while the panel, strip or a rule note is up: the bubble then stays fully visible. */
    private val active = MutableStateFlow(false)
    private val dockedRight = MutableStateFlow(false)

    /** Which window manager each window went through, so it is updated and removed through the same one. */
    private val placed = HashMap<View, WindowManager>()

    private val bubbleParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = 0
        y = 400
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        owner.start()
        showBubble()
        _running.value = true
        // Re-place the bubble when the accessibility service comes or goes: its windows die with it.
        scope.launch {
            BekaAccessibilityService.connected.drop(1).collect {
                // The panel may have gone with them; drop it so the next tap opens a fresh one.
                hidePanel(animate = false)
                bubble?.let { view ->
                    unplace(view)
                    place(view, bubbleParams)
                }
            }
        }
        // The notification carries the proactive kill switch; keep its label current.
        scope.launch {
            app.proactiveSettings.enabled.drop(1).collect {
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
            }
        }
        // A rule's note appears next to the bubble.
        scope.launch {
            app.proactive.callout.collect { c ->
                hideCallout()
                if (c != null) showCallout(c)
            }
        }
        // Once the agent acts, the panel gets out of the way and the strip takes over.
        scope.launch {
            chat.status.collect { status ->
                if (status == null) hideStrip() else { hidePanel(animate = false); showStrip() }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            ACTION_TOGGLE_PROACTIVE -> app.proactiveSettings.setEnabled(!app.proactiveSettings.enabled.value)
            ACTION_ACCEPT -> {
                getSystemService(NotificationManager::class.java).cancel(intent.getIntExtra(EXTRA_NOTIFICATION, 0))
                app.proactive.accept(
                    ruleId = intent.getStringExtra(EXTRA_RULE).orEmpty(),
                    runId = intent.getLongExtra(EXTRA_RUN, -1),
                    note = intent.getStringExtra(EXTRA_NOTE).orEmpty(),
                )
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        _running.value = false
        hidePanel(animate = false)
        hideStrip()
        hideCallout()
        unplace(bubble)
        bubble = null
        owner.stop()
        scope.cancel()
        super.onDestroy()
    }

    private fun showBubble() {
        val view = ComposeView(this).apply {
            setContent {
                BekaTheme {
                    Bubble(
                        busy = chat.busy,
                        active = active,
                        dockedRight = dockedRight,
                        onTap = ::togglePanel,
                        onDrag = { dx, dy ->
                            snap?.cancel()
                            bubbleParams.x += dx.toInt()
                            bubbleParams.y += dy.toInt()
                            bubble?.let { relayout(it, bubbleParams) }
                        },
                        onDragEnd = ::snapBubble,
                    )
                }
            }
        }
        view.accessibilityPaneTitle = WINDOW_TITLE
        owner.attach(view)
        place(view, bubbleParams)
        bubble = view
    }

    private fun togglePanel() {
        val status = chat.status.value
        if (status != null && !status.done) {
            Toast.makeText(this, "Beka sedang bertindak — tekan Stop dulu", Toast.LENGTH_SHORT).show()
            return
        }
        chat.dismissStatus()
        when {
            panel == null -> openPanel()
            // Tapped again while it was closing: bring the same panel back.
            panelState?.targetState == false -> showPanel()
            else -> hidePanel()
        }
    }

    /** On release the bubble glides to the nearer side edge, inside the status and navigation bars. */
    private fun snapBubble() {
        val view = bubble ?: return
        val bounds = windowManager.currentWindowMetrics.bounds
        val size = (BUBBLE_DP * resources.displayMetrics.density).toInt()
        val right = bubbleParams.x + size / 2 > bounds.width() / 2
        dockedRight.value = right
        val target = if (right) bounds.width() - size else 0
        val maxY = (bounds.height() - size - navigationBarHeight()).coerceAtLeast(statusBarHeight())
        bubbleParams.y = bubbleParams.y.coerceIn(statusBarHeight(), maxY)
        relayout(view, bubbleParams)
        snap?.cancel()
        snap = scope.launch {
            Animatable(bubbleParams.x.toFloat()).animateTo(target.toFloat(), spring(dampingRatio = 0.75f, stiffness = 500f)) {
                bubbleParams.x = value.toInt()
                relayout(view, bubbleParams)
            }
        }
    }

    private fun updateActive() {
        active.value = panel != null || strip != null || callout != null
    }

    /** Captures the screen first (panel hidden), then shows the panel. */
    private fun openPanel() {
        scope.launch {
            val started = System.currentTimeMillis()
            chat.captureScreen()
            Log.i(TAG, "screen read for the panel in ${System.currentTimeMillis() - started} ms")
            showPanel()
        }
    }

    private fun refreshScreen() {
        scope.launch {
            hidePanel(animate = false)
            delay(300)
            chat.captureScreen()
            showPanel()
        }
    }

    private fun showPanel() {
        if (panel != null) {
            panelRemoval?.cancel()
            panelState?.targetState = true
            return
        }
        // The keyboard is layered right above the window it types into: above an accessibility
        // overlay it covers the navigation bar. Only screens that hide app overlays need one.
        val accessibility = chat.screen.value?.packageName in HIDES_APP_OVERLAYS &&
            BekaAccessibilityService.instance != null
        val height = (windowManager.currentWindowMetrics.bounds.height() * 0.62f).toInt()
        // The height lives in the content, not the window: a window with a fixed height is measured
        // at that height even after the keyboard shrinks its frame, which cut off the input row.
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM
            // Accessibility overlays draw over the navigation bar (app overlays stop above it).
            if (accessibility) y = navigationBarHeight()
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        val state = MutableTransitionState(false).apply { targetState = true }
        val view = ComposeView(this).apply {
            setContent {
                BekaTheme {
                    AnimatedVisibility(visibleState = state, enter = PanelEnter, exit = PanelExit) {
                        ChatPanel(
                            chat = chat,
                            heightPx = height,
                            onClose = { hidePanel() },
                            onRefresh = ::refreshScreen,
                            onReport = ::report,
                        )
                    }
                }
            }
        }
        view.accessibilityPaneTitle = WINDOW_TITLE
        owner.attach(view)
        place(view, params, accessibility)
        panel = view
        panelState = state
        updateActive()
    }

    private fun showStrip() {
        if (strip != null) return
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP
            y = statusBarHeight()
        }
        val view = ComposeView(this).apply {
            setContent {
                BekaTheme {
                    AgentStrip(
                        status = chat.status,
                        onStop = chat::stop,
                        onAnswer = chat::answerConfirm,
                        onOpenChat = {
                            chat.dismissStatus()
                            openPanel()
                        },
                        onDismiss = chat::dismissStatus,
                    )
                }
            }
        }
        view.accessibilityPaneTitle = WINDOW_TITLE
        owner.attach(view)
        place(view, params)
        strip = view
        updateActive()
    }

    /** Below the bubble when it sits in the top half of the screen, above it otherwise. */
    private fun showCallout(c: Callout) {
        val screenHeight = windowManager.currentWindowMetrics.bounds.height()
        val bubbleSize = (BUBBLE_DP * resources.displayMetrics.density).toInt()
        val below = bubbleParams.y + bubbleSize / 2 < screenHeight / 2
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = if (below) Gravity.TOP else Gravity.BOTTOM
            y = if (below) bubbleParams.y + bubbleSize else screenHeight - bubbleParams.y
        }
        val view = ComposeView(this).apply {
            setContent {
                BekaTheme {
                    RuleCallout(
                        callout = c,
                        onAccept = { app.proactive.acceptCallout(c) },
                        onClose = { app.proactive.closeCallout(c) },
                        onWrong = { app.proactive.wrongCallout(c) },
                    )
                }
            }
        }
        view.accessibilityPaneTitle = WINDOW_TITLE
        owner.attach(view)
        place(view, params)
        callout = view
        updateActive()
    }

    private fun hideCallout() {
        unplace(callout)
        callout = null
        updateActive()
    }

    private fun hideStrip() {
        unplace(strip)
        strip = null
        updateActive()
    }

    /**
     * Adds a window through the accessibility service when it is running. Its overlays stay visible
     * over Settings (which hides app overlays) and above the notification shade; without it, falls
     * back to a normal app overlay.
     */
    private fun place(view: View, params: WindowManager.LayoutParams, accessibility: Boolean = true) {
        val a11y = BekaAccessibilityService.instance?.takeIf { accessibility }
        if (a11y != null) {
            params.type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            val wm = a11y.getSystemService(WindowManager::class.java)
            // The service's window token can already be dead, e.g. while the app is being updated.
            try {
                wm.addView(view, params)
                placed[view] = wm
                return
            } catch (e: WindowManager.BadTokenException) {
                Log.w(TAG, "Accessibility overlay rejected, using an app overlay", e)
            }
        }
        params.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        windowManager.addView(view, params)
        placed[view] = windowManager
    }

    private fun relayout(view: View, params: WindowManager.LayoutParams) {
        runCatching { (placed[view] ?: windowManager).updateViewLayout(view, params) }
    }

    private fun unplace(view: View?) {
        view ?: return
        runCatching { (placed.remove(view) ?: windowManager).removeView(view) }
    }

    private fun navigationBarHeight(): Int =
        windowManager.currentWindowMetrics.windowInsets
            .getInsetsIgnoringVisibility(android.view.WindowInsets.Type.navigationBars()).bottom

    private fun statusBarHeight(): Int =
        windowManager.currentWindowMetrics.windowInsets
            .getInsetsIgnoringVisibility(android.view.WindowInsets.Type.statusBars()).top

    /**
     * Plays the exit animation, then removes the window. [animate] = false removes it at once, for
     * when something must see the app underneath right away (a screen read, the agent's first touch).
     */
    private fun hidePanel(animate: Boolean = true) {
        val view = panel ?: return
        val state = panelState
        panelRemoval?.cancel()
        if (!animate || state == null) {
            removePanel(view)
            return
        }
        state.targetState = false
        panelRemoval = scope.launch {
            // Bounded: a window that died with the accessibility service never finishes its exit.
            withTimeoutOrNull(EXIT_WAIT_MS) { snapshotFlow { state.isIdle && !state.currentState }.first { it } }
            removePanel(view)
        }
    }

    private fun removePanel(view: ComposeView) {
        if (panel !== view) return
        unplace(view)
        panel = null
        panelState = null
        updateActive()
    }

    private fun report(note: String) {
        scope.launch {
            val path = runCatching { DebugReporter.write(this@OverlayService, chat.screen.value, note) }
            Toast.makeText(
                this@OverlayService,
                path.fold({ "Laporan tersimpan: $it" }, { "Gagal menyimpan laporan: ${it.message}" }),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Overlay Beka", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, OverlayService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val toggle = PendingIntent.getService(
            this, 2, Intent(this, OverlayService::class.java).setAction(ACTION_TOGGLE_PROACTIVE), PendingIntent.FLAG_IMMUTABLE,
        )
        val proactive = app.proactiveSettings.enabled.value
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Beka aktif")
            .setContentText(if (proactive) "Rule proaktif aktif" else "Rule proaktif dimatikan")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .addAction(Notification.Action.Builder(null, if (proactive) "Matikan proaktif" else "Nyalakan proaktif", toggle).build())
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "BekaOverlay"
        private const val CHANNEL = "overlay"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "dev.beka.STOP"
        /** The bubble's size including its shadow padding (52dp + 2 × 6dp). */
        private const val BUBBLE_DP = 64
        /** Longer than the panel's exit animation. */
        private const val EXIT_WAIT_MS = 600L
        private const val ACTION_TOGGLE_PROACTIVE = "dev.beka.TOGGLE_PROACTIVE"
        private const val ACTION_ACCEPT = "dev.beka.ACCEPT_SUGGESTION"
        private const val EXTRA_RULE = "rule"
        private const val EXTRA_RUN = "run"
        private const val EXTRA_NOTE = "note"
        private const val EXTRA_NOTIFICATION = "notification"

        /** System screens that hide app overlays or draw above them (Settings, the notification shade, ...). */
        private val HIDES_APP_OVERLAYS = setOf(
            "com.android.settings",
            "com.android.systemui",
            "com.google.android.permissioncontroller",
            "com.android.permissioncontroller",
            "com.google.android.packageinstaller",
            "com.android.packageinstaller",
        )

        /** Marks Beka's own overlay windows so the screen reader can skip them. */
        const val WINDOW_TITLE = "Beka overlay"

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running

        fun start(context: Context) {
            context.startForegroundService(Intent(context, OverlayService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }

        /** "Lakukan" on a rule's suggestion: starts the overlay if needed, then hands the task to the agent. */
        fun acceptIntent(context: Context, ruleId: String, runId: Long, notificationId: Int, note: String): PendingIntent =
            PendingIntent.getForegroundService(
                context, notificationId,
                Intent(context, OverlayService::class.java).setAction(ACTION_ACCEPT)
                    .putExtra(EXTRA_RULE, ruleId)
                    .putExtra(EXTRA_RUN, runId)
                    .putExtra(EXTRA_NOTE, note)
                    .putExtra(EXTRA_NOTIFICATION, notificationId),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
    }
}
