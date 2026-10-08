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
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.ServiceCompat
import dev.beka.BekaApp
import dev.beka.MainActivity
import dev.beka.debug.DebugReporter
import dev.beka.ui.BekaTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Foreground service that owns the floating bubble and the chat panel windows. */
class OverlayService : Service() {
    private lateinit var windowManager: WindowManager
    private val owner = OverlayOwner()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val chat get() = (application as BekaApp).chat

    private var bubble: ComposeView? = null
    private var panel: ComposeView? = null

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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) stopSelf()
        return START_STICKY
    }

    override fun onDestroy() {
        _running.value = false
        hidePanel()
        bubble?.let { runCatching { windowManager.removeView(it) } }
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
                        onTap = ::togglePanel,
                        onDrag = { dx, dy ->
                            bubbleParams.x += dx.toInt()
                            bubbleParams.y += dy.toInt()
                            bubble?.let { windowManager.updateViewLayout(it, bubbleParams) }
                        },
                    )
                }
            }
        }
        owner.attach(view)
        windowManager.addView(view, bubbleParams)
        bubble = view
    }

    private fun togglePanel() {
        if (panel != null) hidePanel() else openPanel()
    }

    /** Captures the screen first (panel hidden), then shows the panel. */
    private fun openPanel() {
        scope.launch {
            chat.captureScreen()
            showPanel()
        }
    }

    private fun refreshScreen() {
        scope.launch {
            hidePanel()
            delay(300)
            chat.captureScreen()
            showPanel()
        }
    }

    private fun showPanel() {
        if (panel != null) return
        val height = (windowManager.currentWindowMetrics.bounds.height() * 0.62f).toInt()
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        val view = ComposeView(this).apply {
            setContent {
                BekaTheme {
                    ChatPanel(
                        chat = chat,
                        onClose = ::hidePanel,
                        onRefresh = ::refreshScreen,
                        onReport = ::report,
                    )
                }
            }
        }
        owner.attach(view)
        windowManager.addView(view, params)
        panel = view
    }

    private fun hidePanel() {
        panel?.let { runCatching { windowManager.removeView(it) } }
        panel = null
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
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Beka aktif")
            .setContentText("Tap bubble untuk bertanya soal layar")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "overlay"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "dev.beka.STOP"

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running

        fun start(context: Context) {
            context.startForegroundService(Intent(context, OverlayService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }
    }
}
