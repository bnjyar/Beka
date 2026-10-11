package dev.beka.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.graphics.PixelFormat
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.widget.FrameLayout
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import android.os.Build
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.spring
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.beka.BekaApp
import dev.beka.MainActivity
import dev.beka.accessibility.BekaAccessibilityService
import dev.beka.debug.DebugReporter
import dev.beka.rules.Callout
import dev.beka.agent.ConfirmRequest
import dev.beka.ui.BekaTheme
import dev.beka.voice.VoiceAnswer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
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
    private var panel: View? = null
    private var strip: ComposeView? = null
    private var callout: ComposeView? = null

    /** Drives the panel's enter/exit animation; the window is removed once the exit has played. */
    private var panelState: MutableTransitionState<Boolean>? = null
    private var panelRemoval: Job? = null

    /** The next run was asked by voice; claimed when it starts. */
    private var spokenRequest = false
    /** The current (or last) run was asked by voice, so its answer and questions are spoken. */
    private var voiceTurn = false
    private var confirmingByVoice = false
    private var snap: Job? = null

    /** True while the panel, strip or a rule note is up: the bubble then stays fully visible. */
    private val active = MutableStateFlow(false)
    private val dockedRight = MutableStateFlow(false)
    private var dismissTarget: ComposeView? = null
    private val nearTarget = MutableStateFlow(false)

    /** Which window manager each window went through, so it is updated and removed through the same one. */
    private val placed = HashMap<View, WindowManager>()

    /**
     * Accessibility overlays are drawn above the lock screen too. While the phone is locked (or dark)
     * every Beka window is hidden and takes no touches: a confirmation there must go through the
     * notification, whose "Izinkan" asks for the screen lock first.
     */
    private var hiddenForLock = false
    private val lockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.i(TAG, "lock event ${intent.action?.substringAfterLast('.')}, keyguard locked=${phoneLocked()}")
            updateLockVisibility(intent.action == Intent.ACTION_SCREEN_OFF)
        }
    }

    /** While hidden, checks every second whether the phone was unlocked: a broadcast may come before the keyguard is gone. */
    private var unlockWatch: Job? = null

    private fun phoneLocked(): Boolean = getSystemService(KeyguardManager::class.java).isKeyguardLocked

    private fun phoneDark(): Boolean = !getSystemService(android.os.PowerManager::class.java).isInteractive

    private fun updateLockVisibility(screenOff: Boolean = false) {
        val hide = screenOff || phoneDark() || phoneLocked()
        if (hide && unlockWatch?.isActive != true) {
            unlockWatch = scope.launch {
                while (true) {
                    delay(UNLOCK_POLL_MS)
                    if (!phoneDark() && !phoneLocked()) {
                        updateLockVisibility()
                        break
                    }
                }
            }
        }
        if (hide == hiddenForLock) return
        hiddenForLock = hide
        Log.i(TAG, if (hide) "phone locked: overlay hidden" else "phone unlocked: overlay shown")
        if (hide) {
            hidePanel(animate = false)
            if (confirmingByVoice) {
                confirmingByVoice = false
                app.speaker.stop()
                app.listener.cancel()
            }
        } else unlockWatch?.cancel()
        // A question asked on the card while the phone was in use moves to the notification now.
        chat.onPhoneLocked(hide)
        placed.keys.forEach { it.visibility = if (hide) View.INVISIBLE else View.VISIBLE }
    }

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
        ContextCompat.registerReceiver(
            this, lockReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        showBubble()
        updateLockVisibility()
        _running.value = true
        setWanted(this, true)
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
        // Recording needs a microphone-type foreground service while Beka has no visible activity.
        scope.launch {
            app.listener.state.map { it.listening }.distinctUntilChanged().drop(1).collect { on ->
                // Talking over Beka cuts it off.
                if (on) app.speaker.stop()
                setMicForeground(on)
            }
        }
        // A new request, typed or spoken, ends whatever is still being read out.
        scope.launch {
            chat.busy.drop(1).filter { it }.collect {
                app.speaker.stop()
                voiceTurn = spokenRequest
                spokenRequest = false
            }
        }
        // A confirmation during a spoken request is asked and answered by voice too.
        scope.launch {
            chat.status.map { it?.confirm }.distinctUntilChanged().collect { request ->
                if (request != null && voiceTurn) confirmByVoice(request)
                else if (request == null && confirmingByVoice) {
                    // Answered on the card, or the run ended: drop the voice question.
                    confirmingByVoice = false
                    app.speaker.stop()
                    app.listener.cancel()
                }
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
            ACTION_STOP -> {
                Log.i(TAG, "overlay stopped: notification Stop")
                setWanted(this, false)
                stopSelf()
            }
            ACTION_ASSIST -> assist()
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
        Log.i(TAG, "overlay destroyed")
        runCatching { unregisterReceiver(lockReceiver) }
        hidePanel(animate = false)
        hideStrip()
        hideCallout()
        unplace(dismissTarget)
        dismissTarget = null
        unplace(bubble)
        bubble = null
        owner.stop()
        scope.cancel()
        super.onDestroy()
    }

    private fun setMicForeground(on: Boolean) {
        val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
            (if (on) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
        runCatching { ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), types) }
            .onSuccess { Log.i(VOICE_TAG, "foreground microphone ${if (on) "on" else "off"}") }
            .onFailure { Log.w(VOICE_TAG, "foreground microphone ${if (on) "on" else "off"} rejected", it) }
    }

    /**
     * A spoken request: the answer is read out too, unless that is switched off. While the panel
     * stays open, Beka then listens once more for a follow-up.
     */
    private fun sendSpoken(text: String) {
        spokenRequest = true
        chat.send(text) { outcome ->
            if (!app.voiceSettings.speakReplies.value) return@send
            // Only a conversation is read out: after acting (a song just started), talking gets in the way.
            if (chat.status.value != null) return@send
            val spoken = when {
                outcome == "Error: dihentikan" -> null
                outcome.startsWith("Error: Ada error:") -> "Maaf, ada error. Detailnya ada di panel."
                // Friendly messages point to screens with "→"; spoken, that reads as "di Setelan, Model AI".
                outcome.startsWith("Error:") ->
                    outcome.removePrefix("Error:").trim().replaceFirstChar { it.uppercase() }.replace(" → ", ", ")
                else -> outcome
            }
            spoken?.let { scope.launch { app.speaker.speak(it, onDone = ::listenForFollowUp) } }
        }
        // Not sent (a run is going on): nothing claimed the flag.
        if (!chat.busy.value) spokenRequest = false
    }

    private fun listenForFollowUp() {
        if (panel == null || chat.busy.value || !app.listener.hasPermission()) return
        Log.i(VOICE_TAG, "listening for a follow-up")
        app.listener.start(quiet = true, onResult = ::sendSpoken)
    }

    private fun confirmByVoice(request: ConfirmRequest) {
        // Never by voice past the screen lock: anyone nearby could say "ya". The notification asks there.
        if (!app.listener.hasPermission() || phoneLocked() || phoneDark()) return
        confirmingByVoice = true
        val listen = {
            if (confirmingByVoice) {
                app.listener.start { answer ->
                    val allow = VoiceAnswer.parse(answer)
                    Log.i(VOICE_TAG, "voice confirm: ${allow ?: "unclear"}")
                    when (allow) {
                        null -> app.speaker.speak("Kurang jelas. Jawab ya atau batal, atau tap kartunya.", onDone = { confirmByVoice(request) })
                        else -> {
                            confirmingByVoice = false
                            if (allow && (phoneLocked() || phoneDark())) Log.i(VOICE_TAG, "voice confirm ignored: phone locked")
                            else chat.answerConfirm(allow)
                        }
                    }
                }
            }
        }
        if (app.voiceSettings.speakReplies.value) {
            val where = if (request.appLabel.isNotEmpty()) " di ${request.appLabel}" else ""
            app.speaker.speak("${request.description}$where? Ya atau batal?", onDone = listen)
        } else {
            listen()
        }
    }

    /** Holding the power button or a headset button: open the panel already listening. */
    private fun assist() {
        app.speaker.stop()
        // The overlay is hidden on a locked phone, and requests must not get past the lock.
        if (phoneLocked()) {
            Toast.makeText(this, "Buka kunci HP dulu untuk bicara dengan Beka", Toast.LENGTH_SHORT).show()
            return
        }
        if (app.listener.hasPermission()) app.listener.start(onResult = ::sendSpoken) else requestMic()
        if (panel != null) return
        scope.launch {
            // Let the assist activity's window go before the screen is read.
            delay(250)
            chat.captureScreen()
            showPanel()
        }
    }

    /** Runtime permissions can only be asked from an activity. */
    private fun requestMic() {
        hidePanel(animate = false)
        startActivity(
            Intent(this, MainActivity::class.java)
                .setAction(MainActivity.ACTION_REQUEST_MIC)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun showBubble() {
        val view = ComposeView(this).apply {
            setContent {
                BekaTheme {
                    Bubble(
                        busy = chat.busy,
                        active = active,
                        dockedRight = dockedRight,
                        idleAlpha = app.appearance.bubbleIdleAlpha,
                        onTap = ::togglePanel,
                        onDragStart = ::startBubbleDrag,
                        onDrag = { dx, dy ->
                            snap?.cancel()
                            bubbleParams.x += dx.toInt()
                            bubbleParams.y += dy.toInt()
                            bubble?.let { relayout(it, bubbleParams) }
                            updateNearTarget()
                        },
                        onDragEnd = ::releaseBubble,
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
        if (phoneLocked()) return
        Log.i(TAG, "bubble tapped: panel=${panel != null}, opening=${opening?.isActive == true}, status=${status?.let { if (it.done) "done" else "running" }}")
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

    /** While the bubble is dragged, a drop zone at the bottom centre offers to hide it. */
    private fun startBubbleDrag() {
        snap?.cancel()
        bubble?.performHapticFeedback(HapticFeedbackConstants.GESTURE_START)
        if (dismissTarget != null) return
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = navigationBarHeight() + (TARGET_MARGIN_DP * resources.displayMetrics.density).toInt()
        }
        val view = ComposeView(this).apply {
            setContent { BekaTheme { DismissTarget(nearTarget) } }
        }
        view.accessibilityPaneTitle = WINDOW_TITLE
        owner.attach(view)
        place(view, params)
        dismissTarget = view
    }

    /** Whether the bubble's centre is within reach of the drop zone's centre; ticks on entering. */
    private fun updateNearTarget() {
        if (dismissTarget == null) return
        val density = resources.displayMetrics.density
        val bounds = windowManager.currentWindowMetrics.bounds
        val half = BUBBLE_DP * density / 2
        val dx = bubbleParams.x + half - bounds.width() / 2f
        val targetY = bounds.height() - navigationBarHeight() - (TARGET_MARGIN_DP + TARGET_DP / 2f) * density
        val dy = bubbleParams.y + half - targetY
        val near = dx * dx + dy * dy < (TARGET_REACH_DP * density).let { it * it }
        if (near && !nearTarget.value) bubble?.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        nearTarget.value = near
    }

    private fun releaseBubble() {
        // A locked phone in a pocket can drag; it never turns the bubble off.
        val dropped = nearTarget.value && !hiddenForLock
        nearTarget.value = false
        unplace(dismissTarget)
        dismissTarget = null
        if (!dropped) {
            snapBubble()
            return
        }
        val status = chat.status.value
        if (status != null && !status.done) {
            Toast.makeText(this, "Beka sedang bertindak — tekan Stop dulu", Toast.LENGTH_SHORT).show()
            snapBubble()
            return
        }
        bubble?.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        Toast.makeText(this, "Bubble disembunyikan. Buka app Beka → Mulai untuk memunculkannya lagi.", Toast.LENGTH_LONG).show()
        Log.i(TAG, "overlay stopped: bubble dropped on the target")
        setWanted(this, false)
        stopSelf()
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
        // Compose animations tick on a frame clock; the service's own dispatcher has none.
        snap = scope.launch(AndroidUiDispatcher.Main) {
            // Tick the moment the bubble first touches the edge, not when the spring has fully settled.
            val start = bubbleParams.x.toFloat()
            var ticked = false
            Animatable(start).animateTo(target.toFloat(), spring(dampingRatio = 0.75f, stiffness = 500f)) {
                bubbleParams.x = value.toInt()
                relayout(view, bubbleParams)
                val reached = if (target >= start) value >= target - 1f else value <= target + 1f
                if (reached && !ticked) {
                    ticked = true
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
            }
            if (!ticked) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            // A rule note shown while the bubble moved follows it to its new spot.
            if (callout != null) app.proactive.callout.value?.let { c ->
                hideCallout()
                showCallout(c)
            }
        }
    }

    private fun updateActive() {
        active.value = panel != null || strip != null || callout != null
    }

    /** The screen read before the panel opens; one at a time. */
    private var opening: Job? = null

    /** Captures the screen first (panel hidden), then shows the panel. */
    private fun openPanel() {
        if (opening?.isActive == true) return
        opening = scope.launch {
            val started = System.currentTimeMillis()
            // Bounded: the panel opens even if reading the screen hangs, just without it.
            if (withTimeoutOrNull(SCREEN_READ_TIMEOUT_MS) { chat.captureScreen() } == null) {
                Log.w(TAG, "screen read for the panel timed out")
            }
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
        val content = ComposeView(this).apply {
            setContent {
                BekaTheme {
                    AnimatedVisibility(visibleState = state, enter = PanelEnter, exit = PanelExit) {
                        ChatPanel(
                            chat = chat,
                            listener = app.listener,
                            speaking = app.speaker.speaking,
                            virtualStatus = app.virtualScreen.status,
                            onVoiceText = ::sendSpoken,
                            onStopSpeaking = app.speaker::stop,
                            onRequestMic = ::requestMic,
                            heightPx = height,
                            expandedHeightPx = (windowManager.currentWindowMetrics.bounds.height() * 0.9f).toInt(),
                            backgroundAlpha = app.appearance.panelAlpha,
                            onClose = { hidePanel() },
                            onRefresh = ::refreshScreen,
                            onReport = ::report,
                        )
                    }
                }
            }
        }
        val view = BackFrame(this) { hidePanel() }.apply { addView(content) }
        view.accessibilityPaneTitle = WINDOW_TITLE
        owner.attach(view)
        place(view, params, accessibility)
        panel = view
        panelState = state
        updateActive()
    }

    private fun showStrip() {
        if (strip != null) return
        // Wraps its content: the pill leaves the rest of the top edge touchable; the cards fill the width.
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = statusBarHeight()
        }
        val state = MutableTransitionState(false).apply { targetState = true }
        val view = ComposeView(this).apply {
            setContent {
                BekaTheme {
                    AnimatedVisibility(visibleState = state, enter = StripEnter) {
                    AgentStrip(
                        status = chat.status,
                        backgroundRun = chat.backgroundRun,
                        frame = app.virtualScreen.frame,
                        watch = app.virtualScreen::watch,
                        onStop = chat::stop,
                        onShowBackground = chat::showBackgroundApp,
                        onAnswer = { allow ->
                            // Hidden while locked anyway; never approve past the screen lock.
                            if (allow && phoneLocked()) {
                                Toast.makeText(this@OverlayService, "Buka kunci HP dulu, atau izinkan lewat notifikasi", Toast.LENGTH_SHORT).show()
                            } else chat.answerConfirm(allow)
                        },
                        onOpenChat = {
                            chat.dismissStatus()
                            // Asked by voice: "Ada lagi?" is answered by voice too.
                            if (voiceTurn && app.listener.hasPermission()) assist() else openPanel()
                        },
                        onDismiss = chat::dismissStatus,
                    )
                    }
                }
            }
        }
        view.accessibilityPaneTitle = WINDOW_TITLE
        owner.attach(view)
        place(view, params)
        strip = view
        updateActive()
    }

    /**
     * Beside the bubble, its tail pointing at the bubble's centre. In the upper half of the screen the
     * card hangs down from the tail; in the lower half it rises from it, so it never runs off screen.
     */
    private fun calloutParams(params: WindowManager.LayoutParams? = null): Pair<WindowManager.LayoutParams, Pair<Boolean, Boolean>> {
        val density = resources.displayMetrics.density
        fun dp(v: Float) = (v * density).toInt()
        val bounds = windowManager.currentWindowMetrics.bounds
        val bubbleCenterY = bubbleParams.y + dp(BUBBLE_DP / 2f)
        val left = !dockedRight.value
        val top = bubbleCenterY < bounds.height() / 2
        val windowWidth = dp(CALLOUT_WIDTH.value + CALLOUT_TAIL.value + 2 * CALLOUT_PAD.value)
        // The circle spans 6..58dp of the 64dp bubble window; the tail's tip stops just short of it.
        val tip = dp(BUBBLE_DP - 6f + 4f)
        val inset = dp(CALLOUT_PAD.value + CALLOUT_TAIL_INSET.value)
        val p = params ?: WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        )
        p.gravity = (if (top) Gravity.TOP else Gravity.BOTTOM) or Gravity.START
        p.x = if (left) tip - dp(CALLOUT_PAD.value) else bounds.width() - tip + dp(CALLOUT_PAD.value) - windowWidth
        p.y = if (top) bubbleCenterY - inset else bounds.height() - bubbleCenterY - inset
        return p to (left to top)
    }

    private fun showCallout(c: Callout) {
        val (params, side) = calloutParams()
        val view = ComposeView(this).apply {
            setContent {
                BekaTheme {
                    RuleCallout(
                        callout = c,
                        tailLeft = side.first,
                        tailTop = side.second,
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
            view.visibility = if (hiddenForLock) View.INVISIBLE else View.VISIBLE
            try {
                wm.addView(view, params)
                placed[view] = wm
                return
            } catch (e: WindowManager.BadTokenException) {
                Log.w(TAG, "Accessibility overlay rejected, using an app overlay", e)
            } catch (e: SecurityException) {
                Log.w(TAG, "Accessibility overlay refused, using an app overlay", e)
            }
        }
        params.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        view.visibility = if (hiddenForLock) View.INVISIBLE else View.VISIBLE
        try {
            windowManager.addView(view, params)
            placed[view] = windowManager
        } catch (e: WindowManager.BadTokenException) {
            giveUp(e)
        } catch (e: SecurityException) {
            giveUp(e)
        }
    }

    /** The overlay permission is gone (revoked while the sticky service restarted): stop instead of crash-looping. */
    private fun giveUp(e: Exception) {
        Log.e(TAG, "overlay window refused, stopping: ${e.javaClass.simpleName}")
        stopSelf()
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
        app.listener.cancel()
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

    private fun removePanel(view: View) {
        if (panel !== view) return
        unplace(view)
        panel = null
        panelState = null
        updateActive()
    }

    private var reporting = false

    private fun report(request: ReportRequest) {
        if (reporting) return
        reporting = true
        scope.launch {
            try {
                val written = runCatching {
                    DebugReporter.write(this@OverlayService, chat.screen.value, request.happened, request.expected, request.include)
                }.getOrElse {
                    Toast.makeText(this@OverlayService, "Gagal menyimpan laporan: ${it.message}", Toast.LENGTH_LONG).show()
                    return@launch
                }
                // Out of the way, so the browser is what the user sees.
                hidePanel()
                val url = DebugReporter.issueUrl(request.happened, request.expected, written.stamp)
                val opened = runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.isSuccess
                if (!opened) {
                    Toast.makeText(this@OverlayService, "Laporan tersimpan: ${written.zip}", Toast.LENGTH_LONG).show()
                }
            } finally {
                reporting = false
            }
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
        private const val VOICE_TAG = "BekaVoice"
        private const val CHANNEL = "overlay"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "dev.beka.STOP"
        private const val ACTION_ASSIST = "dev.beka.ASSIST"
        /** The bubble's size including its shadow padding (52dp + 2 × 6dp). */
        private const val BUBBLE_DP = 64
        /** Longer than the panel's exit animation. */
        private const val EXIT_WAIT_MS = 600L
        private const val SCREEN_READ_TIMEOUT_MS = 4_000L
        private const val UNLOCK_POLL_MS = 1_000L
        /** The drop zone: its window is 96dp, the circle inside 60dp; the bubble counts as over it within reach. */
        private const val TARGET_DP = 96f
        private const val TARGET_MARGIN_DP = 40f
        private const val TARGET_REACH_DP = 64f
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

        fun assist(context: Context) {
            context.startForegroundService(Intent(context, OverlayService::class.java).setAction(ACTION_ASSIST))
        }

        fun stop(context: Context) {
            Log.i(TAG, "overlay stopped: Hentikan in the app")
            setWanted(context, false)
            context.stopService(Intent(context, OverlayService::class.java))
        }

        /**
         * After an update or a reboot the bubble comes back by itself, unless the user turned it off.
         * An update kills the process without onDestroy, so "wanted" survives it.
         */
        fun restore(context: Context) {
            val wanted = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_WANTED, false)
            Log.i(TAG, "overlay restore: wanted=$wanted")
        if (!wanted || !android.provider.Settings.canDrawOverlays(context)) return
            runCatching { start(context) }.onFailure { Log.w(TAG, "overlay restore failed", it) }
        }

        private fun setWanted(context: Context, on: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_WANTED, on).apply()
        }

        private const val PREFS = "overlay"
        private const val KEY_WANTED = "wanted"

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

/**
 * Root of the panel window: the back gesture or key closes the panel (the keyboard, when up, takes
 * the first back itself). Android 13+ delivers back through OnBackInvokedDispatcher, older versions
 * as a key event.
 */
private class BackFrame(context: Context, private val onBack: () -> Unit) : FrameLayout(context) {
    private val callback: Any? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) OnBackInvokedCallback { onBack() } else null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            findOnBackInvokedDispatcher()?.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                callback as OnBackInvokedCallback,
            )
        }
    }

    override fun onDetachedFromWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            findOnBackInvokedDispatcher()?.unregisterOnBackInvokedCallback(callback as OnBackInvokedCallback)
        }
        super.onDetachedFromWindow()
    }

    // Evidence for the intermittent "keyboard covers the input row" report: how tall the panel laid
    // out against the frame the window got and the keyboard's height.
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (!changed) return
        val content = getChildAt(0)
        val ime = rootWindowInsets?.getInsets(android.view.WindowInsets.Type.ime())?.bottom ?: -1
        val frame = android.graphics.Rect().also { getWindowVisibleDisplayFrame(it) }
        Log.i("BekaOverlay", "panel layout: window ${bottom - top}px, content ${content?.measuredHeight}px, ime ${ime}px, visible frame $frame")
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) onBack()
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}
