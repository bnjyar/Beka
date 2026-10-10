package dev.beka.mcp

import dev.beka.agent.Risk
import dev.beka.agent.ToolFailed
import dev.beka.agent.ToolKit
import dev.beka.llm.ToolSpec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Tools of connected MCP servers, offered to the agent as `mcp_<server>__<tool>`.
 * [servers] pairs each server with its cached tool list; [clientFor] gives the (shared) client used for calls.
 */
class McpToolKit(
    servers: List<Pair<McpServerConfig, List<McpTool>>>,
    /** Server name → the server's own instructions, shown to the model so it knows when to reach for the server. */
    private val notes: Map<String, String> = emptyMap(),
    private val clientFor: (McpServerConfig) -> McpClient,
) : ToolKit {
    private class Entry(val server: McpServerConfig, val tool: McpTool, val spec: ToolSpec)

    private val entries: Map<String, Entry>

    init {
        val map = LinkedHashMap<String, Entry>()
        for ((server, tools) in servers) {
            val slug = slug(server.name)
            for (tool in tools) {
                val name = unique(fit("mcp_${slug}__${sanitize(tool.name)}"), map.keys)
                val description = "[${server.name}] " + tool.description.ifBlank { tool.title ?: tool.name }.take(MAX_DESCRIPTION)
                map[name] = Entry(server, tool, ToolSpec(name, description, schemaOf(tool.inputSchema)))
            }
        }
        entries = map
    }

    override val specs: List<ToolSpec> = entries.values.map { it.spec }

    override val prompt: String?
        get() = notes.filterValues { it.isNotBlank() }.entries.takeIf { it.isNotEmpty() }?.joinToString("\n\n") { (name, text) ->
            "<service name=\"$name\">\n${text.trim().take(MAX_NOTE)}\n</service>"
        }

    override fun risk(name: String, argsJson: String): Risk {
        val e = entries[name] ?: return Risk.HIGH
        return if (e.server.autoRun || e.tool.readOnly) Risk.SAFE else Risk.HIGH
    }

    /** "Tanya dulu" is the user's explicit choice for this server, so it beats the global switch. */
    override fun alwaysAsk(name: String): Boolean = risk(name, "{}") == Risk.HIGH

    override fun describe(name: String, argsJson: String): String {
        val e = entries[name] ?: return name
        return "${e.server.name}: ${e.tool.title?.takeIf { it.isNotBlank() } ?: e.tool.name}"
    }

    override suspend fun run(name: String, argsJson: String): String {
        val e = entries[name] ?: throw IllegalArgumentException("Unknown tool: $name")
        val args = runCatching { mcpJson.parseToJsonElement(argsJson.ifBlank { "{}" }).jsonObject }
            .getOrElse { throw IllegalArgumentException("Arguments are not a JSON object: ${argsJson.take(200)}") }
        val result = try {
            clientFor(e.server).callTool(e.tool.name, args)
        } catch (ex: McpException) {
            throw ToolFailed(ex.message ?: "MCP request failed")
        }
        val text = cap(result.text)
        if (result.isError) throw ToolFailed(text.ifBlank { "The tool reported an error." })
        return text.ifBlank { "(empty result)" }
    }

    private fun cap(text: String) = if (text.length > MAX_RESULT) text.take(MAX_RESULT) + "\n… (truncated)" else text

    private fun schemaOf(schema: JsonObject): JsonObject = buildJsonObject {
        schema.forEach { (k, v) -> put(k, v) }
        if ("type" !in schema) put("type", "object")
        if ("properties" !in schema) put("properties", JsonObject(emptyMap()))
    }

    companion object {
        const val MAX_NAME = 64
        private const val MAX_DESCRIPTION = 1000
        const val MAX_NOTE = 800
        private const val MAX_RESULT = 12_000

        private fun sanitize(s: String) = s.replace(Regex("[^a-zA-Z0-9_-]"), "_")

        internal fun slug(name: String): String =
            name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifEmpty { "server" }

        /** At most [MAX_NAME] chars; a longer name is cut and ends in a short hash of the whole so cut names stay distinct. */
        internal fun fit(name: String): String {
            if (name.length <= MAX_NAME) return name
            val hash = name.hashCode().toUInt().toString(16).padStart(8, '0')
            return name.take(MAX_NAME - 9) + "_" + hash
        }

        private fun unique(name: String, taken: Set<String>): String {
            if (name !in taken) return name
            var n = 2
            while (true) {
                val suffix = "_$n"
                val candidate = name.take(MAX_NAME - suffix.length) + suffix
                if (candidate !in taken) return candidate
                n++
            }
        }
    }
}
