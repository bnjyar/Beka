package dev.beka.llm

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

enum class Role { USER, ASSISTANT }

sealed interface Part {
    data class Text(val text: String) : Part
    data class Image(val base64: String, val mediaType: String = "image/jpeg") : Part

    /** A tool call the model made (assistant messages only). [argsJson] is a JSON object. */
    data class ToolUse(val id: String, val name: String, val argsJson: String) : Part

    /** The result of a [ToolUse] (user messages only). Adapters place these before other parts. */
    data class ToolResult(val toolUseId: String, val content: String, val isError: Boolean = false) : Part
}

data class Message(val role: Role, val parts: List<Part>) {
    val text: String get() = parts.filterIsInstance<Part.Text>().joinToString("\n") { it.text }

    companion object {
        fun user(text: String) = Message(Role.USER, listOf(Part.Text(text)))
        fun assistant(text: String) = Message(Role.ASSISTANT, listOf(Part.Text(text)))
    }
}

data class ChatRequest(
    val model: String,
    val system: String?,
    val messages: List<Message>,
    val maxTokens: Int = 2048,
    val temperature: Double? = null,
    val tools: List<ToolSpec> = emptyList(),
)

/** A function the model may call. [parameters] is a JSON Schema object. */
data class ToolSpec(val name: String, val description: String, val parameters: JsonObject)

sealed interface ChatEvent {
    data class TextDelta(val text: String) : ChatEvent
    /** Emitted once the call's arguments are complete. [argsJson] is "{}" when the model sent none. */
    data class ToolCall(val id: String, val name: String, val argsJson: String) : ChatEvent
    data class Usage(val inputTokens: Int, val outputTokens: Int) : ChatEvent
    data class Done(val stopReason: String?) : ChatEvent
}

data class ModelInfo(val id: String)

class LlmException(message: String, val status: Int? = null) : Exception(message)

enum class ProviderKind { OPENAI_COMPATIBLE, ANTHROPIC_COMPATIBLE }

/**
 * A configured endpoint. The API key is stored separately (encrypted) and never lives here.
 *
 * baseUrl conventions:
 * - OPENAI_COMPATIBLE: includes the version segment, e.g. https://api.openai.com/v1
 * - ANTHROPIC_COMPATIBLE: without /v1, e.g. https://api.anthropic.com
 */
@Serializable
data class ProviderConfig(
    val id: String,
    val name: String,
    val kind: ProviderKind,
    val baseUrl: String,
    val model: String,
    val extraHeaders: Map<String, String> = emptyMap(),
)
