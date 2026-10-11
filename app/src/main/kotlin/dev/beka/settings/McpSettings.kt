package dev.beka.settings

import android.content.Context
import dev.beka.BuildConfig
import dev.beka.mcp.McpClient
import dev.beka.mcp.McpException
import dev.beka.mcp.McpServerConfig
import dev.beka.mcp.McpTool
import dev.beka.mcp.McpToolKit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import java.util.UUID

/** A connected MCP server as the screen and chat see it. The header value stays out of here ([McpSettings.config] has it). */
data class McpServer(
    val id: String,
    val name: String,
    val url: String,
    val headerName: String?,
    val hasHeaderValue: Boolean,
    val autoRun: Boolean,
    val enabled: Boolean,
    val tools: List<McpTool> = emptyList(),
    /** Millis of the last successful tool list; 0 = never. */
    val fetchedAt: Long = 0,
    val error: String? = null,
    val refreshing: Boolean = false,
)

/**
 * Remote MCP servers the user connected. The server list and each server's last tool list live in
 * SharedPreferences (header values encrypted with [SecretBox]); chat only ever reads the cached lists,
 * so starting a chat never waits on the network.
 */
class McpSettings(context: Context, private val http: OkHttpClient) {
    private val prefs = context.getSharedPreferences("mcp", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _servers = MutableStateFlow(load())
    val servers: StateFlow<List<McpServer>> = _servers

    /** One client per server (it keeps the handshake and session); replaced when the server's settings change. */
    private val clients = java.util.concurrent.ConcurrentHashMap<String, Pair<McpServerConfig, McpClient>>()
    /** Last background attempt per server, so a server that keeps failing is not retried on every message. */
    private val attempts = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** The server with its decrypted header value, for connecting. */
    fun config(id: String): McpServerConfig? {
        val s = _servers.value.firstOrNull { it.id == id } ?: return null
        val value = prefs.getString(valueKey(id), null)?.let(SecretBox::decrypt)?.takeIf { it.isNotBlank() }
        return McpServerConfig(s.id, s.name, s.url, s.headerName, value, s.autoRun)
    }

    /** Adds a server and returns its id; call [refresh] to load its tools. */
    fun add(name: String, url: String, headerName: String, headerValue: String): String {
        val id = UUID.randomUUID().toString()
        val value = headerValue.trim()
        if (value.isNotEmpty()) prefs.edit().putString(valueKey(id), SecretBox.encrypt(value)).apply()
        val server = McpServer(
            id, name.trim().ifEmpty { hostOf(url) }, url.trim(),
            headerName.trim().takeIf { value.isNotEmpty() && it.isNotEmpty() },
            hasHeaderValue = value.isNotEmpty(), autoRun = false, enabled = true,
        )
        _servers.update { it + server }
        saveList()
        return id
    }

    fun remove(id: String) {
        _servers.update { list -> list.filter { it.id != id } }
        prefs.edit().remove(valueKey(id)).remove(cacheKey(id)).remove(notesKey(id)).apply()
        clients.remove(id)
        saveList()
    }

    fun setEnabled(id: String, value: Boolean) = change(id) { it.copy(enabled = value) }

    fun setAutoRun(id: String, value: Boolean) = change(id) { it.copy(autoRun = value) }

    /** Connects (initialize + tools/list) and stores the result, or the error while keeping the old list. */
    suspend fun refresh(id: String) {
        val cfg = config(id) ?: return
        change(id) { it.copy(refreshing = true) }
        // A fresh client: this is also how the user recovers from a stuck session.
        val client = McpClient(http, cfg, BuildConfig.VERSION_NAME)
        clients[id] = cfg to client
        try {
            val tools = client.listTools()
            val now = System.currentTimeMillis()
            prefs.edit().putString(cacheKey(id), encodeCache(tools, now)).putString(notesKey(id), client.instructions.orEmpty()).apply()
            change(id) { it.copy(tools = tools, fetchedAt = now, error = null, refreshing = false) }
        } catch (e: McpException) {
            change(id) { it.copy(error = e.message ?: "Gagal terhubung", refreshing = false) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            change(id) { it.copy(refreshing = false) }
            throw e
        } catch (e: Exception) {
            change(id) { it.copy(error = e.message ?: e.javaClass.simpleName, refreshing = false) }
        }
    }

    /** Tools of the enabled servers from the cache; null when there are none. Kicks off background refreshes of stale lists. */
    fun toolkit(): McpToolKit? {
        val now = System.currentTimeMillis()
        val enabled = _servers.value.filter { it.enabled }
        enabled.filter { !it.refreshing && now - it.fetchedAt > STALE_MS && now - (attempts[it.id] ?: 0) > RETRY_MS }.forEach {
            attempts[it.id] = now
            scope.launch { refresh(it.id) }
        }
        val usable = enabled.filter { it.tools.isNotEmpty() }.mapNotNull { s -> config(s.id)?.let { it to s.tools } }
        if (usable.isEmpty()) return null
        val notes = usable.associate { (cfg, _) -> cfg.name to prefs.getString(notesKey(cfg.id), "").orEmpty() }
        return McpToolKit(usable, clientFor = { cfg -> client(cfg) }, notes = notes)
    }

    /** Re-reads the stored values, after a backup was imported. */
    fun reload() {
        clients.clear()
        attempts.clear()
        _servers.value = load()
    }

    private fun client(cfg: McpServerConfig): McpClient {
        clients[cfg.id]?.takeIf { it.first == cfg }?.let { return it.second }
        return McpClient(http, cfg, BuildConfig.VERSION_NAME).also { clients[cfg.id] = cfg to it }
    }

    private fun change(id: String, f: (McpServer) -> McpServer) {
        var persist = false
        _servers.update { list -> list.map { if (it.id == id) f(it).also { n -> persist = persist || n.enabled != it.enabled || n.autoRun != it.autoRun } else it } }
        if (persist) saveList()
    }

    // --- persistence ---

    private fun saveList() {
        val arr = buildJsonArray {
            _servers.value.forEach { s ->
                add(buildJsonObject {
                    put("id", s.id); put("name", s.name); put("url", s.url)
                    s.headerName?.let { put("headerName", it) }
                    put("hasValue", s.hasHeaderValue); put("autoRun", s.autoRun); put("enabled", s.enabled)
                })
            }
        }
        prefs.edit().putString(KEY_SERVERS, arr.toString()).apply()
    }

    private fun load(): List<McpServer> {
        val arr = runCatching { json.parseToJsonElement(prefs.getString(KEY_SERVERS, null) ?: "[]") as? JsonArray }.getOrNull() ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            fun str(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
            fun bool(k: String, d: Boolean) = (o[k] as? JsonPrimitive)?.booleanOrNull ?: d
            val id = str("id") ?: return@mapNotNull null
            val url = str("url") ?: return@mapNotNull null
            val (tools, at) = decodeCache(prefs.getString(cacheKey(id), null))
            McpServer(
                id, str("name") ?: hostOf(url), url, str("headerName"), bool("hasValue", false),
                bool("autoRun", false), bool("enabled", true), tools, at,
            )
        }
    }

    private fun encodeCache(tools: List<McpTool>, at: Long): String = buildJsonObject {
        put("at", at)
        put("tools", buildJsonArray {
            tools.forEach { t ->
                add(buildJsonObject {
                    put("name", t.name)
                    t.title?.let { put("title", it) }
                    put("description", t.description)
                    put("inputSchema", t.inputSchema)
                    put("readOnly", t.readOnly)
                })
            }
        })
    }.toString()

    private fun decodeCache(raw: String?): Pair<List<McpTool>, Long> {
        val o = runCatching { json.parseToJsonElement(raw ?: return emptyList<McpTool>() to 0L) as? JsonObject }.getOrNull()
            ?: return emptyList<McpTool>() to 0L
        val at = (o["at"] as? JsonPrimitive)?.longOrNull ?: 0L
        val tools = (o["tools"] as? JsonArray).orEmpty().mapNotNull { el ->
            val t = el as? JsonObject ?: return@mapNotNull null
            McpTool(
                name = (t["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null,
                title = (t["title"] as? JsonPrimitive)?.contentOrNull,
                description = (t["description"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                inputSchema = t["inputSchema"] as? JsonObject ?: JsonObject(emptyMap()),
                readOnly = (t["readOnly"] as? JsonPrimitive)?.booleanOrNull == true,
            )
        }
        return tools to at
    }

    private fun hostOf(url: String): String =
        url.trim().substringAfter("://").substringBefore('/').substringBefore('?').ifEmpty { "MCP" }

    private companion object {
        const val KEY_SERVERS = "servers"
        const val STALE_MS = 24L * 60 * 60 * 1000
        const val RETRY_MS = 60L * 60 * 1000
        fun valueKey(id: String) = "value_$id"
        fun cacheKey(id: String) = "cache_$id"
        fun notesKey(id: String) = "notes_$id"
    }
}
