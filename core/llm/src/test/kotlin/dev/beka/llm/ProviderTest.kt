package dev.beka.llm

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ProviderTest {
    private lateinit var server: MockWebServer
    private val client = OkHttpClient()

    @BeforeTest fun setUp() { server = MockWebServer().apply { start() } }
    @AfterTest fun tearDown() { server.shutdown() }

    private fun sse(body: String) = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(body)

    private val request = ChatRequest(
        model = "m",
        system = "sys",
        messages = listOf(Message(Role.USER, listOf(Part.Text("hi"), Part.Image("AAAA", "image/png")))),
    )

    @Test
    fun `openai compatible streams text and usage`() = runTest {
        server.enqueue(
            sse(
                """
                data: {"choices":[{"delta":{"content":"Hal"},"finish_reason":null}]}

                data: {"choices":[{"delta":{"content":"o"},"finish_reason":"stop"}]}

                data: {"choices":[],"usage":{"prompt_tokens":12,"completion_tokens":3}}

                data: [DONE]

                """.trimIndent()
            )
        )
        val config = ProviderConfig("p", "P", ProviderKind.OPENAI_COMPATIBLE, server.url("/v1").toString(), "m")
        val events = OpenAiCompatProvider(config, "key", client).chat(request).toList()

        assertEquals(
            listOf(
                ChatEvent.TextDelta("Hal"),
                ChatEvent.TextDelta("o"),
                ChatEvent.Usage(12, 3),
                ChatEvent.Done("stop"),
            ),
            events,
        )
        val recorded = server.takeRequest()
        assertEquals("/v1/chat/completions", recorded.path)
        assertEquals("Bearer key", recorded.getHeader("Authorization"))
        val body = json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        val messages = body["messages"]!!.jsonArray
        assertEquals("system", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
        val image = messages[1].jsonObject["content"]!!.jsonArray[1].jsonObject
        assertEquals("data:image/png;base64,AAAA", image["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content)
    }

    @Test
    fun `anthropic streams text and usage`() = runTest {
        server.enqueue(
            sse(
                """
                event: message_start
                data: {"type":"message_start","message":{"usage":{"input_tokens":20}}}

                event: content_block_delta
                data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"Halo"}}

                event: message_delta
                data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":5}}

                event: message_stop
                data: {"type":"message_stop"}

                """.trimIndent()
            )
        )
        val config = ProviderConfig("p", "P", ProviderKind.ANTHROPIC_COMPATIBLE, server.url("/").toString(), "m")
        val events = AnthropicProvider(config, "key", client).chat(request).toList()

        assertEquals(
            listOf(ChatEvent.TextDelta("Halo"), ChatEvent.Usage(20, 5), ChatEvent.Done("end_turn")),
            events,
        )
        val recorded = server.takeRequest()
        assertEquals("/v1/messages", recorded.path)
        assertEquals("key", recorded.getHeader("x-api-key"))
        val body = json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        assertEquals("sys", body["system"]!!.jsonPrimitive.content)
        val image = body["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray[1].jsonObject
        assertEquals("base64", image["source"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `http errors surface status and body`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"bad key"}"""))
        val config = ProviderConfig("p", "P", ProviderKind.OPENAI_COMPATIBLE, server.url("/v1").toString(), "m")
        val error = assertFailsWith<LlmException> { OpenAiCompatProvider(config, "x", client).chat(request).toList() }
        assertEquals(401, error.status)
    }

    @Test
    fun `lists models`() = runTest {
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"b"},{"id":"a"}]}"""))
        val config = ProviderConfig("p", "P", ProviderKind.OPENAI_COMPATIBLE, server.url("/v1").toString(), "m")
        assertEquals(listOf(ModelInfo("a"), ModelInfo("b")), OpenAiCompatProvider(config, null, client).listModels())
    }

    private val tapTool = ToolSpec(
        "tap", "Tap an element",
        buildJsonObject { put("type", "object"); putJsonObject("properties") { putJsonObject("id") { put("type", "string") } } },
    )

    private val openAiConfig get() = ProviderConfig("p", "P", ProviderKind.OPENAI_COMPATIBLE, server.url("/v1").toString(), "m")
    private val anthropicConfig get() = ProviderConfig("p", "P", ProviderKind.ANTHROPIC_COMPATIBLE, server.url("/").toString(), "m")

    @Test
    fun `openai compatible streams tool calls`() = runTest {
        server.enqueue(
            sse(
                """
                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"abc","type":"function","function":{"name":"tap","arguments":""}}]}}]}

                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"id"}}]}}]}

                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\":\"a1\""}}]}}]}

                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"}"}}]}}]}

                data: {"choices":[{"delta":{"tool_calls":[{"index":1,"function":{"name":"back","arguments":"{}"}}]}}]}

                data: {"choices":[{"delta":{},"finish_reason":"tool_calls"}]}

                data: [DONE]

                """.trimIndent()
            )
        )
        val events = OpenAiCompatProvider(openAiConfig, "key", client).chat(request.copy(tools = listOf(tapTool))).toList()

        assertEquals(
            listOf(
                ChatEvent.ToolCall("abc", "tap", """{"id":"a1"}"""),
                ChatEvent.ToolCall("call_1", "back", "{}"),
                ChatEvent.Done("tool_calls"),
            ),
            events,
        )
        val body = json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val fn = body["tools"]!!.jsonArray[0].jsonObject["function"]!!.jsonObject
        assertEquals("tap", fn["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `openai request encodes tool history`() {
        val history = ChatRequest(
            model = "m",
            system = null,
            messages = listOf(
                Message.user("go"),
                Message(Role.ASSISTANT, listOf(Part.ToolUse("c1", "tap", """{"id":"a1"}"""))),
                Message(Role.USER, listOf(Part.ToolResult("c1", "ok"), Part.Image("AAAA"), Part.Text("now?"))),
            ),
        )
        val body = OpenAiCompatProvider(openAiConfig, null, client).requestBody(history)
        assertEquals(null, body["tools"])
        val messages = body["messages"]!!.jsonArray
        assertEquals(4, messages.size)
        val assistant = messages[1].jsonObject
        assertEquals(JsonNull, assistant["content"])
        val call = assistant["tool_calls"]!!.jsonArray[0].jsonObject
        assertEquals("c1", call["id"]!!.jsonPrimitive.content)
        assertEquals("""{"id":"a1"}""", call["function"]!!.jsonObject["arguments"]!!.jsonPrimitive.content)
        val tool = messages[2].jsonObject
        assertEquals("tool", tool["role"]!!.jsonPrimitive.content)
        assertEquals("c1", tool["tool_call_id"]!!.jsonPrimitive.content)
        assertEquals("ok", tool["content"]!!.jsonPrimitive.content)
        val user = messages[3].jsonObject
        assertEquals("user", user["role"]!!.jsonPrimitive.content)
        assertEquals(2, user["content"]!!.jsonArray.size)
    }

    @Test
    fun `anthropic streams tool use`() = runTest {
        server.enqueue(
            sse(
                """
                event: message_start
                data: {"type":"message_start","message":{"usage":{"input_tokens":7}}}

                event: content_block_start
                data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Ok"}}

                event: content_block_stop
                data: {"type":"content_block_stop","index":0}

                event: content_block_start
                data: {"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"tu_1","name":"tap","input":{}}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"id\":"}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"\"a1\"}"}}

                event: content_block_stop
                data: {"type":"content_block_stop","index":1}

                event: message_delta
                data: {"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":9}}

                """.trimIndent()
            )
        )
        val events = AnthropicProvider(anthropicConfig, "key", client).chat(request).toList()

        assertEquals(
            listOf(
                ChatEvent.TextDelta("Ok"),
                ChatEvent.ToolCall("tu_1", "tap", """{"id":"a1"}"""),
                ChatEvent.Usage(7, 9),
                ChatEvent.Done("tool_use"),
            ),
            events,
        )
    }

    @Test
    fun `anthropic request encodes tools and tool history`() {
        val history = ChatRequest(
            model = "m",
            system = null,
            messages = listOf(
                Message(Role.ASSISTANT, listOf(Part.Text(" "), Part.ToolUse("tu_1", "tap", """{"id":"a1"}"""))),
                Message(Role.USER, listOf(Part.Text("x"), Part.ToolResult("tu_1", "boom", isError = true))),
            ),
            tools = listOf(tapTool),
        )
        val body = AnthropicProvider(anthropicConfig, null, client).requestBody(history)
        val tool = body["tools"]!!.jsonArray[0].jsonObject
        assertEquals("tap", tool["name"]!!.jsonPrimitive.content)
        assertEquals("object", tool["input_schema"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        val messages = body["messages"]!!.jsonArray
        val assistant = messages[0].jsonObject["content"]!!.jsonArray
        assertEquals(1, assistant.size)
        assertEquals("tool_use", assistant[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("a1", assistant[0].jsonObject["input"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        val user = messages[1].jsonObject["content"]!!.jsonArray
        assertEquals("tool_result", user[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("tu_1", user[0].jsonObject["tool_use_id"]!!.jsonPrimitive.content)
        assertEquals("true", user[0].jsonObject["is_error"]!!.jsonPrimitive.content)
        assertEquals("text", user[1].jsonObject["type"]!!.jsonPrimitive.content)
    }
}
