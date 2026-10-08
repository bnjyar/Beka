package dev.beka.llm

import kotlinx.serialization.Serializable

enum class Role { USER, ASSISTANT }

sealed interface Part {
    data class Text(val text: String) : Part
    data class Image(val base64: String, val mediaType: String = "image/jpeg") : Part
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
)

sealed interface ChatEvent {
    data class TextDelta(val text: String) : ChatEvent
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
