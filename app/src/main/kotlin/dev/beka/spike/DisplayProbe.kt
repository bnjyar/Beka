package dev.beka.spike

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import dev.beka.accessibility.ScreenSerializer
import java.io.File

/**
 * Fase-8 spike: lets the shell ask the accessibility service what it sees on another display.
 * Only senders holding DUMP (shell, system) get through.
 *
 *   am broadcast -a dev.beka.SPIKE_DISPLAY --ei display N --es op dump|shot|tap|click|type
 *       [--ei x X --ei y Y] [--es text T]
 *
 * Results go to logcat (tag BekaSpike); shots to the app's external files dir.
 */
class DisplayProbe(private val service: AccessibilityService) : BroadcastReceiver() {

    fun register() {
        val filter = IntentFilter(ACTION)
        if (Build.VERSION.SDK_INT >= 33) {
            service.registerReceiver(this, filter, android.Manifest.permission.DUMP, null, Context.RECEIVER_EXPORTED)
        } else {
            service.registerReceiver(this, filter, android.Manifest.permission.DUMP, null)
        }
    }

    fun unregister() = runCatching { service.unregisterReceiver(this) }

    override fun onReceive(context: Context, intent: Intent) {
        val displayId = intent.getIntExtra("display", 0)
        val text = intent.getStringExtra("text")
        when (intent.getStringExtra("op") ?: "dump") {
            "dump" -> dump(displayId)
            "shot" -> shot(displayId)
            "tap" -> tap(displayId, intent.getIntExtra("x", 0).toFloat(), intent.getIntExtra("y", 0).toFloat())
            "click" -> withNode(displayId, text) { it.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
            "type" -> withNode(displayId, null, editable = true) { node ->
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text ?: "")
                })
            }
        }
    }

    private fun dump(displayId: Int) {
        val all = service.windowsOnAllDisplays
        log("displays seen by a11y: ${(0 until all.size()).map { all.keyAt(it) }}")
        val windows = all.get(displayId)
        if (windows == null) {
            log("display $displayId: no windows")
            return
        }
        val bounds = Rect()
        for (w in windows) {
            w.getBoundsInScreen(bounds)
            log("display $displayId window type=${w.type} title=${w.title} pkg=${w.root?.packageName} focused=${w.isFocused} bounds=$bounds")
        }
        val root = windows.firstNotNullOfOrNull { it.root } ?: return log("display $displayId: no root")
        val tree = ScreenSerializer.serialize(root)
        log("display $displayId tree (${tree.count} nodes):\n${tree.text.take(3000)}")
    }

    private fun shot(displayId: Int) {
        service.takeScreenshot(displayId, service.mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                val bmp = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                    ?.copy(Bitmap.Config.ARGB_8888, false)
                result.hardwareBuffer.close()
                val file = File(service.getExternalFilesDir(null), "vd-a11y-$displayId.png")
                file.outputStream().use { bmp?.compress(Bitmap.CompressFormat.PNG, 100, it) }
                log("shot display $displayId ok ${bmp?.width}x${bmp?.height} → $file")
            }

            override fun onFailure(errorCode: Int) = log("shot display $displayId failed: error $errorCode")
        })
    }

    private fun tap(displayId: Int, x: Float, y: Float) {
        val stroke = GestureDescription.StrokeDescription(Path().apply { moveTo(x, y) }, 0, 50)
        val gesture = GestureDescription.Builder().addStroke(stroke).setDisplayId(displayId).build()
        val sent = service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) = log("tap $x,$y on display $displayId completed")
            override fun onCancelled(g: GestureDescription?) = log("tap $x,$y on display $displayId cancelled")
        }, null)
        log("tap dispatch sent=$sent")
    }

    private fun withNode(displayId: Int, text: String?, editable: Boolean = false, act: (AccessibilityNodeInfo) -> Boolean) {
        val roots = service.windowsOnAllDisplays.get(displayId)?.mapNotNull { it.root }.orEmpty()
        val node = roots.firstNotNullOfOrNull { root -> find(root) { n ->
            if (editable) n.isEditable
            else text != null && (n.text?.toString()?.contains(text, true) == true ||
                n.contentDescription?.toString()?.contains(text, true) == true)
        } }
        if (node == null) return log("no node for '${text ?: "editable"}' on display $displayId")
        var target: AccessibilityNodeInfo? = node
        if (!editable) while (target != null && !target.isClickable) target = target.parent
        log("action on '${node.text ?: node.contentDescription}' → ${act(target ?: node)}")
    }

    private fun find(node: AccessibilityNodeInfo, match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (match(node)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            find(child, match)?.let { return it }
        }
        return null
    }

    private fun log(msg: String) {
        Log.i(TAG, msg)
    }

    companion object {
        const val ACTION = "dev.beka.SPIKE_DISPLAY"
        private const val TAG = "BekaSpike"
    }
}
