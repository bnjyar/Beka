package dev.beka.rules

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** One time a rule's trigger and conditions matched, and what came of it. */
data class RuleRun(
    val id: Long = 0,
    val ruleId: String,
    val ruleName: String,
    val time: Long,
    /** Short description of the event, e.g. "Shopee dibuka". */
    val trigger: String,
    val level: Level?,
    /** notified, suggested, nothing, acted, skipped, error. */
    val outcome: String,
    val text: String = "",
    /** False for runs that did not happen (skipped); those do not count toward cooldown or budget. */
    val counted: Boolean = true,
    /** The user marked it as a trigger that should not have fired. */
    val wrong: Boolean = false,
)

/**
 * Rules, per-app permissions and the run history, in rules.db. Rules and permissions are few and
 * kept in memory; the engine reads them on every event.
 */
class RulesStore(context: Context) {
    private val helper = object : SQLiteOpenHelper(context.applicationContext, "rules.db", null, SCHEMA) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE rules (id TEXT PRIMARY KEY, json TEXT NOT NULL, created_at INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE app_permission (package TEXT PRIMARY KEY, level TEXT NOT NULL)")
            db.execSQL(
                """
                CREATE TABLE runs (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  rule_id TEXT NOT NULL,
                  rule_name TEXT NOT NULL,
                  time INTEGER NOT NULL,
                  trigger TEXT NOT NULL,
                  level TEXT,
                  outcome TEXT NOT NULL,
                  text TEXT NOT NULL DEFAULT '',
                  counted INTEGER NOT NULL DEFAULT 1,
                  wrong INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX runs_rule_time ON runs (rule_id, time)")
        }

        // Add one step per version bump. onCreate always creates the latest schema: never change it
        // without bumping SCHEMA and adding a step here.
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            for (from in oldVersion until newVersion) {
                when (from) {
                    else -> error("no migration from v$from")
                }
            }
        }
    }

    private val _rules = MutableStateFlow<List<Rule>>(emptyList())
    val rules: StateFlow<List<Rule>> = _rules

    private val _permissions = MutableStateFlow<Map<String, AppPermission>>(emptyMap())
    val permissions: StateFlow<Map<String, AppPermission>> = _permissions

    private val _runsChanged = MutableStateFlow(0)

    /** Bumped after every run written or marked, so the history reloads. */
    val runsChanged: StateFlow<Int> = _runsChanged

    init {
        load()
    }

    private fun load() {
        val db = helper.readableDatabase
        _rules.value = db.query("rules", arrayOf("json"), null, null, null, null, "created_at ASC").use { c ->
            buildList {
                while (c.moveToNext()) {
                    runCatching { Rule.fromJson(c.getString(0)) }
                        .onSuccess(::add)
                        .onFailure { Log.w(TAG, "unreadable rule skipped", it) }
                }
            }
        }
        _permissions.value = db.query("app_permission", null, null, null, null, null, null).use { c ->
            buildMap {
                while (c.moveToNext()) {
                    runCatching { AppPermission.valueOf(c.getString(1)) }.onSuccess { put(c.getString(0), it) }
                }
            }
        }
    }

    suspend fun save(rule: Rule) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put("id", rule.id)
            put("json", rule.toJson())
            put("created_at", System.currentTimeMillis())
        }
        val db = helper.writableDatabase
        // Keep the original creation time (and so the list order) when a rule is edited.
        if (db.update("rules", ContentValues().apply { put("json", rule.toJson()) }, "id = ?", arrayOf(rule.id)) == 0) {
            db.insert("rules", null, values)
        }
        _rules.update { list ->
            if (list.any { it.id == rule.id }) list.map { if (it.id == rule.id) rule else it } else list + rule
        }
    }

    suspend fun delete(ruleId: String) = withContext(Dispatchers.IO) {
        helper.writableDatabase.delete("rules", "id = ?", arrayOf(ruleId))
        _rules.update { list -> list.filterNot { it.id == ruleId } }
    }

    /** Null removes the entry, which turns proactive rules off for that app. */
    suspend fun setPermission(pkg: String, permission: AppPermission?) = withContext(Dispatchers.IO) {
        val db = helper.writableDatabase
        if (permission == null) {
            db.delete("app_permission", "package = ?", arrayOf(pkg))
        } else {
            db.insertWithOnConflict(
                "app_permission", null,
                ContentValues().apply { put("package", pkg); put("level", permission.name) },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
        }
        _permissions.update { map -> if (permission == null) map - pkg else map + (pkg to permission) }
    }

    suspend fun addRun(run: RuleRun): Long = withContext(Dispatchers.IO) {
        val id = helper.writableDatabase.insert(
            "runs", null,
            ContentValues().apply {
                put("rule_id", run.ruleId)
                put("rule_name", run.ruleName)
                put("time", run.time)
                put("trigger", run.trigger)
                put("level", run.level?.name)
                put("outcome", run.outcome)
                put("text", run.text)
                put("counted", if (run.counted) 1 else 0)
            },
        )
        _runsChanged.value++
        id
    }

    suspend fun updateRun(id: Long, outcome: String, text: String, counted: Boolean = true) = withContext(Dispatchers.IO) {
        helper.writableDatabase.update(
            "runs",
            ContentValues().apply { put("outcome", outcome); put("text", text); put("counted", if (counted) 1 else 0) },
            "id = ?", arrayOf(id.toString()),
        )
        _runsChanged.value++
    }

    suspend fun markWrong(id: Long, wrong: Boolean) = withContext(Dispatchers.IO) {
        helper.writableDatabase.update("runs", ContentValues().apply { put("wrong", if (wrong) 1 else 0) }, "id = ?", arrayOf(id.toString()))
        _runsChanged.value++
    }

    suspend fun recentRuns(limit: Int = 50): List<RuleRun> = withContext(Dispatchers.IO) {
        helper.readableDatabase.query("runs", null, null, null, null, null, "time DESC", limit.toString()).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        RuleRun(
                            id = c.getLong(c.getColumnIndexOrThrow("id")),
                            ruleId = c.getString(c.getColumnIndexOrThrow("rule_id")),
                            ruleName = c.getString(c.getColumnIndexOrThrow("rule_name")),
                            time = c.getLong(c.getColumnIndexOrThrow("time")),
                            trigger = c.getString(c.getColumnIndexOrThrow("trigger")),
                            level = c.getString(c.getColumnIndexOrThrow("level"))?.let { runCatching { Level.valueOf(it) }.getOrNull() },
                            outcome = c.getString(c.getColumnIndexOrThrow("outcome")),
                            text = c.getString(c.getColumnIndexOrThrow("text")),
                            counted = c.getInt(c.getColumnIndexOrThrow("counted")) == 1,
                            wrong = c.getInt(c.getColumnIndexOrThrow("wrong")) == 1,
                        ),
                    )
                }
            }
        }
    }

    /** Blocking; call off the main thread. */
    fun lastRun(ruleId: String): LocalDateTime? =
        helper.readableDatabase.rawQuery(
            "SELECT MAX(time) FROM runs WHERE rule_id = ? AND counted = 1", arrayOf(ruleId),
        ).use { c -> if (c.moveToFirst() && !c.isNull(0)) toLocal(c.getLong(0)) else null }

    /** Blocking; call off the main thread. */
    fun runsToday(ruleId: String): Int =
        helper.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM runs WHERE rule_id = ? AND counted = 1 AND time >= ?", arrayOf(ruleId, startOfToday().toString()),
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    /** Old history is dropped; the gate only looks back about a week. */
    fun prune(days: Int = 30) {
        val cutoff = System.currentTimeMillis() - days * 24L * 3600 * 1000
        helper.writableDatabase.delete("runs", "time < ?", arrayOf(cutoff.toString()))
    }

    private fun startOfToday(): Long = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun toLocal(millis: Long): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault())

    private companion object {
        const val SCHEMA = 1
        const val TAG = "BekaRules"
    }
}
