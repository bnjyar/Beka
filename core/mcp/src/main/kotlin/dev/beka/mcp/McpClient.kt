package dev.beka.mcp

import dev.beka.llm.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** A remote MCP server the user connected. [headerName]/[headerValue] are an optional static auth header. */
data class McpServerConfig(
    val id: String,
    val name: String,
    val url: String,
    val headerName: String? = null,
    val headerValue: String? = null,
    /** Run its tools without asking (read-only tools never ask). */
    val autoRun: Boolean = false,
)

data class McpTool(
    val name: String,
    val title: String?,
    val description: String,
    val inputSchema: JsonObject,
    val readOnly: Boolean,
)

data class McpResult(val text: String, val isError: Boolean)

/** A call to an MCP server failed. [status] is the HTTP status and [raw] the response body when there was one. The message is meant for the user and the model. */
class McpException(message: String, val status: Int? = null, val code: Int? = null, val raw: String? = null) : Exception(message)

internal val mcpJson = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * Talks to one MCP server over Streamable HTTP.
 *
 * Two protocol eras exist. The legacy one (2025-11-25 and earlier) opens with `initialize`, may hand out an
 * `Mcp-Session-Id` and expects `notifications/initialized`. The modern one (2026-07-28) has no handshake or sessions:
 * every request carries its version, identity and capabilities in `_meta`. We start with the legacy handshake (every
 * deployed server answers it) and, when the server rejects `initialize` while naming the modern version, switch to
 * modern requests for the lifetime of this client.
 *
 * Reuse one instance per server: the handshake and session id are kept between calls.
 */
