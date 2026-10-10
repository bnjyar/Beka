package dev.beka.accessibility

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import dev.beka.agent.Action
import dev.beka.agent.Blocklist
import dev.beka.agent.ActionFailed
import dev.beka.agent.Device
import dev.beka.agent.Direction
import dev.beka.agent.GlobalKind
import dev.beka.agent.Observation
import dev.beka.display.DisplayHostService
import dev.beka.display.VirtualScreen
import dev.beka.media.MediaState
import kotlinx.coroutines.delay

/**
 * [Device] over the accessibility service: node actions first, gestures as fallback.
 * With a [background] screen it works on Beka's virtual display instead of the user's screen (fase 9).
 */
class AgentDevice(private val media: MediaState, private val background: VirtualScreen? = null) : Device {
    private val service: BekaAccessibilityService
        get() = BekaAccessibilityService.instance ?: throw ActionFailed("Accessibility service is off")

    /** Set by [release]: from then on nothing may open the display again. */
    @Volatile
    private var released = false

    /** The display actions land on. */
    private val display: Int
        get() = when {
            background == null -> Display.DEFAULT_DISPLAY
            released -> throw ActionFailed("The background screen was released")
            else -> background.displayId ?: background.open() ?: throw ActionFailed("The background screen is not available")
        }

    override suspend fun observe(screenshot: Boolean): Observation? {
        val s = BekaAccessibilityService.instance ?: return null
        val d = runCatching { display }.getOrElse { return null }
        background?.ensureAwake()
        // Mid-transition the window list can be briefly empty; give it a moment before reporting unreadable.
        var tree = s.captureTree(d)
        var tries = 0
        while ((tree == null || tree.nodeCount == 0) && tries++ < 4) {
            if (background != null && background.packages().isEmpty()) return emptyBackground()
            delay(400)
            tree = s.captureTree(d)
        }
        val captured = tree ?: return if (background != null) emptyBackground() else null
        val jpeg = if (screenshot && !Blocklist.isBlocked(captured.packageName)) s.captureScreenshot(displayId = d) else null
        return captured.copy(screenshotJpeg = jpeg).toObservation(media.describe())
    }

    private fun emptyBackground() = Observation(
        packageName = "",
        appLabel = "Background screen",
        text = "(empty — no app is open here yet; use open_app or open_link)",
        media = media.describe(),
    )

    override suspend fun readFullPage(): String? {
        val root = BekaAccessibilityService.instance?.targetRoot(display) ?: return null
        return ScreenSerializer.pageText(root).takeIf { it.isNotBlank() }
    }

    /**
     * Ends a background run's use of the display, like freeing memory: its apps go back to the main
     * screen (behind what the user has in front), the wake lock and the display are released, and
     * this device can no longer open it. Safe to call twice.
     */
    fun release() {
        val screen = background ?: return
        if (released) return
        released = true
        Log.i(DisplayHostService.TAG, "returned ${screen.returnBorrowed()} app(s) to the main screen")
        screen.holdAwake(false)
        screen.close()
        Log.i(DisplayHostService.TAG, "background screen released")
    }

    override fun label(nodeId: String): String? =
        BekaAccessibilityService.instance?.lastNodes?.get(nodeId)?.let(ScreenSerializer::label)

    private fun launcherApps(): List<AppResolver.App> {
        val pm = BekaAccessibilityService.instance?.packageManager ?: return emptyList()
        return AppResolver.launcherApps(pm)
    }

    /**
     * A text field, or a wrapper around one: some apps put the field's hint on a clickable node
     * over their EditText ("Kirim pesan ke Math" in Cue), and the field itself may not be its child
     * in the tree. Any field on screen covering most of the node counts, so a whole input bar that
     * also holds the send button does not.
     */
    override fun isEditable(nodeId: String): Boolean {
        val service = BekaAccessibilityService.instance ?: return false
        val node = service.lastNodes[nodeId] ?: return false
        if (node.isEditable) return true
        val outer = Rect().also(node::getBoundsInScreen)
        val area = { r: Rect -> r.width().toLong() * r.height() }
        if (area(outer) <= 0) return false
        val field = service.targetRoot(display)?.findDescendant { n ->
            (n.isEditable || n.className?.toString() == "android.widget.EditText") &&
                Rect().also(n::getBoundsInScreen).let { r -> r.intersect(outer) && area(r) * 2 >= area(outer) }
        }
        return field != null
    }

