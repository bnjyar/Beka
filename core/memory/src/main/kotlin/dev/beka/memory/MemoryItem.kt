package dev.beka.memory

enum class MemoryKind { FACT, EPISODE, PROCEDURE }

/** Who wrote an item: the user in the Memory screen, end-of-session extraction, or the agent's remember tool. */
enum class MemorySource { USER, EXTRACTED, AGENT }

/**
 * One thing Beka knows. Items are never edited in place by Beka: a changed fact is a new item
 * that [supersedes] the old one, so the old version stays visible in the Memory screen.
 */
data class MemoryItem(
    val id: Long = 0,
    val kind: MemoryKind,
    /** Short text in the user's language, e.g. "Suka kopi less sugar". */
    val content: String,
    /** Null = applies everywhere. Procedures always name their app. */
    val appPackage: String? = null,
    val source: MemorySource,
    val confidence: Double = 1.0,
    /** Pinned facts always go into the prompt, whatever the budget. */
    val pinned: Boolean = false,
    /** In the review queue: not used until the user approves it. */
    val pending: Boolean = false,
    /** Id of the item this one replaces. The replaced item stops being used once this one is live. */
    val supersedes: Long? = null,
    val createdAt: Long,
    val lastUsed: Long? = null,
    val useCount: Int = 0,
)

/** Persistence for [MemoryItem]s. The Android app backs it with SQLite; tests use [InMemoryStore]. */
interface MemoryStore {
    /** Every item, including pending and superseded ones. */
    suspend fun all(): List<MemoryItem>

    /** Stores [item] (its id is ignored) and returns the new id. */
    suspend fun add(item: MemoryItem): Long

    /** Replaces the item with the same id. */
    suspend fun update(item: MemoryItem)

    suspend fun delete(ids: Collection<Long>)

    /** Bumps use_count and sets last_used for items that were put into a prompt or returned by recall. */
    suspend fun markUsed(ids: Collection<Long>, time: Long)
}

/** Items Beka actually uses: approved and not replaced by an approved newer item. */
fun List<MemoryItem>.live(): List<MemoryItem> {
    val replaced = filter { !it.pending }.mapNotNullTo(HashSet()) { it.supersedes }
    return filter { !it.pending && it.id !in replaced }
}

/** [id] plus every older version it replaced, directly or through a chain. Deleting a fact deletes its history. */
fun List<MemoryItem>.withHistory(id: Long): Set<Long> {
    val byId = associateBy { it.id }
    val ids = LinkedHashSet<Long>()
    var next: Long? = id
    while (next != null && ids.add(next)) next = byId[next]?.supersedes
    return ids
}

class InMemoryStore(items: List<MemoryItem> = emptyList()) : MemoryStore {
    private val items = items.toMutableList()
    private var nextId = (items.maxOfOrNull { it.id } ?: 0) + 1

    override suspend fun all(): List<MemoryItem> = items.toList()

    override suspend fun add(item: MemoryItem): Long {
        val id = nextId++
        items += item.copy(id = id)
        return id
    }

    override suspend fun update(item: MemoryItem) {
        val i = items.indexOfFirst { it.id == item.id }
        if (i >= 0) items[i] = item
    }

    override suspend fun delete(ids: Collection<Long>) {
        items.removeAll { it.id in ids }
    }

    override suspend fun markUsed(ids: Collection<Long>, time: Long) {
        items.replaceAll { if (it.id in ids) it.copy(useCount = it.useCount + 1, lastUsed = time) else it }
    }
}
