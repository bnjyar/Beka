package dev.beka.data

import android.content.Context
import dev.beka.BekaApp
import dev.beka.BuildConfig
import dev.beka.memory.MemoryCodec
import dev.beka.memory.MemoryItem
import dev.beka.memory.importMemories
import dev.beka.rules.AppPermission
import dev.beka.rules.Rule
import dev.beka.rules.RuleJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/** A message that is fine to show the user as it is (Indonesian). */
class BackupException(message: String) : Exception(message)

/** What a backup file holds, parsed and validated; nothing has been written anywhere yet. */
class Backup(
    val memory: List<MemoryItem>,
    val rules: List<Rule>,
    /** Rules in the file that could not be read. */
    val skippedRules: Int,
    val permissions: Map<String, AppPermission>,
    /** Prefs name to key to typed value (Boolean, Int, Long, Float, String or Set<String>), whitelist already applied. */
    val settings: Map<String, Map<String, Any>>,
) {
    val settingCount: Int get() = settings.values.sumOf { it.size }
}

class ExportSummary(val memory: Int, val rules: Int)

class ImportSummary(
    val memoryAdded: Int,
    val memorySkipped: Int,
    val rulesAdded: Int,
    val rulesReplaced: Int,
    val rulesSkipped: Int,
    val permissions: Int,
    val settings: Int,
)

/**
 * Exports memory, rules, per-app permissions and a whitelist of settings to one JSON file, and
 * merges such a file back in. API keys, header values, OAuth tokens and the run history never go
 * into it; import only adds or overwrites and never deletes.
 */
class BackupManager(private val app: BekaApp) {
    suspend fun export(out: OutputStream): ExportSummary = withContext(Dispatchers.IO) {
        val memory = app.memoryStore.all()
        val rules = app.rulesStore.rules.value
        val file = buildJsonObject {
            put("format", FORMAT)
            put("version", VERSION)
            put("app", BuildConfig.VERSION_NAME)
            put("exported_at", System.currentTimeMillis())
            put("memory", MemoryCodec.encode(memory))
            put("rules", JsonArray(rules.map { RuleJson.lenient.parseToJsonElement(it.toJson()) }))
            put("permissions", buildJsonObject { app.rulesStore.permissions.value.forEach { (pkg, p) -> put(pkg, p.name) } })
            put("settings", exportSettings())
        }
        out.write(pretty.encodeToString(JsonElement.serializer(), file).toByteArray(Charsets.UTF_8))
        out.flush()
        ExportSummary(memory.size, rules.size)
    }