    override fun resolveApp(name: String): String? = AppResolver.resolve(launcherApps(), name)

    /** Installed apps sharing a word with [name], so the model can retry with the right label. */
    private fun similarApps(name: String): List<String> {
        val words = name.lowercase().split(Regex("\\W+")).filter { it.length >= 2 }
        return launcherApps()
            .filter { app -> words.any { it in app.label.lowercase() || it in app.pkg } }
            .map { it.label }
            .take(10)
    }

    override suspend fun perform(action: Action): String {
        val s = service
        background?.ensureAwake()
        val result = when (action) {
            is Action.Tap -> tap(s, node(action.nodeId), action.long)
            is Action.Type -> type(s, node(action.nodeId), action.text, action.pressEnter)
            is Action.Scroll -> scroll(s, action.nodeId?.let(::node), action.direction)
            is Action.Global -> global(s, action.kind)
            is Action.OpenApp -> openApp(s, action.name)
            is Action.OpenLink -> openLink(s, action.uri)
            is Action.ReadScreen -> "Screen read"
            is Action.Media -> media.control(action.command)
            is Action.External -> throw ActionFailed("Not a phone action")
        }
        val slow = action is Action.OpenApp || action is Action.OpenLink
        val waitStart = SystemClock.uptimeMillis()
        s.awaitIdle(minMs = if (slow) 800 else 250, maxMs = if (slow) 5000 else 3000)
        Log.i(DisplayHostService.TAG, "${action::class.simpleName} settled in ${SystemClock.uptimeMillis() - waitStart} ms")
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
        if (!s.gestureTap(bounds.exactCenterX(), bounds.exactCenterY(), long, display)) throw ActionFailed("Tap gesture was rejected")
        return if (long) "Long-pressed (gesture)" else "Tapped (gesture)"
    }

    private suspend fun type(s: BekaAccessibilityService, node: AccessibilityNodeInfo, text: String, pressEnter: Boolean): String {
        val field = node.takeIf { it.isEditable } ?: node.findDescendant { it.isEditable }
            ?: focusFieldBehind(s, node)
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
            ?: s.targetRoot(display)?.largestScrollable()
        val directional = when (direction) {
            Direction.UP -> AccessibilityAction.ACTION_SCROLL_UP
            Direction.DOWN -> AccessibilityAction.ACTION_SCROLL_DOWN
            Direction.LEFT -> AccessibilityAction.ACTION_SCROLL_LEFT
            Direction.RIGHT -> AccessibilityAction.ACTION_SCROLL_RIGHT
        }
        if (target != null && directional in target.actionList && target.performAction(directional.id)) return "Scrolled"

        // Generic forward/backward only when the list says which way it runs: on a horizontal pager
        // "forward" turns the page (Instagram's "scroll down" opened DMs). Otherwise swipe for real.
        val vertical = direction == Direction.UP || direction == Direction.DOWN
        val info = target?.collectionInfo
        val runsThatWay = info != null &&
            if (vertical) info.rowCount > 1 && info.columnCount <= 1 else info.columnCount > 1 && info.rowCount <= 1
        if (target != null && runsThatWay) {
            val generic = if (direction == Direction.DOWN || direction == Direction.RIGHT) {
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            } else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            if (target.performAction(generic)) return "Scrolled"
        }
        val area = Rect().also { r -> (target ?: s.targetRoot(display))?.getBoundsInScreen(r) }
        if (area.isEmpty) throw ActionFailed("Nothing to scroll")
        val cx = area.exactCenterX()
        val cy = area.exactCenterY()
        val dx = area.width() * 0.3f
        val dy = area.height() * 0.3f
        val ok = when (direction) {
            Direction.DOWN -> s.gestureSwipe(cx, cy + dy, cx, cy - dy, display)
            Direction.UP -> s.gestureSwipe(cx, cy - dy, cx, cy + dy, display)
            Direction.RIGHT -> s.gestureSwipe(cx + dx, cy, cx - dx, cy, display)
            Direction.LEFT -> s.gestureSwipe(cx - dx, cy, cx + dx, cy, display)
        }
        if (!ok) throw ActionFailed("Scroll gesture was rejected")
        return "Swiped"
    }

