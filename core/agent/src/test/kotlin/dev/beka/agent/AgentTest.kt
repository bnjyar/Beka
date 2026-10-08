package dev.beka.agent

import dev.beka.llm.ChatEvent
import dev.beka.llm.LlmException
import dev.beka.llm.Message
import dev.beka.llm.Part
import dev.beka.llm.Role
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AgentTest {
    private fun agent(
        provider: FakeProvider,
        device: FakeDevice,
        config: AgentConfig = AgentConfig(),
        confirm: suspend (ConfirmRequest) -> Boolean = { true },
    ) = Agent(provider, "test-model", device, confirm, config)

    private fun toolResults(message: Message): List<Part.ToolResult> =
        message.parts.filterIsInstance<Part.ToolResult>()

    @Test
    fun `plain answer without tool calls finishes immediately`() = runTest {
        val provider = FakeProvider(listOf(ChatEvent.TextDelta("Halo "), ChatEvent.TextDelta("dunia")))
        val device = FakeDevice(listOf(obs("unused")))
        val events = agent(provider, device).run(emptyList(), "apa kabar", obs("[n1] Text \"Beranda\"")).toList()

        assertEquals(AgentEvent.Finished(StopReason.DONE, "Halo dunia", 0), events.last())
        assertEquals(1, events.filterIsInstance<AgentEvent.Finished>().size)
        assertEquals(1, provider.requests.size)
        assertTrue(device.performed.isEmpty())
        assertEquals(0, device.observeCalls)
        assertEquals(
            listOf("Halo ", "dunia"),
            events.filterIsInstance<AgentEvent.TextDelta>().map { it.text },
        )
    }

    @Test
    fun `request offers tools when actions enabled and none when disabled`() = runTest {
        val withTools = FakeProvider(listOf(ChatEvent.TextDelta("a")))
        agent(withTools, FakeDevice(listOf(obs("x")))).run(emptyList(), "hi", obs("x")).toList()
        assertEquals(Tools.specs, withTools.requests[0].tools)
        assertTrue(withTools.requests[0].tools.isNotEmpty())

        val withoutTools = FakeProvider(listOf(ChatEvent.TextDelta("a")))
        agent(withoutTools, FakeDevice(listOf(obs("x"))), AgentConfig(actions = false))
            .run(emptyList(), "hi", obs("x")).toList()
        assertTrue(withoutTools.requests[0].tools.isEmpty())
    }

    @Test
    fun `first request carries user text and screen`() = runTest {
        val provider = FakeProvider(listOf(ChatEvent.TextDelta("ok")))
        agent(provider, FakeDevice(listOf(obs("x"))))
            .run(emptyList(), "tolong baca", obs("[n1] Text \"Judul Unik\"")).toList()

        val last = provider.requests[0].messages.last()
        assertEquals(Role.USER, last.role)
        assertTrue(last.text.contains("tolong baca"))
        assertTrue(last.text.contains("<screen>"))
        assertTrue(last.text.contains("Judul Unik"))
    }

    @Test
    fun `history is kept ahead of the new user message`() = runTest {
        val provider = FakeProvider(listOf(ChatEvent.TextDelta("ok")))
        val history = listOf(Message.user("lama"), Message.assistant("jawaban lama"))
        agent(provider, FakeDevice(listOf(obs("x")))).run(history, "baru", obs("x")).toList()

        val messages = provider.requests[0].messages
        assertEquals(3, messages.size)
        assertEquals(history, messages.take(2))
        assertTrue(messages[2].text.contains("baru"))
    }

    @Test
    fun `tap then done`() = runTest {
        val provider = FakeProvider(
            listOf(call("c1", "tap", """{"id":"n2"}""")),
            listOf(ChatEvent.TextDelta("Selesai")),
        )
        val device = FakeDevice(listOf(obs("screen two")))
        val events = agent(provider, device).run(emptyList(), "ketuk itu", obs("screen one")).toList()

        assertEquals(listOf<Action>(Action.Tap("n2")), device.performed)
        assertTrue(events.any { it is AgentEvent.Acting && it.action == Action.Tap("n2") && it.step == 1 })
        val acted = events.filterIsInstance<AgentEvent.Acted>().single()
        assertTrue(acted.ok)
        assertEquals("ok", acted.outcome)
        assertEquals(AgentEvent.Finished(StopReason.DONE, "Selesai", 1), events.last())

        assertEquals(2, provider.requests.size)
        val messages = provider.requests[1].messages
        assertEquals(3, messages.size)

        val assistant = messages[1]
        assertEquals(Role.ASSISTANT, assistant.role)
        assertTrue(assistant.parts.contains(Part.ToolUse("c1", "tap", """{"id":"n2"}""")))

        val reply = messages[2]
        assertEquals(Role.USER, reply.role)
        assertTrue(reply.parts.contains(Part.ToolResult("c1", "ok")))
        assertTrue(reply.text.contains("screen two"))

        val first = messages[0]
        assertFalse(first.text.contains("screen one"))
        assertTrue(first.text.contains("ketuk itu"))
        assertTrue(first.parts.contains(Part.Text(Agent.OLD_SCREEN)))
    }

    @Test
    fun `usage events are forwarded`() = runTest {
        val provider = FakeProvider(listOf(ChatEvent.Usage(10, 5), ChatEvent.TextDelta("ok")))
        val events = agent(provider, FakeDevice(listOf(obs("x")))).run(emptyList(), "hi", obs("x")).toList()
        assertTrue(events.contains(AgentEvent.Usage(10, 5)))
    }

    @Test
    fun `high risk tap declined is not performed and later calls are skipped`() = runTest {
        val provider = FakeProvider(
            listOf(call("c1", "tap", """{"id":"n3"}"""), call("c2", "tap", """{"id":"n4"}""")),
            listOf(ChatEvent.TextDelta("Baik, saya berhenti")),
        )
        val device = FakeDevice(listOf(obs("screen two")))
        device.labels["n3"] = "Kirim"
        var asked = 0
        val events = agent(provider, device, confirm = { asked++; false })
            .run(emptyList(), "kirim pesan", obs("screen one")).toList()

        assertEquals(1, asked)
        assertTrue(device.performed.isEmpty())

        val acting = events.filterIsInstance<AgentEvent.Acting>()
        assertEquals(1, acting.size)
        assertEquals(Risk.HIGH, acting[0].risk)
        val acted = events.filterIsInstance<AgentEvent.Acted>().single()
        assertFalse(acted.ok)
        assertTrue(events.indexOf(acting[0]) < events.indexOf(acted))

        assertEquals(2, provider.requests.size)
        val results = toolResults(provider.requests[1].messages.last())
        assertEquals(listOf("c1", "c2"), results.map { it.toolUseId })
        assertTrue(results[0].isError)
        assertTrue(results[0].content.contains("declined"))
        assertTrue(results[1].isError)
        assertTrue(results[1].content.contains("Not performed"))

        assertEquals(AgentEvent.Finished(StopReason.DONE, "Baik, saya berhenti", 1), events.last())
    }

    @Test
    fun `high risk tap approved is performed and confirm sees the request`() = runTest {
        val provider = FakeProvider(
            listOf(call("c1", "tap", """{"id":"n3"}""")),
            listOf(ChatEvent.TextDelta("Terkirim")),
        )
        val device = FakeDevice(listOf(obs("screen two")))
        device.labels["n3"] = "Kirim"
        var received: ConfirmRequest? = null
        val events = agent(provider, device, confirm = { request -> received = request; true })
            .run(emptyList(), "kirim", obs("screen one")).toList()

        assertEquals(listOf<Action>(Action.Tap("n3")), device.performed)
        val request = assertNotNull(received)
        assertEquals(Risk.HIGH, request.risk)
        assertEquals(Action.Tap("n3"), request.action)
        assertEquals("Example", request.appLabel)
        assertTrue(request.description.contains("Kirim"))
        assertTrue(events.filterIsInstance<AgentEvent.Acted>().single().ok)
        assertEquals(AgentEvent.Finished(StopReason.DONE, "Terkirim", 1), events.last())
    }

    @Test
    fun `medium tap asks only when confirmAll is set`() = runTest {
        suspend fun asksFor(config: AgentConfig): Pair<Int, ConfirmRequest?> {
            val provider = FakeProvider(
                listOf(call("c1", "tap", """{"id":"n1"}""")),
                listOf(ChatEvent.TextDelta("ok")),
            )
            val device = FakeDevice(listOf(obs("screen two")))
            var asked = 0
            var last: ConfirmRequest? = null
            agent(provider, device, config, confirm = { request -> asked++; last = request; true })
                .run(emptyList(), "tap", obs("screen one")).toList()
            assertEquals(1, device.performed.size)
            return asked to last
        }

        val (askedAll, request) = asksFor(AgentConfig(confirmAll = true))
        assertEquals(1, askedAll)
        assertEquals(Risk.MEDIUM, assertNotNull(request).risk)

        val (askedDefault, _) = asksFor(AgentConfig())
        assertEquals(0, askedDefault)
    }

    @Test
    fun `blocked app screen refuses tap`() = runTest {
        val provider = FakeProvider(
            listOf(call("c1", "tap", """{"id":"n1"}""")),
            listOf(ChatEvent.TextDelta("Silakan lakukan sendiri")),
        )
        val device = FakeDevice(listOf(obs("screen two", pkg = "id.dana")))
        var asked = 0
        val events = agent(provider, device, confirm = { asked++; true })
            .run(emptyList(), "bayar", obs("screen one", pkg = "id.dana", label = "DANA")).toList()

        assertTrue(device.performed.isEmpty())
        assertEquals(0, asked)
        assertTrue(events.none { it is AgentEvent.Acting })
        assertFalse(events.filterIsInstance<AgentEvent.Acted>().single().ok)

        val result = toolResults(provider.requests[1].messages.last()).single()
        assertTrue(result.isError)
        assertTrue(result.content.contains("Refused"))
        assertEquals(AgentEvent.Finished(StopReason.DONE, "Silakan lakukan sendiri", 1), events.last())
    }

    @Test
    fun `open_app resolving to a banking app is refused`() = runTest {
        val provider = FakeProvider(
            listOf(call("c1", "open_app", """{"name":"BCA"}""")),
            listOf(ChatEvent.TextDelta("ok")),
        )
        val device = FakeDevice(listOf(obs("screen two")))
        device.apps["BCA"] = "com.bca"
        val events = agent(provider, device).run(emptyList(), "buka bca", obs("screen one")).toList()

        assertTrue(device.performed.isEmpty())
        assertFalse(events.filterIsInstance<AgentEvent.Acted>().single().ok)
        val result = toolResults(provider.requests[1].messages.last()).single()
        assertTrue(result.isError)
        assertTrue(result.content.contains("Refused"))
    }

    @Test
    fun `open_app for a normal app is performed`() = runTest {
        val provider = FakeProvider(
            listOf(call("c1", "open_app", """{"name":"WhatsApp"}""")),
            listOf(ChatEvent.TextDelta("ok")),
        )
        val device = FakeDevice(listOf(obs("screen two")))
        device.apps["WhatsApp"] = "com.whatsapp"
        agent(provider, device).run(emptyList(), "buka wa", obs("screen one")).toList()
        assertEquals(listOf<Action>(Action.OpenApp("WhatsApp")), device.performed)
    }

    @Test
    fun `unchanged screen stops with no progress`() = runTest {
        val tap = listOf(call("c", "tap", """{"id":"n1"}"""))
        val provider = FakeProvider(tap, tap, tap, listOf(ChatEvent.TextDelta("Tombolnya tidak merespons")))
        val device = FakeDevice(listOf(obs("same screen")))
        val events = agent(provider, device).run(emptyList(), "ketuk", obs("same screen")).toList()

        val finished = events.last() as AgentEvent.Finished
        assertEquals(StopReason.NO_PROGRESS, finished.reason)
        assertEquals("Tombolnya tidak merespons", finished.text)
        assertEquals(3, finished.steps)
        assertEquals(4, provider.requests.size)
        assertEquals(3, device.performed.size)
        assertTrue(provider.requests[3].messages.last().text.contains("Stop acting now"))
    }

    @Test
    fun `max steps stops and skips remaining calls in the batch`() = runTest {
        val provider = FakeProvider(
            listOf(
                call("c1", "tap", """{"id":"n1"}"""),
                call("c2", "tap", """{"id":"n2"}"""),
                call("c3", "tap", """{"id":"n3"}"""),
            ),
            // The wrap-up turn: tool calls here are ignored.
            listOf(ChatEvent.TextDelta("Sudah dua"), call("c4", "tap", """{"id":"n4"}""")),
        )
        val device = FakeDevice(listOf(obs("screen two"), obs("screen three")))
        val events = agent(provider, device, AgentConfig(maxSteps = 2))
            .run(emptyList(), "ketuk semua", obs("screen one")).toList()

        assertEquals(listOf<Action>(Action.Tap("n1"), Action.Tap("n2")), device.performed)
        val finished = events.last() as AgentEvent.Finished
        assertEquals(StopReason.MAX_STEPS, finished.reason)
        assertEquals("Sudah dua", finished.text)
        assertEquals(2, finished.steps)
        assertEquals(2, provider.requests.size)
        assertEquals(2, device.performed.size)

        val results = toolResults(provider.requests[1].messages.last())
        assertEquals(listOf("c1", "c2", "c3"), results.map { it.toolUseId })
        assertFalse(results[0].isError)
        assertFalse(results[1].isError)
        assertTrue(results[2].isError)
        assertTrue(results[2].content.contains("step limit"))
    }

    @Test
    fun `invalid tool arguments are reported to the model and the loop continues`() = runTest {
        val provider = FakeProvider(
            listOf(call("c1", "tap", "{}")),
            listOf(ChatEvent.TextDelta("maaf")),
        )
        val device = FakeDevice(listOf(obs("screen two")))
        val events = agent(provider, device).run(emptyList(), "ketuk", obs("screen one")).toList()

        assertTrue(device.performed.isEmpty())
        assertTrue(events.none { it is AgentEvent.Acting })
        val result = toolResults(provider.requests[1].messages.last()).single()
        assertTrue(result.isError)
        assertTrue(result.content.contains("id"))
        assertEquals(AgentEvent.Finished(StopReason.DONE, "maaf", 1), events.last())
    }

    @Test
    fun `unknown tool is reported as an error`() = runTest {
        val provider = FakeProvider(
            listOf(call("c1", "teleport")),
            listOf(ChatEvent.TextDelta("ok")),
        )
        val device = FakeDevice(listOf(obs("screen two")))
        agent(provider, device).run(emptyList(), "x", obs("screen one")).toList()

        assertTrue(device.performed.isEmpty())
        val result = toolResults(provider.requests[1].messages.last()).single()
        assertTrue(result.isError)
        assertTrue(result.content.contains("Unknown tool"))
    }

    @Test
    fun `device failure becomes a failed Acted and an error result`() = runTest {
        val provider = FakeProvider(
            listOf(call("c1", "tap", """{"id":"n1"}""")),
            listOf(ChatEvent.TextDelta("gagal")),
        )
        val device = FakeDevice(listOf(obs("screen two")))
        device.failWith = "Element n1 not found"
        val events = agent(provider, device).run(emptyList(), "ketuk", obs("screen one")).toList()

        assertTrue(device.performed.isEmpty())
        val acted = events.filterIsInstance<AgentEvent.Acted>().single()
        assertFalse(acted.ok)
        assertTrue(acted.outcome.contains("not found"))
        val result = toolResults(provider.requests[1].messages.last()).single()
        assertTrue(result.isError)
        assertTrue(result.content.contains("not found"))
        assertEquals(AgentEvent.Finished(StopReason.DONE, "gagal", 1), events.last())
    }

    @Test
    fun `read_screen does not hit the device but re-observes`() = runTest {
        val provider = FakeProvider(
            listOf(call("c1", "read_screen")),
            listOf(ChatEvent.TextDelta("sudah")),
        )
        val device = FakeDevice(listOf(obs("screen two")))
        val events = agent(provider, device).run(emptyList(), "baca", obs("screen one")).toList()

        assertTrue(device.performed.isEmpty())
        assertEquals(1, device.observeCalls)
        val result = toolResults(provider.requests[1].messages.last()).single()
        assertFalse(result.isError)
        assertEquals(AgentEvent.Finished(StopReason.DONE, "sudah", 1), events.last())
    }

    @Test
    fun `repeated read_screen on an unchanged screen stops with no progress`() = runTest {
        val provider = FakeProvider(
            listOf(call("c1", "read_screen")),
            listOf(call("c2", "read_screen")),
            listOf(call("c3", "read_screen")),
            listOf(ChatEvent.TextDelta("Tidak ketemu")),
        )
        val device = FakeDevice(listOf(obs("same")))
        val events = agent(provider, device).run(emptyList(), "cari", obs("same")).toList()

        assertEquals(StopReason.NO_PROGRESS, (events.last() as AgentEvent.Finished).reason)
        assertEquals(4, provider.requests.size)
    }

    @Test
    fun `rate limit is retried before anything streamed`() = runTest {
        val provider = FakeProvider(listOf(ChatEvent.TextDelta("halo")))
        provider.errors[0] = null to LlmException("HTTP 429", 429)
        provider.errors[1] = null to LlmException("HTTP 503", 503)
        val events = agent(provider, FakeDevice(emptyList())).run(emptyList(), "hai", obs("s")).toList()

        assertEquals(AgentEvent.Finished(StopReason.DONE, "halo", 0), events.last())
        assertEquals(3, provider.requests.size)
    }

    @Test
    fun `errors after streaming started or client errors are not retried`() = runTest {
        val midStream = FakeProvider()
        midStream.errors[0] = "sebagian" to LlmException("HTTP 429", 429)
        val e1 = runCatching { agent(midStream, FakeDevice(emptyList())).run(emptyList(), "hai", obs("s")).toList() }
        assertTrue(e1.exceptionOrNull() is LlmException)

        val badRequest = FakeProvider()
        badRequest.errors[0] = null to LlmException("HTTP 404", 404)
        val e2 = runCatching { agent(badRequest, FakeDevice(emptyList())).run(emptyList(), "hai", obs("s")).toList() }
        assertEquals(404, (e2.exceptionOrNull() as LlmException).status)
        assertEquals(1, badRequest.requests.size)
    }

    @Test
    fun `tool call leaked as text gets one nudge`() = runTest {
        val leaked = "Saya ketuk.\n<｜DSML｜tool_calls>\n<｜DSML｜invoke name=\"tap\">"
        val provider = FakeProvider(
            listOf(ChatEvent.TextDelta(leaked)),
            listOf(call("c1", "tap", """{"id":"n1"}""")),
            listOf(ChatEvent.TextDelta("Selesai")),
        )
        val device = FakeDevice(listOf(obs("screen two")))
        val events = agent(provider, device).run(emptyList(), "putar", obs("screen one")).toList()

        assertTrue(provider.requests[1].messages.last().text.contains("not executed"))
        assertEquals(listOf<Action>(Action.Tap("n1")), device.performed)
        assertEquals(AgentEvent.Finished(StopReason.DONE, "Selesai", 1), events.last())
    }

    @Test
    fun `empty reply is nudged only once`() = runTest {
        val provider = FakeProvider(listOf(), listOf())
        val events = agent(provider, FakeDevice(emptyList())).run(emptyList(), "hai", obs("s")).toList()

        assertEquals(2, provider.requests.size)
        assertEquals(AgentEvent.Finished(StopReason.DONE, "", 0), events.last())
    }

    @Test
    fun `plain chat mode is never nudged`() = runTest {
        val provider = FakeProvider(listOf())
        agent(provider, FakeDevice(emptyList()), AgentConfig(actions = false)).run(emptyList(), "hai", obs("s")).toList()
        assertEquals(1, provider.requests.size)
    }
}
