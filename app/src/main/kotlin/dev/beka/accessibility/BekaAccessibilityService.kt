package dev.beka.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.Rect
import android.os.SystemClock
import android.graphics.Bitmap
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import dev.beka.BekaApp
import dev.beka.MainActivity
import dev.beka.overlay.OverlayService
import dev.beka.spike.DisplayProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

class BekaAccessibilityService : AccessibilityService() {

    private val displayProbe = DisplayProbe(this)

    override fun onServiceConnected() {
        displayProbe.register()
        instance = this
        _connected.value = true
    }

    override fun onDestroy() {
        displayProbe.unregister()
        instance = null
        _connected.value = false
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        // Overlay redraws (status strip updates) must not count as the app still changing.
        if (pkg != packageName || MainActivity.visible) lastEventAt = SystemClock.uptimeMillis()
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        // Apps the agent opens on the virtual display are not what the user is looking at.
        if (onVirtualDisplay(event.windowId)) return
        if (pkg != packageName && pkg != "com.android.systemui") {
            _foregroundApp.value = pkg
            (application as BekaApp).proactive.onForeground(pkg)
        }
    }

    override fun onInterrupt() = Unit

    private fun onVirtualDisplay(windowId: Int): Boolean {
        val vd = (application as BekaApp).virtualScreen.displayId ?: return false
        return windowsOnAllDisplays.get(vd).orEmpty().any { it.id == windowId }
    }

    /**
     * The top-most window the user is looking at: an app window that is not one of Beka's overlays,
     * or a system window covering most of the screen (notification shade, quick settings).
     * Beka's own activity counts as a normal app, so its settings screen can be read too.
     * On another display (the virtual one) it is simply the top app window: Beka draws nothing there.
     */
    fun targetRoot(displayId: Int = Display.DEFAULT_DISPLAY): AccessibilityNodeInfo? {
        if (displayId != Display.DEFAULT_DISPLAY) {
            return windowsOnAllDisplays.get(displayId).orEmpty()
                .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                .firstNotNullOfOrNull { it.root }
        }
        val half = resources.displayMetrics.heightPixels / 2
        val bounds = Rect()
        return windows.filter { window ->
            when (window.type) {
                AccessibilityWindowInfo.TYPE_APPLICATION -> !isOverlay(window)
                AccessibilityWindowInfo.TYPE_SYSTEM -> {
                    window.getBoundsInScreen(bounds)
                    bounds.height() > half && window.root?.packageName?.toString() != packageName
                }
                else -> false
            }
        }.firstNotNullOfOrNull { it.root }
    }

    /** Overlay roots carry a pane title; anything else from Beka's package is MainActivity. */
    private fun isOverlay(window: AccessibilityWindowInfo): Boolean {
        val root = window.root ?: return false
        if (root.paneTitle?.toString() == OverlayService.WINDOW_TITLE) return true
        return root.packageName?.toString() == packageName && !MainActivity.visible
    }

    /** Nodes behind the ids of the last [captureTree], for the agent's actions. */
    @Volatile
    var lastNodes: Map<String, AccessibilityNodeInfo> = emptyMap()
        private set

    /** Package of the last [captureTree]; unlike [foregroundApp] it is never the keyboard. */
    @Volatile
    var lastPackage: String? = null
        private set

    @Volatile
    private var lastEventAt = 0L

    /** Suspends until no UI events arrived for [quietMs] (at least [minMs], at most [maxMs]). */
    suspend fun awaitIdle(quietMs: Long = 350, minMs: Long = 250, maxMs: Long = 3000) {
        val start = SystemClock.uptimeMillis()
        delay(minMs)
        while (true) {
            val now = SystemClock.uptimeMillis()
            if (now - lastEventAt >= quietMs || now - start >= maxMs) return
            delay(50)
        }
    }

    /** A tap (or long press) at screen coordinates. Returns false if the system rejected it. */
    suspend fun gestureTap(x: Float, y: Float, long: Boolean = false, displayId: Int = Display.DEFAULT_DISPLAY): Boolean =
        gesture(Path().apply { moveTo(x, y) }, if (long) 800 else 50, displayId)