    private fun global(s: BekaAccessibilityService, kind: GlobalKind): String {
        if (background != null) {
            if (kind != GlobalKind.BACK) throw ActionFailed("There is no ${kind.name.lowercase()} on the background screen. Use open_app to switch apps.")
            if (!background.key(KeyEvent.KEYCODE_BACK)) throw ActionFailed("Back did not work on the background screen")
            return "Done"
        }
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
        val pkg = resolveApp(name) ?: throw ActionFailed(
            "No installed app matches \"$name\"." + similarApps(name).let { if (it.isEmpty()) "" else " Similar: ${it.joinToString()}" }
        )
        val intent = s.packageManager.getLaunchIntentForPackage(pkg) ?: throw ActionFailed("$pkg cannot be launched")
        start(s, intent)
        return "Opened $pkg"
    }

    /** On the user's screen as usual, or on the background one, taking the app over if the user has it open. */
    private fun start(s: BekaAccessibilityService, intent: Intent) {
        if (background == null) {
            s.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        // A link's target app is resolved here so the host can move that app's open task over.
        if (intent.`package` == null && intent.component == null) {
            s.packageManager.resolveActivity(intent, 0)?.activityInfo?.packageName
                ?.takeIf { it != "android" }?.let(intent::setPackage)
        }
        background.launch(intent)?.let { throw ActionFailed("Could not open it on the background screen: $it") }
    }

    private fun openLink(s: BekaAccessibilityService, uri: String): String {
        if (SETTINGS_ACTION.matches(uri.trim())) {
            try {
                start(s, Intent(uri.trim()))
            } catch (e: ActivityNotFoundException) {
                throw ActionFailed("This phone has no settings page $uri")
            }
            return "Opened settings page"
        }
        val parsed = Uri.parse(uri.trim())
        val scheme = parsed.scheme?.lowercase() ?: throw ActionFailed("Link has no scheme: $uri")
        if (scheme in BLOCKED_SCHEMES) throw ActionFailed("$scheme: links are not allowed")
        try {
            start(s, Intent(Intent.ACTION_VIEW, parsed))
        } catch (e: ActivityNotFoundException) {
            throw ActionFailed("No app can open $uri")
        }
        return "Opened link"
    }

    /**
     * Some apps show only a hint ("Kirim pesan ke Math" in Cue) and create the real field once it
     * is tapped. Tapping it here keeps focusing part of typing, so it never asks for confirmation.
     * Only wide, non-button targets are tapped, so typing "into" a send button cannot press it.
     */
    private suspend fun focusFieldBehind(s: BekaAccessibilityService, node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.selfOrAncestor { it.isClickable } == null) return null
        if (node.className?.toString()?.endsWith("Button") == true) return null
        val bounds = Rect().also(node::getBoundsInScreen)
        if (bounds.width() * 2 < s.resources.displayMetrics.widthPixels) return null
        tap(s, node, long = false)
        repeat(10) {
            delay(150)
            s.targetRoot(display)?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }?.let { return it }
        }
        return null
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
        val SETTINGS_ACTION = Regex("""android\.settings\.[A-Z_]+""")
        val BLOCKED_SCHEMES = setOf("intent", "file", "content", "javascript", "android-app")
    }
}

fun ScreenContext.toObservation(media: String? = null) = Observation(
    packageName = packageName,
    appLabel = appLabel,
    text = "Visible elements ($nodeCount):\n" +
        tree.ifBlank { "(no readable elements — the app may draw its own UI; rely on the screenshot if present)" },
    jpeg = screenshotJpeg,
    media = media,
)
