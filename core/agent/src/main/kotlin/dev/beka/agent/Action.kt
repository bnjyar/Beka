package dev.beka.agent

import dev.beka.llm.Lang
import dev.beka.llm.tr

/** Something the agent does on the phone. Node ids (`n12`) refer to the latest observed screen. */
sealed interface Action {
    data class Tap(val nodeId: String, val long: Boolean = false) : Action
    data class Type(val nodeId: String, val text: String, val pressEnter: Boolean = false) : Action
    data class Scroll(val direction: Direction, val nodeId: String? = null) : Action
    data class Global(val kind: GlobalKind) : Action
    data class OpenApp(val name: String) : Action
    data class OpenLink(val uri: String) : Action
    data class ReadScreen(val fullPage: Boolean = false) : Action
    data class Media(val command: MediaCommand) : Action

    /** A confirmation for a [ToolKit] call; never produced by [Tools.parse]. */
    data class External(val tool: String, val summary: String) : Action
}

enum class MediaCommand { PLAY, PAUSE, NEXT, PREVIOUS }

/** Which way the user wants to move through content: DOWN reveals what is below. */
enum class Direction { UP, DOWN, LEFT, RIGHT }

enum class GlobalKind { BACK, HOME, RECENTS, NOTIFICATIONS }

/** User-facing one-liner for an action, in [Lang.current]. [label] is the target element's label, if known. */
fun Action.describe(label: String?): String {
    val target = label?.let { "“${it.take(40)}”" } ?: targetNode.orEmpty()
    return when (this) {
        is Action.Tap -> (if (long) tr("Long-press ", "Tekan lama ") else tr("Tap ", "Ketuk ")) + target
        is Action.Type -> tr("Type “${text.take(40)}” in $target", "Ketik “${text.take(40)}” di $target") +
            if (pressEnter) tr(" then Enter", " lalu Enter") else ""
        is Action.Scroll -> when (direction) {
            Direction.UP -> tr("Scroll up", "Gulir ke atas")
            Direction.DOWN -> tr("Scroll down", "Gulir ke bawah")
            Direction.LEFT -> tr("Scroll left", "Gulir ke kiri")
            Direction.RIGHT -> tr("Scroll right", "Gulir ke kanan")
        }
        is Action.Global -> when (kind) {
            GlobalKind.BACK -> tr("Back", "Kembali")
            GlobalKind.HOME -> tr("Go to the home screen", "Ke layar utama")
            GlobalKind.RECENTS -> tr("Open recent apps", "Buka app terbaru")
            GlobalKind.NOTIFICATIONS -> tr("Open notifications", "Buka notifikasi")
        }
        is Action.OpenApp -> tr("Open $name", "Buka app $name")
        is Action.OpenLink -> tr("Open link ${uri.take(60)}", "Buka link ${uri.take(60)}")
        is Action.Media -> when (command) {
            MediaCommand.PLAY -> tr("Play media", "Putar media")
            MediaCommand.PAUSE -> tr("Pause media", "Jeda media")
            MediaCommand.NEXT -> tr("Next track", "Lagu berikutnya")
            MediaCommand.PREVIOUS -> tr("Previous track", "Lagu sebelumnya")
        }
        is Action.ReadScreen -> if (fullPage) tr("Read the whole page", "Baca seluruh halaman") else tr("Read the screen", "Baca layar")
        is Action.External -> summary
    }
}

/** The element an action is aimed at, if any. */
val Action.targetNode: String?
    get() = when (this) {
        is Action.Tap -> nodeId
        is Action.Type -> nodeId
        is Action.Scroll -> nodeId
        else -> null
    }
