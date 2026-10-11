package dev.beka.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody

internal val json = Json { ignoreUnknownKeys = true }

internal val JSON_MEDIA = "application/json".toMediaType()

internal fun JsonElement.toBody(): RequestBody = toString().toRequestBody(JSON_MEDIA)

internal suspend fun OkHttpClient.getJson(request: Request): JsonElement = withContext(Dispatchers.IO) {
    newCall(request).execute().use { response ->
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) throw LlmException("HTTP ${response.code}: ${body.take(500)}", response.code)
        json.parseToJsonElement(body)
    }
}

private val EPHEMERAL = JsonObject(mapOf("type" to JsonPrimitive("ephemeral")))

/**
 * Puts a cache_control breakpoint on the last content block of the last message, so the whole
 * conversation so far is cached for the next turn. Works for both wire formats: a string content
 * (OpenAI style) becomes a one-element text array first, as OpenRouter's Claude support expects.
 */
internal fun JsonObject.withCacheBreakpointOnLastMessage(): JsonObject {
    val messages = this["messages"] as? JsonArray ?: return this
    val last = messages.lastOrNull() as? JsonObject ?: return this
    val blocks = when (val content = last["content"]) {
        is JsonArray -> content
        is JsonPrimitive -> if (content.isString) JsonArray(listOf(JsonObject(mapOf("type" to JsonPrimitive("text"), "text" to content)))) else return this
        else -> return this
    }
    val lastBlock = blocks.lastOrNull() as? JsonObject ?: return this
    val marked = JsonArray(blocks.dropLast(1) + JsonObject(lastBlock + ("cache_control" to EPHEMERAL)))
    val newLast = JsonObject(last + ("content" to marked))
    return JsonObject(this + ("messages" to JsonArray(messages.dropLast(1) + newLast)))
}

internal fun JsonObject.str(key: String): String? = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull

internal fun JsonObject.int(key: String): Int = this[key]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull } ?: 0

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

/**
 * An error that arrived inside an SSE stream (HTTP 200 already sent). Takes the status from a numeric
 * `code` (OpenRouter) or a known `type` (Anthropic), so callers can treat it like an HTTP error.
 */
internal fun streamError(error: JsonElement, raw: String): LlmException {
    val obj = error as? JsonObject
    val code = (obj?.get("code") as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }
    val status = code?.takeIf { it in 400..599 } ?: when (obj?.str("type")) {
        "invalid_request_error" -> 400
        "authentication_error" -> 401
        "permission_error" -> 403
        "not_found_error" -> 404
        "rate_limit_error" -> 429
        "api_error" -> 500
        "overloaded_error" -> 529
        else -> null
    }
    return LlmException(obj?.str("message") ?: raw, status)
}
