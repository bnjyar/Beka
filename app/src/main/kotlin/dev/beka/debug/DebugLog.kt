package dev.beka.debug

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** In-memory ring of recent model calls and agent actions, dumped into debug reports. */
object DebugLog {
    private const val MAX = 200
    private val json = Json { encodeDefaults = true }
    private val entries = ArrayDeque<Pair<Long, String>>()

    @Serializable
    data class Entry(
        val type: String = "model",
        val time: Long,
        val provider: String,
        val model: String,
        val app: String?,
        val userText: String,
        val screenChars: Int,
        /** Whether screenshots were sent to the model during the run. */
        val withScreenshot: Boolean,
        /** The model refused an image and the run went on without them. */
        val visionRejected: Boolean = false,
        val response: String,
        val inputTokens: Int = 0,
        val outputTokens: Int = 0,
        val durationMs: Long,
        val error: String? = null,
        /** Agent actions attempted during this turn. */
        val steps: Int = 0,
    )

    @Serializable
    data class ActionEntry(
        val type: String = "action",
        val time: Long,
        val step: Int,
        val app: String?,
        val action: String,
        val description: String,
        val risk: String,
        val outcome: String,
        val ok: Boolean,
        val durationMs: Long,
    )

    fun add(entry: Entry) = push(entry.time, json.encodeToString(Entry.serializer(), entry))

    fun add(entry: ActionEntry): String = json.encodeToString(ActionEntry.serializer(), entry).also { push(entry.time, it) }

    @Synchronized
    private fun push(time: Long, line: String) {
        if (entries.size >= MAX) entries.removeFirst()
        entries.addLast(time to line)
    }

    @Synchronized
    fun toJsonLines(): String = entries.sortedBy { it.first }.joinToString("\n") { it.second }
}