class McpClient(
    private val http: OkHttpClient,
    private val config: McpServerConfig,
    private val clientVersion: String = "0.1",
) {
    private val lock = Mutex()
    private val ids = AtomicInteger(1)

    @Volatile private var ready = false
    @Volatile private var modern = false
    @Volatile private var version = LEGACY_VERSION
    @Volatile private var sessionId: String? = null

    /** What the server says about when to use it (initialize's `instructions`), if it said anything. */
    @Volatile var instructions: String? = null
        private set

    suspend fun listTools(): List<McpTool> {
        val tools = ArrayList<McpTool>()
        var cursor: String? = null
        repeat(MAX_PAGES) {
            val params = buildJsonObject { if (cursor != null) put("cursor", cursor!!) }
            val result = request("tools/list", params, TIMEOUT_S)
            (result["tools"] as? JsonArray)?.forEach { el -> parseTool(el)?.let(tools::add) }
            cursor = (result["nextCursor"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
            if (cursor == null) return tools
        }
        return tools
    }

    suspend fun callTool(name: String, argumentsJson: JsonObject): McpResult {
        val params = buildJsonObject {
            put("name", name)
            put("arguments", argumentsJson)
        }
        val result = request("tools/call", params, CALL_TIMEOUT_S)
        val parts = (result["content"] as? JsonArray).orEmpty().mapNotNull { contentText(it) }
        var text = parts.joinToString("\n")
        if (text.isBlank()) result["structuredContent"]?.takeIf { it !is JsonNull }?.let { text = it.toString() }
        val isError = (result["isError"] as? JsonPrimitive)?.booleanOrNull ?: false
        return McpResult(text, isError)
    }

    private fun parseTool(el: JsonElement): McpTool? {
        val o = el as? JsonObject ?: return null
        val name = (o["name"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        val annotations = o["annotations"] as? JsonObject
        return McpTool(
            name = name,
            title = (o["title"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: (annotations?.get("title") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() },
            description = (o["description"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            inputSchema = o["inputSchema"] as? JsonObject ?: JsonObject(emptyMap()),
            readOnly = (annotations?.get("readOnlyHint") as? JsonPrimitive)?.booleanOrNull == true,
        )
    }

    private fun contentText(el: JsonElement): String? {
        val o = el as? JsonObject ?: return null
        fun str(key: String) = (o[key] as? JsonPrimitive)?.contentOrNull
        return when (str("type")) {
            "text" -> str("text")
            "image", "audio" -> "(${str("type")} omitted)"
            "resource" -> {
                val r = o["resource"] as? JsonObject
                (r?.get("text") as? JsonPrimitive)?.contentOrNull ?: (r?.get("uri") as? JsonPrimitive)?.contentOrNull ?: "(resource omitted)"
            }
            "resource_link" -> str("uri") ?: str("name")
            else -> str("text")
        }
    }

    // --- protocol ---

    /** Sends [method], doing the handshake first when needed and redoing it once if the server forgot our session. */
    private suspend fun request(method: String, params: JsonObject, timeoutS: Long): JsonObject {
        ensureReady()
        val usedSession = sessionId
        return try {
            post(method, params, timeoutS)
        } catch (e: McpException) {
            // A session the server dropped answers 404 (spec: start a new one with a fresh initialize).
            if (e.status == 404 && usedSession != null && !modern) {
                resetSession(usedSession)
                ensureReady()
                post(method, params, timeoutS)
            } else throw e
        }
    }

    private suspend fun resetSession(stale: String) = lock.withLock {
        if (sessionId == stale) { ready = false; sessionId = null }
    }

    private suspend fun ensureReady() {
        if (ready) return
        lock.withLock {
            if (ready) return
            val params = buildJsonObject {
                put("protocolVersion", LEGACY_VERSION)
                putJsonObject("capabilities") {}
                putJsonObject("clientInfo") { put("name", "beka"); put("version", clientVersion) }
            }
            try {
                val result = post("initialize", params, TIMEOUT_S, keepSession = true)
                version = (result["protocolVersion"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: LEGACY_VERSION
                modern = false
                instructions = (result["instructions"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                // Failure to deliver this is not fatal: servers that ignore it still work.
                runCatching {
                    post("notifications/initialized", JsonObject(emptyMap()), TIMEOUT_S, notification = true)
                }
            } catch (e: McpException) {
                // A server that only speaks the modern era rejects `initialize` and names its versions.
                val body = e.raw.orEmpty()
                val modernServer = body.contains(MODERN_VERSION) && (e.status == null || e.status in 400..499 || e.code != null)
                if (!modernServer) throw e
                modern = true
                version = MODERN_VERSION
                sessionId = null
            }
            ready = true
        }
    }

    private fun idOf(el: JsonElement?): String? = (el as? JsonPrimitive)?.contentOrNull

    /** One POST. Returns the JSON-RPC `result`; throws [McpException] for HTTP and JSON-RPC errors. */
    private suspend fun post(
        method: String,
        params: JsonObject,
        timeoutS: Long,
        notification: Boolean = false,
        keepSession: Boolean = false,
    ): JsonObject {
        val url = config.url.trim().toHttpUrlOrNull() ?: throw McpException(tr("Invalid URL: ${config.url}", "URL tidak valid: ${config.url}"))
        val id = if (notification) null else ids.getAndIncrement().toString()
        val body = buildJsonObject {
            put("jsonrpc", "2.0")
            if (id != null) put("id", id.toInt())
            put("method", method)
            val p = if (modern) JsonObject(params + ("_meta" to metaObject())) else params
            if (p.isNotEmpty()) put("params", p)
        }
        val builder = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .header("Accept", "application/json, text/event-stream")
            .header("MCP-Protocol-Version", version)
            .header("Mcp-Method", method)
        // Old servers ignore the metadata headers; modern ones require them.
        if (method == "tools/call") (params["name"] as? JsonPrimitive)?.contentOrNull?.let { builder.header("Mcp-Name", headerSafe(it)) }
        sessionId?.let { builder.header("Mcp-Session-Id", it) }
        val hn = config.headerName?.trim().orEmpty()
        val hv = config.headerValue?.trim().orEmpty()
        if (hv.isNotEmpty()) {
            try { builder.header(hn.ifEmpty { "Authorization" }, hv) } catch (e: IllegalArgumentException) { throw McpException(tr("Invalid header: ${e.message}", "Header tidak valid: ${e.message}")) }
        }
        val call = http.newBuilder()
            .connectTimeout(TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(timeoutS, TimeUnit.SECONDS)
            .callTimeout(timeoutS, TimeUnit.SECONDS)
            .build()
            .newCall(builder.build())

        return withContext(Dispatchers.IO) {
            val handle = coroutineContext[Job]!!.invokeOnCompletion { if (it != null) call.cancel() }
            try { readReply(call, id, notification, keepSession) } finally { handle.dispose() }
        }
    }

    private fun readReply(call: okhttp3.Call, id: String?, notification: Boolean, keepSession: Boolean): JsonObject {
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) throw httpError(resp.code, resp.body?.let { readCapped(it.charStream()) }.orEmpty())
                if (keepSession) sessionId = resp.header("Mcp-Session-Id") ?: sessionId
                if (notification) return JsonObject(emptyMap())
                val contentType = resp.header("Content-Type").orEmpty().lowercase()
                val source = resp.body ?: throw McpException(tr("Empty response", "Respons kosong"), resp.code)
                val message = if ("text/event-stream" in contentType) {
                    readSse(source.charStream(), id)
                } else {
                    val text = readCapped(source.charStream())
                    findReply(text, id) ?: throw McpException(tr("Not a JSON-RPC response: ", "Respons bukan JSON-RPC: ") + text.take(200), resp.code, raw = text)
                }
                return unwrap(message, resp.code)
            }
        } catch (e: IOException) {
            throw McpException(tr("Cannot connect: ", "Tidak bisa terhubung: ") + (e.message ?: e.javaClass.simpleName))
        }
    }

    private fun unwrap(message: JsonObject, status: Int): JsonObject {
        (message["error"] as? JsonObject)?.let { err ->
            val code = (err["code"] as? JsonPrimitive)?.intOrNull
            val msg = (err["message"] as? JsonPrimitive)?.contentOrNull ?: tr("Server error", "Error dari server")
            throw McpException(msg, status, code, message.toString())
        }
        return message["result"] as? JsonObject ?: JsonObject(emptyMap())
    }

    private fun httpError(status: Int, text: String): McpException {
        val err = runCatching { (mcpJson.parseToJsonElement(text).jsonObject["error"] as? JsonObject) }.getOrNull()
        val code = (err?.get("code") as? JsonPrimitive)?.intOrNull
        val msg = (err?.get("message") as? JsonPrimitive)?.contentOrNull
            ?: text.replace(Regex("\\s+"), " ").take(200)
        val hint = if (status == 401 || status == 403) tr(" (check the server header/token)", " (cek header/token server)") else ""
        return McpException("HTTP $status: $msg$hint".trim(), status, code, text)
    }

    /** A JSON body is one message or a batch; picks the response to [id]. */
    private fun findReply(text: String, id: String?): JsonObject? {
        val el = runCatching { mcpJson.parseToJsonElement(text) }.getOrNull() ?: return null
        return when (el) {
            is JsonObject -> el
            is JsonArray -> el.firstNotNullOfOrNull { (it as? JsonObject)?.takeIf { o -> idOf(o["id"]) == id } }
            else -> null
        }
    }

    /** Reads SSE events until the response to [id]; other messages (progress, log notifications, pings) are skipped. */
    private fun readSse(reader: java.io.Reader, id: String?): JsonObject {
        val lines = reader.buffered()
        var total = 0
        val data = StringBuilder()
        fun flush(): JsonObject? {
            if (data.isEmpty()) return null
            val text = data.toString().also { data.setLength(0) }
            val msg = runCatching { mcpJson.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
            val isReply = msg["method"] == null && (msg["result"] != null || msg["error"] != null)
            return msg.takeIf { isReply && idOf(msg["id"]) == id }
        }
        while (true) {
            val line = lines.readLine() ?: break
            total += line.length + 1
            if (total > MAX_BODY) throw McpException(tr("Response too large", "Respons terlalu besar"))
            when {
                line.isEmpty() -> flush()?.let { return it }
                line.startsWith(":") -> {}
                line.startsWith("data:") -> {
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(line.removePrefix("data:").removePrefix(" "))
                }
            }
        }
        flush()?.let { return it }
        throw McpException(tr("The server closed the stream without an answer", "Server menutup stream tanpa jawaban"))
    }

    private fun readCapped(reader: java.io.Reader): String {
        val out = StringBuilder()
        val buf = CharArray(8192)
        while (out.length < MAX_BODY) {
            val n = reader.read(buf, 0, buf.size)
            if (n < 0) break
            out.append(buf, 0, n)
        }
        return out.toString()
    }

    private fun metaObject(): JsonObject = buildJsonObject {
        put("io.modelcontextprotocol/protocolVersion", version)
        putJsonObject("io.modelcontextprotocol/clientInfo") { put("name", "beka"); put("version", clientVersion) }
        putJsonObject("io.modelcontextprotocol/clientCapabilities") {}
    }

    /** Spec "Value Encoding": plain visible ASCII goes as is, anything else (or sentinel look-alikes) as =?base64?...?=. */
    private fun headerSafe(value: String): String {
        val plain = value.isNotEmpty() && value == value.trim() &&
            value.all { it.code in 0x20..0x7E } && !(value.startsWith("=?base64?") && value.endsWith("?="))
        return if (plain) value else "=?base64?" + Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8)) + "?="
    }

    companion object {
        const val LEGACY_VERSION = "2025-06-18"
        const val MODERN_VERSION = "2026-07-28"
        private const val TIMEOUT_S = 30L
        private const val CALL_TIMEOUT_S = 60L
        private const val MAX_BODY = 2_000_000
        private const val MAX_PAGES = 5
    }
}
