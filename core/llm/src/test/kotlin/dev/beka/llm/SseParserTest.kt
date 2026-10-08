package dev.beka.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SseParserTest {
    private fun parse(text: String): List<SseEvent> {
        val parser = SseParser()
        val out = text.lines().mapNotNull(parser::feed).toMutableList()
        parser.finish()?.let(out::add)
        return out
    }

    @Test
    fun `parses named events and multi-line data`() {
        val events = parse("event: a\ndata: one\ndata: two\n\n: comment\ndata:three\n\n")
        assertEquals(listOf(SseEvent("a", "one\ntwo"), SseEvent(null, "three")), events)
    }

    @Test
    fun `flushes trailing event without blank line`() {
        assertEquals(listOf(SseEvent(null, "[DONE]")), parse("data: [DONE]"))
    }

    @Test
    fun `blank lines without data emit nothing`() {
        val parser = SseParser()
        assertNull(parser.feed("event: ping"))
        assertNull(parser.feed(""))
        assertNull(parser.finish())
    }
}
