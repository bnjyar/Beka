package dev.beka.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
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

internal fun JsonObject.str(key: String): String? = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull

internal fun JsonObject.int(key: String): Int = this[key]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull } ?: 0

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
