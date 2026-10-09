package dev.beka.mcp

import dev.beka.agent.Risk
import dev.beka.agent.ToolFailed
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpTest {
    private val http = OkHttpClient()

    private fun json(body: String, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private fun sse(body: String) =
        MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body)

    private fun reply(id: Int, result: String) = """{"jsonrpc":"2.0","id":$id,"result":$result}"""

    private val initResult = """{"protocolVersion":"2025-06-18","capabilities":{},"serverInfo":{"name":"x","version":"1"}}"""

    private fun config(server: MockWebServer, header: Pair<String, String>? = null, autoRun: Boolean = false) =
        McpServerConfig("s1", "Test", server.url("/mcp").toString(), header?.first, header?.second, autoRun)

    private fun bodyOf(r: RecordedRequest): JsonObject =
        mcpJson.parseToJsonElement(r.body.clone().readUtf8()).jsonObject

    private fun server(vararg responses: MockResponse) = MockWebServer().apply { responses.forEach { enqueue(it) }; start() }

    @Test
    fun `initialize keeps the session id and sends it with later requests`() = runTest {
        val s = server(
            sse("event: message\ndata: ${reply(1, initResult)}\n\n").setHeader("Mcp-Session-Id", "abc"),
            MockResponse().setResponseCode(202),
            json(reply(2, """{"tools":[{"name":"echo","description":"Echo","inputSchema":{"type":"object"}}]}""")),
        )
        val tools = McpClient(http, config(s, "x-api-key" to "k1")).listTools()
        assertEquals(listOf("echo"), tools.map { it.name })

        val init = s.takeRequest()
        assertEquals("initialize", bodyOf(init)["method"]!!.jsonPrimitive.content)
        assertEquals("application/json, text/event-stream", init.getHeader("Accept"))
        assertEquals("k1", init.getHeader("x-api-key"))
        assertEquals("initialize", init.getHeader("Mcp-Method"))
        assertNull(init.getHeader("Mcp-Session-Id"))
        val note = s.takeRequest()
        assertEquals("notifications/initialized", bodyOf(note)["method"]!!.jsonPrimitive.content)
        assertEquals("abc", note.getHeader("Mcp-Session-Id"))
        assertNull(bodyOf(note)["id"])
        val list = s.takeRequest()
        assertEquals("abc", list.getHeader("Mcp-Session-Id"))
        assertEquals("2025-06-18", list.getHeader("MCP-Protocol-Version"))
        assertEquals("tools/list", list.getHeader("Mcp-Method"))
        s.shutdown()
    }

    @Test
    fun `plain json replies and default header name`() = runTest {
        val s = server(
            json(reply(1, initResult)),
            MockResponse().setResponseCode(200),
            json(reply(2, """{"content":[{"type":"text","text":"hi"}]}""")),
        )
        val r = McpClient(http, config(s, "Authorization" to "Bearer t")).callTool("echo", buildJsonObject { put("a", "b") })
        assertEquals(McpResult("hi", false), r)
        s.takeRequest(); s.takeRequest()
        val call = s.takeRequest()
        assertEquals("Bearer t", call.getHeader("Authorization"))
        assertEquals("tools/call", call.getHeader("Mcp-Method"))
        assertEquals("echo", call.getHeader("Mcp-Name"))
        assertEquals("b", bodyOf(call)["params"]!!.jsonObject["arguments"]!!.jsonObject["a"]!!.jsonPrimitive.content)
        s.shutdown()
    }

    @Test
    fun `sse skips notifications and replies to other ids, joins multi-line data`() = runTest {
        val s = server(
            json(reply(1, initResult)),
            MockResponse().setResponseCode(202),
            sse(
                "event: message\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\",\"params\":{}}\n\n" +
                    ": keep-alive\n\n" +
                    "data: {\"jsonrpc\":\"2.0\",\"id\":99,\"result\":{}}\n\n" +
                    "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":2,\n" +
                    "data: \"result\":{\"content\":[{\"type\":\"text\",\"text\":\"done\"}]}}\n\n",
            ),
        )
        assertEquals("done", McpClient(http, config(s)).callTool("t", JsonObject(emptyMap())).text)
        s.shutdown()
    }

    @Test
    fun `content items are flattened and isError is reported`() = runTest {
        val s = server(
            json(reply(1, initResult)),
            MockResponse().setResponseCode(202),
            json(
                reply(
                    2,
                    """{"isError":true,"content":[{"type":"text","text":"a"},{"type":"image","data":"zz","mimeType":"image/png"},""" +
                        """{"type":"resource","resource":{"uri":"file:///x","text":"body"}},{"type":"resource_link","uri":"https://e.com/r","name":"r"}]}""",
                ),
            ),
        )
        val r = McpClient(http, config(s)).callTool("t", JsonObject(emptyMap()))
        assertTrue(r.isError)
        assertEquals("a\n(image omitted)\nbody\nhttps://e.com/r", r.text)
        s.shutdown()
    }

    @Test
    fun `json-rpc error becomes McpException`() = runTest {
        val s = server(
            json(reply(1, initResult)),
            MockResponse().setResponseCode(202),
            json("""{"jsonrpc":"2.0","id":2,"error":{"code":-32602,"message":"bad params"}}"""),
        )
        val e = assertFailsWith<McpException> { McpClient(http, config(s)).callTool("t", JsonObject(emptyMap())) }
        assertEquals("bad params", e.message)
        s.shutdown()
    }

    @Test
    fun `http 401 mentions the token`() = runTest {
        val s = server(json("unauthorized", 401))
        val e = assertFailsWith<McpException> { McpClient(http, config(s)).listTools() }
        assertTrue(e.message!!.startsWith("HTTP 401"))
        s.shutdown()
    }

    @Test
    fun `404 with a session re-initializes once and retries`() = runTest {
        val s = server(
            json(reply(1, initResult)).setHeader("Mcp-Session-Id", "old"),
            MockResponse().setResponseCode(202),
            json("""{"error":{"code":-32001,"message":"Session not found"}}""", 404),
            json(reply(3, initResult)).setHeader("Mcp-Session-Id", "new"),
            MockResponse().setResponseCode(202),
            json(reply(4, """{"content":[{"type":"text","text":"ok"}]}""")),
        )
        assertEquals("ok", McpClient(http, config(s)).callTool("t", JsonObject(emptyMap())).text)
        repeat(3) { s.takeRequest() }
        val reinit = s.takeRequest()
        assertEquals("initialize", bodyOf(reinit)["method"]!!.jsonPrimitive.content)
        assertNull(reinit.getHeader("Mcp-Session-Id"))
        s.takeRequest()
        assertEquals("new", s.takeRequest().getHeader("Mcp-Session-Id"))
        s.shutdown()
    }

    @Test
    fun `tools list follows nextCursor up to five pages`() = runTest {
        val s = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val b = bodyOf(request)
                    val id = b["id"]?.jsonPrimitive?.content?.toInt() ?: return MockResponse().setResponseCode(202)
                    return when (b["method"]!!.jsonPrimitive.content) {
                        "initialize" -> json(reply(id, initResult))
                        else -> {
                            val cursor = b["params"]?.jsonObject?.get("cursor")?.jsonPrimitive?.content?.toInt() ?: 0
                            json(reply(id, """{"tools":[{"name":"t$cursor"}],"nextCursor":"${cursor + 1}"}"""))
                        }
                    }
                }
            }
            start()
        }
        val tools = McpClient(http, config(s)).listTools()
        assertEquals(listOf("t0", "t1", "t2", "t3", "t4"), tools.map { it.name })
        s.shutdown()
    }

    @Test
    fun `tool fields are parsed`() = runTest {
        val s = server(
            json(reply(1, initResult)),
            MockResponse().setResponseCode(202),
            json(
                reply(
                    2,
                    """{"tools":[{"name":"a","title":"A tool","description":"d","inputSchema":{"type":"object","properties":{"q":{"type":"string"}}},"annotations":{"readOnlyHint":true}},{"name":"b"}]}""",
                ),
            ),
        )
        val (a, b) = McpClient(http, config(s)).listTools()
        assertEquals("A tool", a.title); assertTrue(a.readOnly); assertEquals("d", a.description)
        assertTrue("q" in a.inputSchema["properties"]!!.jsonObject)
        assertTrue(!b.readOnly); assertNull(b.title)
        s.shutdown()
    }

    @Test
    fun `server that rejects initialize naming the modern version switches to per-request metadata`() = runTest {
        val s = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val b = bodyOf(request)
                    return if (b["method"]!!.jsonPrimitive.content == "initialize") {
                        json("""{"jsonrpc":"2.0","id":1,"error":{"code":-32601,"message":"Use protocol 2026-07-28"}}""", 400)
                    } else {
                        json(reply(b["id"]!!.jsonPrimitive.content.toInt(), """{"tools":[{"name":"m"}]}"""))
                    }
                }
            }
            start()
        }
        val tools = McpClient(http, config(s)).listTools()
        assertEquals(listOf("m"), tools.map { it.name })
        s.takeRequest()
        val list = s.takeRequest()
        assertEquals("2026-07-28", list.getHeader("MCP-Protocol-Version"))
        val meta = bodyOf(list)["params"]!!.jsonObject["_meta"]!!.jsonObject
        assertEquals("2026-07-28", meta["io.modelcontextprotocol/protocolVersion"]!!.jsonPrimitive.content)
        s.shutdown()
    }

    // --- toolkit ---

    private fun tool(name: String, readOnly: Boolean = false, title: String? = null) =
        McpTool(name, title, "does $name", JsonObject(emptyMap()), readOnly)

    private fun kit(server: McpServerConfig, tools: List<McpTool>, client: McpClient = McpClient(http, server)) =
        McpToolKit(listOf(server to tools)) { client }

    private val cfg = McpServerConfig("1", "Context 7!", "http://localhost:1/mcp")

    @Test
    fun `names are prefixed sanitized and unique`() {
        val k = kit(cfg, listOf(tool("resolve-library-id"), tool("a b.c"), tool("a_b_c"), tool("a b.c")))
        val names = k.specs.map { it.name }
        assertEquals("mcp_context_7__resolve-library-id", names[0])
        assertEquals("mcp_context_7__a_b_c", names[1])
        assertEquals(names.size, names.toSet().size)
        assertTrue(names.all { Regex("[a-zA-Z0-9_-]{1,64}").matches(it) })
    }

    @Test
    fun `long names are cut to 64 with a hash that keeps them distinct`() {
        val k = kit(cfg, listOf(tool("x".repeat(100) + "1"), tool("x".repeat(100) + "2")))
        val names = k.specs.map { it.name }
        assertTrue(names.all { it.length <= 64 })
        assertNotEquals(names[0], names[1])
    }

    @Test
    fun `spec has server tag, capped description and an object schema`() {
        val long = McpTool("t", null, "d".repeat(2000), JsonObject(emptyMap()), false)
        val spec = kit(cfg, listOf(long)).specs.single()
        assertTrue(spec.description.startsWith("[Context 7!] d"))
        assertEquals(1000 + "[Context 7!] ".length, spec.description.length)
        assertEquals("object", spec.parameters["type"]!!.jsonPrimitive.content)
        assertTrue(spec.parameters["properties"]!!.jsonObject.isEmpty())
    }

    @Test
    fun `risk follows autoRun and readOnlyHint`() {
        val tools = listOf(tool("w"), tool("r", readOnly = true))
        val ask = kit(cfg, tools)
        assertEquals(Risk.HIGH, ask.risk(ask.specs[0].name, "{}"))
        assertEquals(Risk.SAFE, ask.risk(ask.specs[1].name, "{}"))
        val auto = kit(cfg.copy(autoRun = true), tools)
        assertEquals(Risk.SAFE, auto.risk(auto.specs[0].name, "{}"))
        assertEquals(Risk.HIGH, ask.risk("unknown", "{}"))
    }

    @Test
    fun `describe uses the title or name`() {
        val k = kit(cfg, listOf(tool("a", title = "Nice A"), tool("b")))
        assertEquals("Context 7!: Nice A", k.describe(k.specs[0].name, "{}"))
        assertEquals("Context 7!: b", k.describe(k.specs[1].name, "{}"))
    }

    @Test
    fun `run returns text, truncates, and maps errors to ToolFailed`() = runTest {
        val big = "z".repeat(20_000)
        val s = server(
            json(reply(1, initResult)), MockResponse().setResponseCode(202),
            json(reply(2, """{"content":[{"type":"text","text":"$big"}]}""")),
            json(reply(3, """{"isError":true,"content":[{"type":"text","text":"nope"}]}""")),
            json("""{"jsonrpc":"2.0","id":4,"error":{"code":-1,"message":"boom"}}"""),
        )
        val server = config(s)
        val client = McpClient(http, server)
        val k = McpToolKit(listOf(server to listOf(tool("t")))) { client }
        val name = k.specs.single().name
        val out = k.run(name, """{"q":1}""")
        assertTrue(out.endsWith("… (truncated)") && out.length < 12_100)
        assertEquals("nope", assertFailsWith<ToolFailed> { k.run(name, "{}") }.message)
        assertEquals("boom", assertFailsWith<ToolFailed> { k.run(name, "{}") }.message)
        assertFailsWith<IllegalArgumentException> { k.run(name, "[1]") }
        assertFailsWith<IllegalArgumentException> { k.run("mcp_other", "{}") }
        s.shutdown()
    }
}
