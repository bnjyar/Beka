package dev.beka.memory

import kotlinx.coroutines.test.runTest
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ExtractorTest {
    @Test
    fun `parse reads facts, episode and procedures inside a code fence`() {
        val reply = """
            Here you go:
            ```json
            {"facts": [{"content": "Suka kopi less sugar", "confidence": 0.95, "app": null, "replaces": "m3"},
                       {"content": "Mungkin vegetarian"}],
             "episode": "Pesan kopi lewat GoFood",
             "procedures": [{"app": "com.gojek.app", "task": "pesan kopi", "steps": "GoFood → cari → Tambah → Pesan", "replaces": null}]}
            ```
        """.trimIndent()
        val extraction = Extractor.parse(reply)
        assertEquals(
            listOf(FactProposal("Suka kopi less sugar", 0.95, null, 3), FactProposal("Mungkin vegetarian", 0.7)),
            extraction.facts,
        )
        assertEquals("Pesan kopi lewat GoFood", extraction.episode)
        assertEquals(listOf(ProcedureProposal("com.gojek.app", "pesan kopi: GoFood → cari → Tambah → Pesan")), extraction.procedures)
    }

    @Test
    fun `parse yields nothing for garbage and null episode`() {
        assertEquals(Extraction(), Extractor.parse("I could not find anything."))
        assertEquals(Extraction(), Extractor.parse("{not json"))
        assertEquals(Extraction(), Extractor.parse("""{"facts": [], "episode": null, "procedures": []}"""))
    }

    @Test
    fun `input shows known memory and the session with action apps`() {
        val memory = Memory(InMemoryStore(), { 0L }, ZoneOffset.UTC)
        val known = listOf(MemoryItem(4, MemoryKind.FACT, "Suka kopi", null, MemorySource.USER, createdAt = 0))
        val text = Extractor.input(
            listOf(
                SessionLine(SessionLine.Kind.USER, "kirim wa ke Rina"),
                SessionLine(SessionLine.Kind.ACTION, "✓ Buka WhatsApp", "com.whatsapp"),
                SessionLine(SessionLine.Kind.ASSISTANT, "Sudah terkirim."),
            ),
            known,
            LocalDate.of(2026, 10, 9),
            memory::line,
        )
        assertTrue("- [m4] Suka kopi" in text)
        assertTrue("<session date=\"2026-10-09\">" in text)
        assertTrue("User: kirim wa ke Rina\n  [action in com.whatsapp] ✓ Buka WhatsApp\nBeka: Sudah terkirim." in text)
    }

    @Test
    fun `tools run against memory and reject bad arguments`() = runTest {
        val store = InMemoryStore()
        val memory = Memory(store, { 1_000L }, ZoneOffset.UTC)
        val (result, note) = MemoryTools.run(memory, "remember", """{"content": "Suka kopi"}""")
        assertEquals("Remembered as [m1].", result)
        assertEquals("Diingat: Suka kopi", note)
        assertTrue("[m1]" in MemoryTools.run(memory, "recall", """{"query": "kopi"}""").first)
        assertEquals("Dilupakan: Suka kopi", MemoryTools.run(memory, "forget", """{"id": "m1"}""").second)
        assertTrue(store.all().isEmpty())
        assertFailsWith<IllegalArgumentException> { MemoryTools.run(memory, "remember", "{}") }
        assertFailsWith<IllegalArgumentException> { MemoryTools.run(memory, "forget", """{"id": "abc"}""") }
    }
}
