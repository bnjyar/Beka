package dev.beka.accessibility

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import dev.beka.agent.Action
import dev.beka.agent.ActionFailed
import dev.beka.agent.Device
import dev.beka.agent.Direction
import dev.beka.agent.GlobalKind
import dev.beka.agent.Observation

/** [Device] over the accessibility service: node actions first, gestures as fallback. */
class AgentDevice : Device {
    private val service: BekaAccessibilityService
        get() = BekaAccessibilityService.instance ?: throw ActionFailed("Accessibility service is off")

    override suspend fun observe(screenshot: Boolean): Observation? {
        val s = BekaAccessibilityService.instance ?: return null
        val tree = s.captureTree() ?: return null
        val jpeg = if (screenshot) s.captureScreenshot() else null
        return tree.copy(screenshotJpeg = jpeg).toObservation()
    }

    override fun label(nodeId: String): String? =
        BekaAccessibilityService.instance?.lastNodes?.get(nodeId)?.let(ScreenSerializer::label)

    override fun resolveApp(name: String): String? {
        val pm = (BekaAccessibilityService.instance ?: return null).packageManager
        val wanted = name.trim().lowercase()
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString().lowercase() }
        return apps.firstOrNull { it.second == wanted || it.first == wanted }?.first
            ?: apps.firstOrNull { it.second.startsWith(wanted) }?.first
            ?: apps.firstOrNull { wanted in it.second }?.first
    }

    override suspend fun perform(action: Action): String {
        val s = service
        val result = when (action) {
            is Action.Tap -> tap(s, node(action.nodeId), action.long)
            is Action.Type -> type(node(action.nodeId), action.text, action.pressEnter)
            is Action.Scroll -> scroll(s, action.nodeId?.let(::node), action.direction)
            is Action.Global -> global(s, action.kind)
            is Action.OpenApp -> openApp(s, action.name)
            is Action.OpenLink -> openLink(s, action.uri)
            is Action.ReadScreen -> "Screen read"
        }
        val slow = action is Action.OpenApp || action is Action.OpenLink
        s.awaitIdle(minMs = if (slow) 800 else 250, maxMs = if (slow) 5000 else 3000)
        return result
    }

    private fun node(id: String): AccessibilityNodeInfo {
        val node = service.lastNodes[id] ?: throw ActionFailed("Unknown element $id. Use an id from the latest screen.")
        if (!node.refresh()) throw ActionFailed("Element $id is no longer on screen. Read the screen again.")
        return node
    }

    private suspend fun tap(s: BekaAccessibilityService, node: AccessibilityNodeInfo, long: Boolean): String {
        val action = if (long) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK
        val target = node.selfOrAncestor { if (long) it.isLongClickable else it.isClickable }
        if (target != null && target.isEnabled && target.performAction(action)) return if (long) "Long-pressed" else "Tapped"

        val bounds = Rect().also(node::getBoundsInScreen)
        if (bounds.isEmpty) throw ActionFailed("Element has no position on screen")
        if (!s.gestureTap(bounds.exactCenterX(), bounds.exactCenterY(), long)) throw ActionFailed("Tap gesture was rejected")
        return if (long) "Long-pressed (gesture)" else "Tapped (gesture)"
    }

    private fun type(node: AccessibilityNodeInfo, text: String, pressEnter: Boolean): String {
        val field = node.takeIf { it.isEditable } ?: node.findDescendant { it.isEditable }
            ?: throw ActionFailed("That element is not a text field")
        if (!field.isFocused) field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        if (!field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) throw ActionFailed("The field did not accept text")
        if (!pressEnter) return "Typed"
        field.refresh()
        if (!field.performAction(AccessibilityAction.ACTION_IME_ENTER.id)) {
            throw ActionFailed("Typed, but pressing enter failed. Tap the search/send button instead.")
        }
        return "Typed and pressed enter"
    }

    private suspend fun scroll(s: BekaAccessibilityService, node: AccessibilityNodeInfo?, direction: Direction): String {
        val target = node?.selfOrAncestor { it.isScrollable }
            ?: s.targetRoot()?.largestScrollable()
        if (target != null) {
            val directional = when (direction) {
                Direction.UP -> AccessibilityAction.ACTION_SCROLL_UP
                Direction.DOWN -> AccessibilityAction.ACTION_SCROLL_DOWN
                Direction.LEFT -> AccessibilityAction.ACTION_SCROLL_LEFT
                Direction.RIGHT -> AccessibilityAction.ACTION_SCROLL_RIGHT
            }
            val generic = when (direction) {
                Direction.UP, Direction.LEFT -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                Direction.DOWN, Direction.RIGHT -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            }
            if (target.performAction(directional.id) || target.performAction(generic)) return "Scrolled"
        }

        // No scrollable node (custom-drawn UI): swipe across the middle of the target or the screen.
        val area = Rect().also { r -> (target ?: s.targetRoot())?.getBoundsInScreen(r) }
        if (area.isEmpty) throw ActionFailed("Nothing to scroll")
        val cx = area.exactCenterX()
        val cy = area.exactCenterY()
        val dx = area.width() * 0.3f
        val dy = area.height() * 0.3f
        val ok = when (direction) {
            Direction.DOWN -> s.gestureSwipe(cx, cy + dy, cx, cy - dy)
            Direction.UP -> s.gestureSwipe(cx, cy - dy, cx, cy + dy)
            Direction.RIGHT -> s.gestureSwipe(cx + dx, cy, cx - dx, cy)
            Direction.LEFT -> s.gestureSwipe(cx - dx, cy, cx + dx, cy)
        }
        if (!ok) throw ActionFailed("Scroll gesture was rejected")
        return "Swiped"
    }

    private fun global(s: BekaAccessibilityService, kind: GlobalKind): String {
        val id = when (kind) {
            GlobalKind.BACK -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
            GlobalKind.HOME -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
            GlobalKind.RECENTS -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS
            GlobalKind.NOTIFICATIONS -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
        }
        if (!s.performGlobalAction(id)) throw ActionFailed("The system refused ${kind.name.lowercase()}")
        return "Done"
    }

    private fun openApp(s: BekaAccessibilityService, name: String): String {
        val pkg = resolveApp(name) ?: throw ActionFailed("No installed app matches \"$name\"")
        val intent = s.packageManager.getLaunchIntentForPackage(pkg) ?: throw ActionFailed("$pkg cannot be launched")
        s.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return "Opened $pkg"
    }

    private fun openLink(s: BekaAccessibilityService, uri: String): String {
        val parsed = Uri.parse(uri.trim())
        val scheme = parsed.scheme?.lowercase() ?: throw ActionFailed("Link has no scheme: $uri")
        if (scheme in BLOCKED_SCHEMES) throw ActionFailed("$scheme: links are not allowed")
        try {
            s.startActivity(Intent(Intent.ACTION_VIEW, parsed).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            throw ActionFailed("No app can open $uri")
        }
        return "Opened link"
    }

    private fun AccessibilityNodeInfo.selfOrAncestor(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = this
        repeat(6) {
            val n = current ?: return null
            if (predicate(n)) return n
            current = n.parent
        }
        return null
    }

    private fun AccessibilityNodeInfo.findDescendant(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        for (i in 0 until childCount) {
            val child = getChild(i) ?: continue
            if (predicate(child)) return child
            child.findDescendant(predicate)?.let { return it }
        }
        return null
    }

    private fun AccessibilityNodeInfo.largestScrollable(): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestArea = 0
        val rect = Rect()
        fun visit(n: AccessibilityNodeInfo) {
            if (n.isScrollable && n.isVisibleToUser) {
                n.getBoundsInScreen(rect)
                val area = rect.width() * rect.height()
                if (area > bestArea) { best = n; bestArea = area }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let(::visit)
        }
        visit(this)
        return best
    }

    private companion object {
        val BLOCKED_SCHEMES = setOf("intent", "file", "content", "javascript", "android-app")
    }
}

fun ScreenContext.toObservation() = Observation(
    packageName = packageName,
    appLabel = appLabel,
    text = "Visible elements ($nodeCount):\n" +
        tree.ifBlank { "(no readable elements — the app may draw its own UI; rely on the screenshot if present)" },
    jpeg = screenshotJpeg,
)
