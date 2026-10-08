package dev.beka.llm

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
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
        val calls = sortedMapOf<Int, PendingCall>()
        client.sse(http).collect { event ->
            if (event.data == "[DONE]") return@collect
            val obj = json.parseToJsonElement(event.data).jsonObject
            obj["error"]?.let { throw LlmException(it.toString()) }
            (obj["choices"] as? JsonArray)?.firstOrNull()?.jsonObject?.let { choice ->
                choice.obj("delta")?.str("content")?.let { if (it.isNotEmpty()) emit(ChatEvent.TextDelta(it)) }
                choice.obj("delta")?.get("tool_calls")?.let { it as? JsonArray }?.forEachIndexed { pos, el ->
                    val frag = el as? JsonObject ?: return@forEachIndexed
                    val index = (frag["index"] as? JsonPrimitive)?.intOrNull ?: pos
                    val call = calls.getOrPut(index) { PendingCall() }
                    frag.str("id")?.takeIf { it.isNotEmpty() }?.let { call.id = it }
                    frag.obj("function")?.let { fn ->
                        fn.str("name")?.takeIf { it.isNotEmpty() }?.let { call.name = it }
                        fn.str("arguments")?.let { call.args.append(it) }
                    }
                }
                choice.str("finish_reason")?.let { stopReason = it }
            }
            obj.obj("usage")?.let {
                // prompt_tokens includes cached ones (OpenAI, OpenRouter); report them apart like Anthropic does.
                val cached = it.obj("prompt_tokens_details")?.int("cached_tokens") ?: 0
                emit(ChatEvent.Usage(it.int("prompt_tokens") - cached, it.int("completion_tokens"), cacheReadTokens = cached))
            }
        }
        calls.forEach { (index, call) ->
            emit(ChatEvent.ToolCall(call.id ?: "call_$index", call.name, call.args.toString().ifBlank { "{}" }))
        }
        emit(ChatEvent.Done(stopReason))
    }

    private class PendingCall {
        var id: String? = null
        var name: String = ""
        val args = StringBuilder()
    }

    internal fun requestBody(request: ChatRequest): JsonObject =
        buildBody(request).let { if (config.cachingEnabled) it.withCacheBreakpointOnLastMessage() else it }

    private fun buildBody(request: ChatRequest): JsonObject = buildJsonObject {
        put("model", request.model)
        put("stream", true)
        // OpenAI's newer models reject max_tokens; most compatible servers only know max_tokens.
        put(if (isOpenAi) "max_completion_tokens" else "max_tokens", request.maxTokens)
        request.temperature?.let { put("temperature", it) }
        if (isOpenAi) putJsonObject("stream_options") { put("include_usage", true) }
        if (request.tools.isNotEmpty()) putJsonArray("tools") {
            request.tools.forEach { tool ->
                addJsonObject {
                    put("type", "function")
                    putJsonObject("function") {
                        put("name", tool.name)
                        put("description", tool.description)
                        put("parameters", tool.parameters)
                    }
                }
            }
        }
        putJsonArray("messages") {
            request.system?.let { system ->
                addJsonObject {
                    put("role", "system")
                    if (config.cachingEnabled) {
                        putJsonArray("content") {
                            addJsonObject {
                                put("type", "text")
                                put("text", system)
                                putJsonObject("cache_control") { put("type", "ephemeral") }
                            }
                        }
                    } else put("content", system)
                }
            }
            request.messages.forEach { message ->
                val uses = message.parts.filterIsInstance<Part.ToolUse>()
                val results = message.parts.filterIsInstance<Part.ToolResult>()
                val rest = message.parts.filter { it is Part.Text || it is Part.Image }
                if (message.role == Role.ASSISTANT && uses.isNotEmpty()) {
                    addJsonObject {
                        put("role", "assistant")
                        val text = rest.filterIsInstance<Part.Text>().joinToString("\n") { it.text }
                        put("content", if (text.isBlank()) JsonNull else JsonPrimitive(text))
                        putJsonArray("tool_calls") {
                            uses.forEach { use ->
                                addJsonObject {
                                    put("id", use.id)
                                    put("type", "function")
                                    putJsonObject("function") {
                                        put("name", use.name)
                                        put("arguments", use.argsJson)
                                    }
                                }
                            }
                        }
                    }
                } else {
                    results.forEach { r ->
                        addJsonObject {
                            put("role", "tool")
                            put("tool_call_id", r.toolUseId)
                            put("content", if (r.isError) "Error: ${r.content}" else r.content)
                        }
                    }
                    if (results.isEmpty() || rest.isNotEmpty()) {
                        addJsonObject {
                            put("role", if (message.role == Role.USER) "user" else "assistant")
                            put("content", content(rest))
                        }
                    }
                }
            }
        }
    }

    private fun content(parts: List<Part>): JsonElement {
        if (parts.all { it is Part.Text }) {
            return JsonPrimitive(parts.filterIsInstance<Part.Text>().joinToString("\n") { it.text })
        }
        return buildJsonArray {
            parts.forEach { part ->
                when (part) {
                    is Part.Text -> addJsonObject { put("type", "text"); put("text", part.text) }
                    is Part.Image -> addJsonObject {
                        put("type", "image_url")
                        putJsonObject("image_url") { put("url", "data:${part.mediaType};base64,${part.base64}") }
                    }
                    else -> Unit
                }
            }
        }
    }
}
