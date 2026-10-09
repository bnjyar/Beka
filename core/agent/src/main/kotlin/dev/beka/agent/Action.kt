package dev.beka.agent

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

/** User-facing (Indonesian) one-liner for an action. [label] is the target element's label, if known. */
fun Action.describe(label: String?): String {
    val target = label?.let { "“${it.take(40)}”" } ?: targetNode.orEmpty()
    return when (this) {
        is Action.Tap -> (if (long) "Tekan lama " else "Ketuk ") + target
        is Action.Type -> "Ketik “${text.take(40)}” di $target" + if (pressEnter) " lalu Enter" else ""
        is Action.Scroll -> "Gulir ke " + when (direction) {
            Direction.UP -> "atas"
            Direction.DOWN -> "bawah"
            Direction.LEFT -> "kiri"
            Direction.RIGHT -> "kanan"
        }
        is Action.Global -> when (kind) {
            GlobalKind.BACK -> "Kembali"
            GlobalKind.HOME -> "Ke layar utama"
            GlobalKind.RECENTS -> "Buka app terbaru"
            GlobalKind.NOTIFICATIONS -> "Buka notifikasi"
        }
        is Action.OpenApp -> "Buka app $name"
        is Action.OpenLink -> "Buka link ${uri.take(60)}"
        is Action.Media -> when (command) {
            MediaCommand.PLAY -> "Putar media"
            MediaCommand.PAUSE -> "Jeda media"
            MediaCommand.NEXT -> "Lagu berikutnya"
            MediaCommand.PREVIOUS -> "Lagu sebelumnya"
        }
        is Action.ReadScreen -> if (fullPage) "Baca seluruh halaman" else "Baca layar"
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