    suspend fun gestureSwipe(x1: Float, y1: Float, x2: Float, y2: Float, displayId: Int = Display.DEFAULT_DISPLAY): Boolean =
        gesture(Path().apply { moveTo(x1, y1); lineTo(x2, y2) }, 300, displayId)

    private suspend fun gesture(path: Path, durationMs: Long, displayId: Int): Boolean = suspendCancellableCoroutine { cont ->
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val sent = dispatchGesture(
            GestureDescription.Builder().addStroke(stroke).setDisplayId(displayId).build(),
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(false)
                }
            },
            null,
        )
        if (!sent && cont.isActive) cont.resume(false)
    }

    fun captureTree(displayId: Int = Display.DEFAULT_DISPLAY): ScreenContext? {
        val root = targetRoot(displayId) ?: return null
        val pkg = root.packageName?.toString() ?: "unknown"
        val result = ScreenSerializer.serialize(root)
        lastNodes = result.nodes
        lastPackage = pkg
        return ScreenContext(
            packageName = pkg,
            appLabel = appLabel(pkg),
            tree = result.text,
            nodeCount = result.count,
            capturedAt = System.currentTimeMillis(),
        )
    }

    /**
     * JPEG of the current screen, longest side ≤ [maxSide]. Null for FLAG_SECURE apps or on failure.
     * With [marked], interactive elements from the last [captureTree] are boxed and labelled with
     * their ids (set-of-marks), so a vision model can tell which id the icon it sees has.
     */
    suspend fun captureScreenshot(maxSide: Int = 1024, marked: Boolean = true, displayId: Int = Display.DEFAULT_DISPLAY): ByteArray? {
        val bitmap = suspendCancellableCoroutine<Bitmap?> { cont ->
            takeScreenshot(displayId, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val buffer = result.hardwareBuffer
                    val copy = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                        ?.copy(Bitmap.Config.ARGB_8888, false)
                    buffer.close()
                    if (cont.isActive) cont.resume(copy)
                }

                override fun onFailure(errorCode: Int) {
                    if (cont.isActive) cont.resume(null)
                }
            })
        } ?: return null

        return withContext(Dispatchers.Default) {
            val scale = maxSide.toFloat() / maxOf(bitmap.width, bitmap.height)
            val scaled = if (scale < 1f) {
                Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
            } else bitmap
            val canvasBitmap = if (marked) {
                (if (scaled.isMutable) scaled else scaled.copy(Bitmap.Config.ARGB_8888, true))
                    .also { drawMarks(it, minOf(scale, 1f)) }
            } else scaled
            ByteArrayOutputStream().use { out ->
                canvasBitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
                out.toByteArray()
            }
        }
    }

    private fun drawMarks(bitmap: Bitmap, scale: Float) {
        val canvas = Canvas(bitmap)
        val box = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 3f; color = Color.MAGENTA }
        val tag = Paint().apply { color = Color.MAGENTA }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 22f; typeface = Typeface.DEFAULT_BOLD }
        val r = Rect()
        for ((id, node) in lastNodes) {
            if (!(node.isClickable || node.isLongClickable || node.isEditable)) continue
            node.getBoundsInScreen(r)
            if (r.isEmpty) continue
            val left = r.left * scale
            val top = r.top * scale
            canvas.drawRect(left, top, r.right * scale, r.bottom * scale, box)
            val w = text.measureText(id) + 8f
            canvas.drawRect(left, top, left + w, top + 26f, tag)
            canvas.drawText(id, left + 4f, top + 21f, text)
        }
    }

    private fun appLabel(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    companion object {
        @Volatile
        var instance: BekaAccessibilityService? = null
            private set

        private val _connected = MutableStateFlow(false)
        val connected: StateFlow<Boolean> = _connected

        private val _foregroundApp = MutableStateFlow<String?>(null)
        val foregroundApp: StateFlow<String?> = _foregroundApp
    }
}
