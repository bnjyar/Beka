package dev.beka.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.SystemClock
import android.graphics.Bitmap
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import dev.beka.MainActivity
import dev.beka.overlay.OverlayService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

class BekaAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
        _connected.value = true
    }

    override fun onDestroy() {
        instance = null
        _connected.value = false
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        // Overlay redraws (status strip updates) must not count as the app still changing.
        if (pkg != packageName || MainActivity.visible) lastEventAt = SystemClock.uptimeMillis()
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (pkg != packageName && pkg != "com.android.systemui") _foregroundApp.value = pkg
    }

    override fun onInterrupt() = Unit

    /**
     * The top-most app window that is not one of Beka's overlay windows.
     * Beka's own activity counts as a normal app, so its settings screen can be read too.
     */
    fun targetRoot(): AccessibilityNodeInfo? = windows
        .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && !isOverlay(it) }
        .firstNotNullOfOrNull { it.root }

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
    suspend fun gestureTap(x: Float, y: Float, long: Boolean = false): Boolean =
        gesture(Path().apply { moveTo(x, y) }, if (long) 800 else 50)

    suspend fun gestureSwipe(x1: Float, y1: Float, x2: Float, y2: Float): Boolean =
        gesture(Path().apply { moveTo(x1, y1); lineTo(x2, y2) }, 300)

    private suspend fun gesture(path: Path, durationMs: Long): Boolean = suspendCancellableCoroutine { cont ->
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val sent = dispatchGesture(
            GestureDescription.Builder().addStroke(stroke).build(),
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

    fun captureTree(): ScreenContext? {
        val root = targetRoot() ?: return null
        val pkg = root.packageName?.toString() ?: "unknown"
        val result = ScreenSerializer.serialize(root)
        lastNodes = result.nodes
        return ScreenContext(
            packageName = pkg,
            appLabel = appLabel(pkg),
            tree = result.text,
            nodeCount = result.count,
            capturedAt = System.currentTimeMillis(),
        )
    }

    /** JPEG of the current screen, longest side ≤ [maxSide]. Null for FLAG_SECURE apps or on failure. */
    suspend fun captureScreenshot(maxSide: Int = 1024): ByteArray? {
        val bitmap = suspendCancellableCoroutine<Bitmap?> { cont ->
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
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
            ByteArrayOutputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
                out.toByteArray()
            }
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
