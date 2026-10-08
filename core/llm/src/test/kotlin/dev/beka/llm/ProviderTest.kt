package dev.beka.llm

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
}
