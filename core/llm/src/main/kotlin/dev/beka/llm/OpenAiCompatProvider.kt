package dev.beka.llm

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request

/** Chat Completions API: OpenAI, OpenRouter, Groq, Gemini (compat), Ollama, LM Studio, ... */
class OpenAiCompatProvider(
    override val config: ProviderConfig,
    private val apiKey: String?,
    private val client: OkHttpClient,
) : LlmProvider {

    private val base = config.baseUrl.trimEnd('/')
    private val isOpenAi = "api.openai.com" in base

    private fun headers(): Headers = Headers.Builder().apply {
        if (!apiKey.isNullOrBlank()) add("Authorization", "Bearer $apiKey")
        if ("openrouter.ai" in base) {
            add("HTTP-Referer", "https://github.com/bnjyar/Beka")
            add("X-Title", "Beka")
        }
        config.extraHeaders.forEach { (k, v) -> add(k, v) }
    }.build()

    override suspend fun listModels(): List<ModelInfo> {
        val request = Request.Builder().url("$base/models").headers(headers()).build()
        val root = client.getJson(request).jsonObject
        return (root["data"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonObject)?.str("id") }
            .sorted()
            .map(::ModelInfo)
    }

    override fun chat(request: ChatRequest): Flow<ChatEvent> = flow {
        val http = Request.Builder()
            .url("$base/chat/completions")
            .headers(headers())
            .post(requestBody(request).toBody())
            .build()

        var stopReason: String? = null
        client.sse(http).collect { event ->
            if (event.data == "[DONE]") return@collect
            val obj = json.parseToJsonElement(event.data).jsonObject
            obj["error"]?.let { throw LlmException(it.toString()) }
            (obj["choices"] as? JsonArray)?.firstOrNull()?.jsonObject?.let { choice ->
                choice.obj("delta")?.str("content")?.let { if (it.isNotEmpty()) emit(ChatEvent.TextDelta(it)) }
                choice.str("finish_reason")?.let { stopReason = it }
            }
            obj.obj("usage")?.let { emit(ChatEvent.Usage(it.int("prompt_tokens"), it.int("completion_tokens"))) }
        }
        emit(ChatEvent.Done(stopReason))
    }

    internal fun requestBody(request: ChatRequest): JsonObject = buildJsonObject {
        put("model", request.model)
        put("stream", true)
        // OpenAI's newer models reject max_tokens; most compatible servers only know max_tokens.
        put(if (isOpenAi) "max_completion_tokens" else "max_tokens", request.maxTokens)
        request.temperature?.let { put("temperature", it) }
        if (isOpenAi) putJsonObject("stream_options") { put("include_usage", true) }
        putJsonArray("messages") {
            request.system?.let { addJsonObject { put("role", "system"); put("content", it) } }
            request.messages.forEach { message ->
                addJsonObject {
                    put("role", if (message.role == Role.USER) "user" else "assistant")
                    put("content", content(message))
                }
            }
        }
    }

    private fun content(message: Message): JsonElement {
        if (message.parts.all { it is Part.Text }) return JsonPrimitive(message.text)
        return buildJsonArray {
            message.parts.forEach { part ->
                when (part) {
                    is Part.Text -> addJsonObject { put("type", "text"); put("text", part.text) }
                    is Part.Image -> addJsonObject {
                        put("type", "image_url")
                        putJsonObject("image_url") { put("url", "data:${part.mediaType};base64,${part.base64}") }
                    }
                }
            }
        }
    }
}
