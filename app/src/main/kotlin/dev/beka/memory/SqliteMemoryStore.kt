package dev.beka.memory

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/** [MemoryStore] on plain SQLite; there are few enough rows that [all] is read whole. */
class SqliteMemoryStore(context: Context) : MemoryStore {
    private val helper = object : SQLiteOpenHelper(context.applicationContext, "memory.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE memory (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  kind TEXT NOT NULL,
                  content TEXT NOT NULL,
                  app_package TEXT,
                  source TEXT NOT NULL,
                  confidence REAL NOT NULL DEFAULT 1.0,
                  pinned INTEGER NOT NULL DEFAULT 0,
                  pending INTEGER NOT NULL DEFAULT 0,
                  supersedes INTEGER,
                  created_at INTEGER NOT NULL,
                  last_used INTEGER,
                  use_count INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent(),
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    }

    private val _changes = MutableStateFlow(0)

    /** Bumped after every write so the Memory screen knows to reload. */
    val changes: StateFlow<Int> = _changes

    override suspend fun all(): List<MemoryItem> = withContext(Dispatchers.IO) {
        helper.readableDatabase.query("memory", null, null, null, null, null, "id ASC").use { c ->
            val out = ArrayList<MemoryItem>()
            while (c.moveToNext()) read(c)?.let(out::add)
            out
        }
    }

    override suspend fun add(item: MemoryItem): Long {
        val id = withContext(Dispatchers.IO) { helper.writableDatabase.insert("memory", null, values(item)) }
        _changes.value++
        return id
    }

    override suspend fun update(item: MemoryItem) {
        withContext(Dispatchers.IO) {
            helper.writableDatabase.update("memory", values(item), "id = ?", arrayOf(item.id.toString()))
        }
        _changes.value++
    }

    override suspend fun delete(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        withContext(Dispatchers.IO) {
            val db = helper.writableDatabase
            db.beginTransaction()
            try {
                ids.forEach { db.delete("memory", "id = ?", arrayOf(it.toString())) }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
        _changes.value++
    }

    override suspend fun markUsed(ids: Collection<Long>, time: Long) {
        if (ids.isEmpty()) return
        withContext(Dispatchers.IO) {
            val db = helper.writableDatabase
            db.beginTransaction()
            try {
                ids.forEach {
                    db.execSQL(
                        "UPDATE memory SET use_count = use_count + 1, last_used = ? WHERE id = ?",
                        arrayOf<Any>(time, it),
                    )
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
        _changes.value++
    }

    private fun values(item: MemoryItem) = ContentValues().apply {
        put("kind", item.kind.name)
        put("content", item.content)
        put("app_package", item.appPackage)
        put("source", item.source.name)
        put("confidence", item.confidence)
        put("pinned", if (item.pinned) 1 else 0)
        put("pending", if (item.pending) 1 else 0)
        put("supersedes", item.supersedes)
        put("created_at", item.createdAt)
        put("last_used", item.lastUsed)
        put("use_count", item.useCount)
    }

    /** Null for a row with an enum name this build does not know, so a bad row never breaks the list. */
    private fun read(c: Cursor): MemoryItem? {
        fun col(name: String) = c.getColumnIndexOrThrow(name)
        val kind = runCatching { MemoryKind.valueOf(c.getString(col("kind"))) }.getOrNull() ?: return null
        val source = runCatching { MemorySource.valueOf(c.getString(col("source"))) }.getOrNull() ?: return null
        val supersedes = col("supersedes")
        val lastUsed = col("last_used")
        val appPackage = col("app_package")
        return MemoryItem(
            id = c.getLong(col("id")),
            kind = kind,
            content = c.getString(col("content")),
            appPackage = if (c.isNull(appPackage)) null else c.getString(appPackage),
            source = source,
            confidence = c.getDouble(col("confidence")),
            pinned = c.getInt(col("pinned")) != 0,
            pending = c.getInt(col("pending")) != 0,
            supersedes = if (c.isNull(supersedes)) null else c.getLong(supersedes),
            createdAt = c.getLong(col("created_at")),
            lastUsed = if (c.isNull(lastUsed)) null else c.getLong(lastUsed),
            useCount = c.getInt(col("use_count")),
        )
    }
}
