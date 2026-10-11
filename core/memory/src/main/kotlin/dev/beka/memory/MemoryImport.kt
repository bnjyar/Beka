package dev.beka.memory

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

data class MemoryImportResult(val added: Int, val skipped: Int)

/**
 * Merges [incoming] items into [store] without touching what is already there. An item the store
 * already has (same kind, app and content, ignoring case and outer spaces) is skipped. Ids from the
 * file are only used to rebuild the [MemoryItem.supersedes] links between the items that came in.
 */
suspend fun importMemories(store: MemoryStore, incoming: List<MemoryItem>): MemoryImportResult {
    fun key(item: MemoryItem) = Triple(item.kind, item.appPackage, item.content.trim().lowercase())
    val existing = HashMap<Triple<MemoryKind, String?, String>, Long>()
    store.all().forEach { existing.putIfAbsent(key(it), it.id) }
    val idMap = HashMap<Long, Long>()
    var added = 0
    var skipped = 0
    for (item in incoming.sortedBy { it.id }) {
        val same = existing[key(item)]
        if (same != null) {
            idMap[item.id] = same
            skipped++
            continue
        }
        val newId = store.add(item.copy(id = 0, supersedes = item.supersedes?.let { idMap[it] }))
        existing[key(item)] = newId
        idMap[item.id] = newId
        added++
    }
    return MemoryImportResult(added, skipped)
}

/** JSON for backup files; ids are kept so the supersedes links survive the round trip. */
object MemoryCodec {
    fun encode(items: List<MemoryItem>): JsonArray = JsonArray(items.map(::encode))

    fun encode(item: MemoryItem): JsonObject = buildJsonObject {
        put("id", item.id)
        put("kind", item.kind.name)
        put("content", item.content)
        put("app_package", item.appPackage)
        put("source", item.source.name)
        put("confidence", item.confidence)
        put("pinned", item.pinned)
        put("pending", item.pending)
        put("supersedes", item.supersedes)
        put("created_at", item.createdAt)
        put("last_used", item.lastUsed)
        put("use_count", item.useCount)
    }

    /** Entries with an unknown kind or source, or without id, kind, content, source or created_at, are dropped. */
    fun decode(array: JsonArray): List<MemoryItem> = array.mapNotNull(::decodeOrNull)

    fun decodeOrNull(element: JsonElement): MemoryItem? {
        val o = element as? JsonObject ?: return null
        fun prim(name: String): JsonPrimitive? = (o[name] as? JsonPrimitive)?.takeIf { it !is JsonNull }
        val kind = prim("kind")?.contentOrNull?.let { n -> MemoryKind.entries.firstOrNull { it.name == n } } ?: return null
        val source = prim("source")?.contentOrNull?.let { n -> MemorySource.entries.firstOrNull { it.name == n } } ?: return null
        val content = prim("content")?.contentOrNull ?: return null
        val id = prim("id")?.longOrNull ?: return null
        val createdAt = prim("created_at")?.longOrNull ?: return null
        return MemoryItem(
            id = id,
            kind = kind,
            content = content,
            appPackage = prim("app_package")?.contentOrNull,
            source = source,
            confidence = prim("confidence")?.doubleOrNull ?: 1.0,
            pinned = prim("pinned")?.booleanOrNull ?: false,
            pending = prim("pending")?.booleanOrNull ?: false,
            supersedes = prim("supersedes")?.longOrNull,
            createdAt = createdAt,
            lastUsed = prim("last_used")?.longOrNull,
            useCount = prim("use_count")?.intOrNull ?: 0,
        )
    }
}
