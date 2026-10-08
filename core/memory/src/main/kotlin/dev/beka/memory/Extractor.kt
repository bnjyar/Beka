package dev.beka.memory

import dev.beka.llm.ChatEvent
import dev.beka.llm.ChatRequest
import dev.beka.llm.LlmProvider
import dev.beka.llm.Message
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.time.LocalDate

/** One line of a finished chat session, as extraction reads it. */
data class SessionLine(val kind: Kind, val text: String, val app: String? = null) {
    enum class Kind { USER, ASSISTANT, ACTION }
}

data class FactProposal(val content: String, val confidence: Double, val app: String? = null, val replaces: Long? = null)

data class ProcedureProposal(val app: String, val content: String, val replaces: Long? = null)

data class Extraction(
    val facts: List<FactProposal> = emptyList(),
    val episode: String? = null,
    val procedures: List<ProcedureProposal> = emptyList(),
)

/**
 * End-of-session extraction: a model reads the finished conversation and proposes facts about the
 * user, a short episode summary, and the UI path of tasks that worked. [Memory.apply] stores them.
 */
class Extractor(private val provider: LlmProvider, private val model: String) {
    suspend fun extract(lines: List<SessionLine>, known: List<MemoryItem>, today: LocalDate, line: (MemoryItem) -> String): Extraction {
        val request = ChatRequest(
            model = model,
            system = PROMPT,
            messages = listOf(Message.user(input(lines, known, today, line))),
            maxTokens = 2048,
            temperature = 0.0,
        )
        val reply = StringBuilder()
        provider.chat(request).collect { if (it is ChatEvent.TextDelta) reply.append(it.text) }
        return parse(reply.toString())
    }

    companion object {
        /** The end of a long session matters most; older lines are dropped past this size. */
        const val MAX_TRANSCRIPT = 12_000

        private val json = Json { isLenient = true; ignoreUnknownKeys = true }

        fun input(lines: List<SessionLine>, known: List<MemoryItem>, today: LocalDate, line: (MemoryItem) -> String): String {
            val transcript = lines.joinToString("\n") {
                when (it.kind) {
                    SessionLine.Kind.USER -> "User: ${it.text}"
                    SessionLine.Kind.ASSISTANT -> "Beka: ${it.text}"
                    SessionLine.Kind.ACTION -> "  [action${it.app?.let { a -> " in $a" }.orEmpty()}] ${it.text}"
                }
            }.takeLast(MAX_TRANSCRIPT)
            val memory = if (known.isEmpty()) "(nothing yet)" else known.joinToString("\n") { line(it) }
            return "<known>\n$memory\n</known>\n\n<session date=\"$today\">\n$transcript\n</session>"
        }

        /** Tolerates code fences and chatter around the JSON object; anything unreadable yields nothing. */
        fun parse(text: String): Extraction {
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            if (start < 0 || end <= start) return Extraction()
            val root = runCatching { json.parseToJsonElement(text.substring(start, end + 1)) as? JsonObject }.getOrNull()
                ?: return Extraction()
            val facts = root.array("facts").mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val content = o.string("content") ?: return@mapNotNull null
                FactProposal(
                    content = content,
                    confidence = (o["confidence"] as? JsonPrimitive)?.doubleOrNull?.coerceIn(0.0, 1.0) ?: DEFAULT_CONFIDENCE,
                    app = o.string("app"),
                    replaces = o.id("replaces"),
                )
            }
            val procedures = root.array("procedures").mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val app = o.string("app") ?: return@mapNotNull null
                val steps = o.string("steps") ?: return@mapNotNull null
                val task = o.string("task")
                ProcedureProposal(app, if (task != null) "$task: $steps" else steps, o.id("replaces"))
            }
            return Extraction(facts, root.string("episode"), procedures)
        }

        private const val DEFAULT_CONFIDENCE = 0.7

        private fun JsonObject.array(key: String): List<JsonElement> = (this[key] as? JsonArray).orEmpty()

        private fun JsonObject.string(key: String): String? =
            (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

        private fun JsonObject.id(key: String): Long? = string(key)?.let { runCatching { Memory.parseId(it) }.getOrNull() }

        val PROMPT = """
            You maintain the long-term memory of Beka, an assistant on the user's Android phone. Read the finished session in <session> and the memory Beka already has in <known>, and propose updates.

            Reply with only a JSON object:
            {
              "facts": [{"content": "...", "confidence": 0.9, "app": null, "replaces": null}],
              "episode": "..." or null,
              "procedures": [{"app": "com.example", "task": "...", "steps": "...", "replaces": null}]
            }

            facts — lasting things about the user that will help in future conversations: preferences, habits, routines, people in their life and how they relate to them, places, recurring plans.
            - Only what the user said or what their own requests make clear. Not one-off task details, not the content of screens, apps or web pages, not what Beka said.
            - Skip anything already in <known>. If a fact changes or contradicts a known one, set "replaces" to that id (e.g. "m12").
            - Never store passwords, PINs, OTP codes, card or account numbers, or other secrets.
            - Write each fact short and on its own, in the user's language, without naming the user (e.g. "Suka kopi less sugar", "Adiknya bernama Rina").
            - "app": a package name only when the fact is about that app; otherwise null.
            - confidence: 0.9 or more when the user said it directly; 0.5–0.8 when inferred.
            episode — one or two sentences in the user's language about what was done or discussed, with names and outcomes. null if the session had nothing worth recalling later.
            procedures — for each task Beka completed through actions (lines marked [action]), the path that worked, generalized so it can be reused: e.g. task "kirim pesan ke grup WhatsApp", steps "Chats → ikon cari → ketik nama grup → buka grup → ketik pesan → Kirim". Use the app package shown in the action lines. Leave out failed attempts and specific message text. If <known> has a procedure for the same task, set "replaces" to its id.
            Empty lists are fine. Most sessions have no new facts.
        """.trimIndent()
    }
}