    /** Reads and validates the whole file. Throws [BackupException] with a user-facing message. */
    suspend fun read(input: InputStream): Backup = withContext(Dispatchers.IO) {
        val text = readLimited(input)
        val root = try {
            RuleJson.lenient.parseToJsonElement(text) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: throw BackupException(NOT_BACKUP)
        if ((root["format"] as? JsonPrimitive)?.contentOrNull != FORMAT) throw BackupException(NOT_BACKUP)
        val version = (root["version"] as? JsonPrimitive)?.intOrNull ?: throw BackupException(NOT_BACKUP)
        if (version > VERSION) throw BackupException("Cadangan ini dari versi Beka yang lebih baru. Perbarui Beka dulu.")

        val memory = MemoryCodec.decode(root["memory"] as? JsonArray ?: JsonArray(emptyList()))
        val ruleElements = root["rules"] as? JsonArray ?: JsonArray(emptyList())
        val rules = ruleElements.mapNotNull { el -> runCatching { Rule.fromJson(el.toString()) }.getOrNull() }
        val permissions = buildMap {
            (root["permissions"] as? JsonObject)?.forEach { (pkg, v) ->
                val level = (v as? JsonPrimitive)?.contentOrNull?.let { n -> AppPermission.entries.firstOrNull { it.name == n } }
                if (pkg.isNotBlank() && level != null) put(pkg, level)
            }
        }
        Backup(memory, rules, ruleElements.size - rules.size, permissions, parseSettings(root["settings"] as? JsonObject))
    }

    /** Writes a backup that [read] already accepted, then refreshes every in-memory copy. */
    suspend fun apply(backup: Backup): ImportSummary {
        val summary = withContext(Dispatchers.IO) {
            for ((name, values) in backup.settings) {
                val edit = app.getSharedPreferences(name, Context.MODE_PRIVATE).edit()
                values.forEach { (key, v) ->
                    when (v) {
                        is Boolean -> edit.putBoolean(key, v)
                        is Int -> edit.putInt(key, v)
                        is Long -> edit.putLong(key, v)
                        is Float -> edit.putFloat(key, v)
                        is String -> edit.putString(key, v)
                        is Set<*> -> edit.putStringSet(key, v.filterIsInstance<String>().toSet())
                    }
                }
                edit.commit()
            }
            val known = app.rulesStore.rules.value.mapTo(HashSet()) { it.id }
            var added = 0
            var replaced = 0
            for (rule in backup.rules) {
                if (rule.id in known) replaced++ else added++
                app.rulesStore.save(rule)
                known += rule.id
            }
            backup.permissions.forEach { (pkg, p) -> app.rulesStore.setPermission(pkg, p) }
            val mem = importMemories(app.memoryStore, backup.memory)
            ImportSummary(mem.added, mem.skipped, added, replaced, backup.skippedRules, backup.permissions.size, backup.settingCount)
        }
        withContext(Dispatchers.Main) {
            app.providers.reload()
            app.webSettings.reload()
            app.mcpSettings.reload()
            app.confirmSettings.reload()
            app.appearance.reload()
            app.memorySettings.reload()
            app.proactiveSettings.reload()
            app.voiceSettings.reload()
            app.updateSettings.reload()
        }
        app.rulesStore.reload()
        return summary
    }

    private fun exportSettings(): JsonObject = buildJsonObject {
        for ((name, allowed) in SETTINGS) {
            val prefs = app.getSharedPreferences(name, Context.MODE_PRIVATE)
            val entries = prefs.all.filterKeys(allowed).mapNotNull { (key, v) -> encodeValue(v)?.let { key to it } }
            if (entries.isNotEmpty()) put(name, JsonObject(entries.toMap()))
        }
    }

    private fun encodeValue(v: Any?): JsonObject? = when (v) {
        is Boolean -> typed("b", JsonPrimitive(v))
        is Int -> typed("i", JsonPrimitive(v))
        is Long -> typed("l", JsonPrimitive(v))
        is Float -> typed("f", JsonPrimitive(v))
        is String -> typed("s", JsonPrimitive(v))
        is Set<*> -> typed("ss", buildJsonArray { v.filterIsInstance<String>().sorted().forEach { add(JsonPrimitive(it)) } })
        else -> null
    }

    private fun typed(t: String, v: JsonElement) = buildJsonObject { put("t", t); put("v", v) }

    private fun parseSettings(obj: JsonObject?): Map<String, Map<String, Any>> {
        val out = LinkedHashMap<String, Map<String, Any>>()
        for ((name, allowed) in SETTINGS) {
            val values = LinkedHashMap<String, Any>()
            (obj?.get(name) as? JsonObject)?.forEach { (key, el) ->
                if (allowed(key)) decodeValue(el)?.let { values[key] = it }
            }
            if (values.isNotEmpty()) out[name] = values
        }
        return out
    }

    private fun decodeValue(el: JsonElement): Any? {
        val o = el as? JsonObject ?: return null
        val t = (o["t"] as? JsonPrimitive)?.contentOrNull
        val v = o["v"] ?: return null
        if (t == "ss") return (v as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }?.toSet()
        val p = v as? JsonPrimitive ?: return null
        return when (t) {
            "b" -> p.booleanOrNull
            "i" -> p.intOrNull
            "l" -> p.longOrNull
            "f" -> p.floatOrNull
            "s" -> p.takeIf { it.isString }?.content
            else -> null
        }
    }

    private fun readLimited(input: InputStream): String {
        val buf = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        while (true) {
            val n = input.read(chunk)
            if (n < 0) break
            buf.write(chunk, 0, n)
            if (buf.size() > MAX_BYTES) throw BackupException(NOT_BACKUP)
        }
        return buf.toString(Charsets.UTF_8.name())
    }

    companion object {
        const val FORMAT = "beka-backup"

        /** Bump when the file's shape changes; files of older versions must still import. */
        const val VERSION = 1
        private const val MAX_BYTES = 16 * 1024 * 1024
        private const val NOT_BACKUP = "File ini bukan cadangan Beka."
        private val pretty = Json { prettyPrint = true }

        /**
         * The only SharedPreferences that go into a backup, and which keys of each. Everything else
         * (overlay, debug, oauth, adb, virtual_screen, anything encrypted with SecretBox) stays out;
         * the same filter is applied again on import.
         */
        private val SETTINGS: Map<String, (String) -> Boolean> = mapOf(
            "providers" to { k -> !k.startsWith("key_") },
            "web" to { k -> !k.startsWith("key_") },
            "mcp" to { k -> !k.startsWith("value_") && !k.startsWith("cache_") && !k.startsWith("notes_") },
            "confirm" to { _ -> true },
            "appearance" to { k -> k != "onboarded" },
            "memory" to { _ -> true },
            "proactive" to { k -> k == "enabled" || k == "daily_calls" },
            "voice" to { _ -> true },
            "update" to { k -> k == "channel" || k == "auto_check" },
        )
    }
}
