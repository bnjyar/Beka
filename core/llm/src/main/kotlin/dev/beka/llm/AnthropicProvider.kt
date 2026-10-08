package dev.beka.llm

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request

/** Anthropic Messages API, and servers that mirror it. */
class AnthropicProvider(
    override val config: ProviderConfig,
    private val apiKey: String?,
    private val client: OkHttpClient,
) : LlmProvider {

    private val base = config.baseUrl.trimEnd('/').removeSuffix("/v1")

    private fun headers(): Headers = Headers.Builder().apply {
        if (!apiKey.isNullOrBlank()) add("x-api-key", apiKey)
        add("anthropic-version", "2023-06-01")
        config.extraHeaders.forEach { (k, v) -> add(k, v) }
    }.build()

    override suspend fun listModels(): List<ModelInfo> {
        val request = Request.Builder().url("$base/v1/models?limit=100").headers(headers()).build()
        val root = client.getJson(request).jsonObject
        return (root["data"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonObject)?.str("id") }
            .map(::ModelInfo)
    }

    override fun chat(request: ChatRequest): Flow<ChatEvent> = flow {
        val http = Request.Builder()
            .url("$base/v1/messages")
            .headers(headers())
            .post(requestBody(request).toBody())
            .build()

        var inputTokens = 0
        var outputTokens = 0
        var stopReason: String? = null
        val toolBlocks = mutableMapOf<Int, Pair<String, String>>()
        val toolArgs = mutableMapOf<Int, StringBuilder>()
        client.sse(http).collect { event ->
            val obj = json.parseToJsonElement(event.data).jsonObject
            when (obj.str("type")) {
                "message_start" -> obj.obj("message")?.obj("usage")?.let { inputTokens = it.int("input_tokens") }
                "content_block_start" -> obj.obj("content_block")?.let { block ->
                    if (block.str("type") == "tool_use") {
                        toolBlocks[obj.int("index")] = Pair(block.str("id").orEmpty(), block.str("name").orEmpty())
                    }
                }
                "content_block_delta" -> obj.obj("delta")?.let { delta ->
                    when (delta.str("type")) {
                        "text_delta" -> delta.str("text")?.let { emit(ChatEvent.TextDelta(it)) }
                        "input_json_delta" -> delta.str("partial_json")?.let {
                            toolArgs.getOrPut(obj.int("index")) { StringBuilder() }.append(it)
                        }
                    }
                }
                "content_block_stop" -> {
                    val index = obj.int("index")
                    toolBlocks.remove(index)?.let { (id, name) ->
                        val args = toolArgs.remove(index)?.toString().orEmpty()
                        emit(ChatEvent.ToolCall(id, name, args.ifBlank { "{}" }))
                    }
                }
                "message_delta" -> {
                    obj.obj("delta")?.str("stop_reason")?.let { stopReason = it }
                    obj.obj("usage")?.let { outputTokens = it.int("output_tokens") }
                }
                "error" -> throw LlmException(obj.obj("error")?.str("message") ?: event.data)
            }
        }
        emit(ChatEvent.Usage(inputTokens, outputTokens))
        emit(ChatEvent.Done(stopReason))
    }

    internal fun requestBody(request: ChatRequest): JsonObject = buildJsonObject {
        put("model", request.model)
        put("max_tokens", request.maxTokens)
        put("stream", true)
        request.temperature?.let { put("temperature", it) }
        request.system?.let { put("system", it) }
        if (request.tools.isNotEmpty()) putJsonArray("tools") {
            request.tools.forEach { tool ->
                addJsonObject {
                    put("name", tool.name)
                    put("description", tool.description)
                    put("input_schema", tool.parameters)
                }
            }
        }
        putJsonArray("messages") {
            request.messages.forEach { message ->
                addJsonObject {
                    put("role", if (message.role == Role.USER) "user" else "assistant")
                    putJsonArray("content") {
                        val ordered = message.parts.filterIsInstance<Part.ToolResult>() +
                            message.parts.filter { it !is Part.ToolResult }
                        ordered.forEach { part ->
                            when (part) {
                                is Part.Text -> if (part.text.isNotBlank()) {
                                    addJsonObject { put("type", "text"); put("text", part.text) }
                                }
                                is Part.Image -> addJsonObject {
                                    put("type", "image")
                                    putJsonObject("source") {
                                        put("type", "base64")
                                        put("media_type", part.mediaType)
                                        put("data", part.base64)
                                    }
                                }
                                is Part.ToolUse -> addJsonObject {
                                    put("type", "tool_use")
                                    put("id", part.id)
                                    put("name", part.name)
                                    put("input", runCatching { json.parseToJsonElement(part.argsJson) as? JsonObject }.getOrNull() ?: JsonObject(emptyMap()))
                                }
                                is Part.ToolResult -> addJsonObject {
                                    put("type", "tool_result")
                                    put("tool_use_id", part.toolUseId)
                                    put("content", part.content)
                                    if (part.isError) put("is_error", true)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
