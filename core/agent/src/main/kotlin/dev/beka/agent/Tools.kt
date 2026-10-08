package dev.beka.agent

import dev.beka.llm.ToolSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** The tools offered to the model, and parsing of its calls into [Action]s. */
object Tools {
    private val json = Json { ignoreUnknownKeys = true }

    val specs: List<ToolSpec> = listOf(
        tool(
            "read_screen",
            "Read the current screen again, e.g. after waiting for something to load. Set full_page=true to get the " +
                "text of the whole page including what is scrolled out of view (articles, web pages) instead of scrolling through it.",
        ) {
            bool("full_page", "Return all text on the page, including off-screen parts")
        },
        tool("tap", "Tap an element by its id from the latest <screen>.", required = listOf("id")) {
            string("id", "Element id, e.g. n12")
        },
        tool("long_press", "Long-press an element by its id.", required = listOf("id")) {
            string("id", "Element id, e.g. n12")
        },
        tool(
            "type",
            "Replace the text of an input field. Give the field itself or its placeholder/hint; it is focused for you, so do not tap it first. Set press_enter=true to submit it (e.g. a search box).",
            required = listOf("id", "text"),
        ) {
            string("id", "Id of an Input element")
            string("text", "The full text the field should contain")
            bool("press_enter", "Press the keyboard's enter/search key afterwards")
        },
        tool("scroll", "Scroll to reveal more content. Without id, scrolls the main list on screen.", required = listOf("direction")) {
            enum("direction", "down reveals content below, up reveals content above", listOf("up", "down", "left", "right"))
            string("id", "Id of a scrollable element (optional)")
        },
        tool(
            "media_control",
            "Control whatever is playing (the Media line in <screen>) without touching the app's buttons.",
            required = listOf("command"),
        ) {
            enum("command", "What to do", listOf("play", "pause", "next", "previous"))
        },
        tool("back", "Press the system back button.") {},
        tool("home", "Go to the home screen.") {},
        tool("recents", "Open the recent apps view.") {},
        tool("notifications", "Pull down the notification shade.") {},
        tool(
            "open_app",
            "Launch an installed app by name (e.g. \"WhatsApp\", \"YouTube Music\"). Always use this to start an app; " +
                "never look for it on the home screen or app drawer. If nothing matches, the result lists similar apps.",
            required = listOf("name"),
        ) {
            string("name", "App name as shown in the launcher")
        },
        tool(
            "open_link",
            "Open a URL or deep link. Useful shortcuts: https://wa.me/<number in international format>?text=<urlencoded> " +
                "opens a WhatsApp chat with a draft message; https://www.google.com/maps/search/?api=1&query=<q> opens Maps; " +
                "https://www.youtube.com/results?search_query=<q> searches YouTube. " +
                "Settings pages open by action name, e.g. android.settings.BLUETOOTH_SETTINGS, android.settings.WIFI_SETTINGS, " +
                "android.settings.DISPLAY_SETTINGS, android.settings.SOUND_SETTINGS, android.settings.LOCATION_SOURCE_SETTINGS.",
            required = listOf("uri"),
        ) {
            string("uri", "http(s) URL, app deep link, or android.settings.* action")
        },
    )

    /** @throws IllegalArgumentException with a message meant for the model. */
    fun parse(name: String, argsJson: String): Action {
        val args = runCatching { json.parseToJsonElement(argsJson.ifBlank { "{}" }).jsonObject }
            .getOrElse { throw IllegalArgumentException("Arguments are not a JSON object: ${argsJson.take(200)}") }
        return when (name) {
            "read_screen" -> Action.ReadScreen(args.bool("full_page"))
            "tap" -> Action.Tap(args.nodeId("id"))
            "long_press" -> Action.Tap(args.nodeId("id"), long = true)
            "type" -> Action.Type(args.nodeId("id"), args.string("text") ?: missing("text"), args.bool("press_enter"))
            "scroll" -> Action.Scroll(
                direction = when (args.string("direction")?.lowercase()) {
                    "up" -> Direction.UP
                    "down" -> Direction.DOWN
                    "left" -> Direction.LEFT
                    "right" -> Direction.RIGHT
                    else -> throw IllegalArgumentException("direction must be one of up, down, left, right")
                },
                nodeId = args.string("id")?.takeIf { it.isNotBlank() }?.let(::normalizeId),
            )
            "media_control" -> Action.Media(
                when (args.string("command")?.lowercase()) {
                    "play" -> MediaCommand.PLAY
                    "pause" -> MediaCommand.PAUSE
                    "next" -> MediaCommand.NEXT
                    "previous" -> MediaCommand.PREVIOUS
                    else -> throw IllegalArgumentException("command must be one of play, pause, next, previous")
                }
            )
            "back" -> Action.Global(GlobalKind.BACK)
            "home" -> Action.Global(GlobalKind.HOME)
            "recents" -> Action.Global(GlobalKind.RECENTS)
            "notifications" -> Action.Global(GlobalKind.NOTIFICATIONS)
            "open_app" -> Action.OpenApp(args.string("name")?.takeIf { it.isNotBlank() } ?: missing("name"))
            "open_link" -> Action.OpenLink(args.string("uri")?.takeIf { it.isNotBlank() } ?: missing("uri"))
            else -> throw IllegalArgumentException("Unknown tool: $name")
        }
    }

    /** Accepts "n12", "12" or "[n12]". */
    fun normalizeId(raw: String): String {
        val digits = raw.trim().removePrefix("[").removeSuffix("]").removePrefix("n").removePrefix("N")
        require(digits.isNotEmpty() && digits.all(Char::isDigit)) { "Invalid element id: $raw (expected something like n12)" }
        return "n$digits"
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.bool(key: String): Boolean =
        (this[key] as? JsonPrimitive)?.let { it.booleanOrNull ?: (it.contentOrNull == "true") } ?: false

    private fun JsonObject.nodeId(key: String): String = normalizeId(string(key) ?: missing(key))

    private fun missing(key: String): Nothing = throw IllegalArgumentException("Missing argument: $key")

    private class Params {
        val properties = mutableMapOf<String, JsonObject>()

        fun string(name: String, description: String) {
            properties[name] = buildJsonObject { put("type", "string"); put("description", description) }
        }

        fun bool(name: String, description: String) {
            properties[name] = buildJsonObject { put("type", "boolean"); put("description", description) }
        }

        fun enum(name: String, description: String, values: List<String>) {
            properties[name] = buildJsonObject {
                put("type", "string")
                put("description", description)
                putJsonArray("enum") { values.forEach { add(JsonPrimitive(it)) } }
            }
        }
    }

    private fun tool(
        name: String,
        description: String,
        required: List<String> = emptyList(),
        params: Params.() -> Unit,
    ): ToolSpec {
        val p = Params().apply(params)
        val schema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") { p.properties.forEach { (k, v) -> put(k, v) } }
            if (required.isNotEmpty()) putJsonArray("required") { required.forEach { add(JsonPrimitive(it)) } }
        }
        return ToolSpec(name, description, schema)
    }
}
