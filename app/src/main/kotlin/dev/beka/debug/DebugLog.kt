package dev.beka.debug

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** In-memory ring of recent model calls, dumped into debug reports. */
object DebugLog {
    private const val MAX = 50
    private val json = Json { encodeDefaults = true }
    private val entries = ArrayDeque<Entry>()

    @Serializable
    data class Entry(
        val time: Long,
        val provider: String,
        val model: String,
        val app: String?,
        val userText: String,
        val screenChars: Int,
        val withScreenshot: Boolean,
        val response: String,
        val inputTokens: Int = 0,
        val outputTokens: Int = 0,
        val durationMs: Long,
        val error: String? = null,
    )

    @Synchronized
    fun add(entry: Entry) {
        if (entries.size >= MAX) entries.removeFirst()
        entries.addLast(entry)
    }

    @Synchronized
    fun toJsonLines(): String = entries.joinToString("\n") { json.encodeToString(Entry.serializer(), it) }
}
