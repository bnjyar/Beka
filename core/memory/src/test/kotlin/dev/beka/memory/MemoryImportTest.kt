package dev.beka.memory

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MemoryImportTest {
    private fun item(id: Long, content: String, kind: MemoryKind = MemoryKind.FACT, app: String? = null, supersedes: Long? = null) =
        MemoryItem(id, kind, content, app, MemorySource.USER, supersedes = supersedes, createdAt = 1000 + id)

    @Test
    fun `skips items the store already has, ignoring case and spaces`() = runTest {
        val store = InMemoryStore(listOf(item(5, "Suka kopi")))
        val result = importMemories(
            store,
            listOf(
                item(1, "  suka KOPI "),
                item(2, "Suka kopi", app = "com.x"),
                item(3, "Suka kopi", kind = MemoryKind.EPISODE),
                item(4, "Suka teh"),
            ),
        )
        assertEquals(MemoryImportResult(added = 3, skipped = 1), result)
        assertEquals(4, store.all().size)
    }

    @Test
    fun `importing the same file twice adds nothing the second time`() = runTest {
        val store = InMemoryStore()
        val items = listOf(item(1, "a"), item(2, "b", supersedes = 1))
        importMemories(store, items)
        assertEquals(MemoryImportResult(0, 2), importMemories(store, items))
        assertEquals(2, store.all().size)
    }

    @Test
    fun `supersedes is remapped along a chain`() = runTest {
        val store = InMemoryStore(listOf(item(10, "other")))
        importMemories(store, listOf(item(3, "c", supersedes = 2), item(1, "a"), item(2, "b", supersedes = 1)))
        val byContent = store.all().associateBy { it.content }
        assertNull(byContent.getValue("a").supersedes)
        assertEquals(byContent.getValue("a").id, byContent.getValue("b").supersedes)
        assertEquals(byContent.getValue("b").id, byContent.getValue("c").supersedes)
    }

    @Test
    fun `supersedes points at the existing item when the old version was a duplicate`() = runTest {
        val store = InMemoryStore(listOf(item(7, "a")))
        importMemories(store, listOf(item(1, "a"), item(2, "b", supersedes = 1)))
        assertEquals(7L, store.all().first { it.content == "b" }.supersedes)
    }

    @Test
    fun `supersedes is null when the superseded id is not in the file`() = runTest {
        val store = InMemoryStore()
        importMemories(store, listOf(item(2, "b", supersedes = 99)))
        assertNull(store.all().single().supersedes)
    }

    @Test
    fun `codec round trips every field`() {
        val items = listOf(
            item(1, "a", app = "com.x").copy(confidence = 0.5, pinned = true, pending = true, lastUsed = 5, useCount = 3),
            item(2, "b", kind = MemoryKind.PROCEDURE, supersedes = 1),
        )
        assertEquals(items, MemoryCodec.decode(MemoryCodec.encode(items)))
    }

    @Test
    fun `decode skips unknown kinds, unknown sources and incomplete entries`() {
        val good = MemoryCodec.encode(item(1, "a"))
        val badKind = JsonObject(good + ("kind" to JsonPrimitive("DREAM")))
        val badSource = JsonObject(good + ("source" to JsonPrimitive("ALIEN")))
        val noContent = JsonObject(good - "content")
        val decoded = MemoryCodec.decode(JsonArray(listOf(badKind, good, badSource, noContent, JsonPrimitive("x"))))
        assertEquals(listOf(item(1, "a")), decoded)
    }
}
