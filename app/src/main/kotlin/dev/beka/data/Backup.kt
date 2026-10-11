package dev.beka.data

import android.content.Context
import android.net.Uri
import dev.beka.BekaApp
import dev.beka.BuildConfig
import dev.beka.R
import dev.beka.llm.ProviderConfig
import dev.beka.memory.MemoryCodec
import dev.beka.memory.MemoryItem
import dev.beka.memory.importMemories
import dev.beka.rules.AppPermission
import dev.beka.rules.Rule
import dev.beka.rules.RuleJson
import dev.beka.settings.AppearanceSettings
import dev.beka.voice.VoiceSettings
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
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.InputStream

/** A message that is fine to show the user as it is (in the app language). */
class BackupException(message: String) : Exception(message)

/** What a backup file holds, parsed and validated; nothing has been written anywhere yet. */
class Backup(
    val memory: List<MemoryItem>,
    val rules: List<Rule>,
    /** Rules in the file that could not be read. */
    val skippedRules: Int,
    val permissions: Map<String, AppPermission>,
    /** Prefs name to key to typed value, already checked against [BackupManager.SETTINGS]. */
    val settings: Map<String, Map<String, Any>>,
    /** Provider configs in the file, each one readable; without extra headers. */
    val providers: List<ProviderConfig>,
    /** MCP server entries in the file (id + url at least), without header values. */
    val mcpServers: List<JsonObject>,
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
    /** Providers and MCP servers added from the file; ones already on this phone are left as they are. */
    val providers: Int,
    val mcpServers: Int,
)

/**
 * Exports memory, rules, per-app permissions and a whitelist of settings to one JSON file, and
 * merges such a file back in. API keys, header values, extra headers, URL query strings, OAuth tokens,
 * confirmation settings and the run history never go into it. Import only adds or overwrites and
 * never deletes. A file may come from someone else, so importing one can never pair a key already on
 * this phone with another host, start an MCP server's tools without asking, or switch confirmations off.
 */
class BackupManager(private val app: BekaApp) {
    suspend fun export(uri: Uri): ExportSummary = withContext(Dispatchers.IO) {
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
            put("providers", JsonArray(app.providers.providers.value.map { providerJson.encodeToJsonElement(ProviderConfig.serializer(), it.copy(extraHeaders = emptyMap())) }))
            put("mcp_servers", JsonArray(localServers().mapNotNull(::cleanServer)))
        }
        val out = app.contentResolver.openOutputStream(uri, "wt") ?: throw BackupException(app.getString(R.string.backup_cannot_write))
        out.use {
            it.write(pretty.encodeToString(JsonElement.serializer(), file).toByteArray(Charsets.UTF_8))
            it.flush()
        }
        ExportSummary(memory.size, rules.size)
    }

    /** Reads and validates the whole file. Throws [BackupException] with a user-facing message. */
    suspend fun read(uri: Uri): Backup = withContext(Dispatchers.IO) {
        val input = app.contentResolver.openInputStream(uri) ?: throw BackupException(app.getString(R.string.backup_cannot_open))
        val text = input.use(::readLimited)
        val root = try {
            RuleJson.lenient.parseToJsonElement(text) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: throw BackupException(notBackup())
        if ((root["format"] as? JsonPrimitive)?.contentOrNull != FORMAT) throw BackupException(notBackup())
        val version = (root["version"] as? JsonPrimitive)?.intOrNull ?: throw BackupException(notBackup())
        if (version > VERSION) throw BackupException(app.getString(R.string.backup_too_new))

        val memory = MemoryCodec.decode(root["memory"] as? JsonArray ?: JsonArray(emptyList()))
        val ruleElements = root["rules"] as? JsonArray ?: JsonArray(emptyList())
        val rules = ruleElements.mapNotNull { el -> runCatching { Rule.fromJson(el.toString()) }.getOrNull() }
        val permissions = buildMap {
            (root["permissions"] as? JsonObject)?.forEach { (pkg, v) ->
                val level = (v as? JsonPrimitive)?.contentOrNull?.let { n -> AppPermission.entries.firstOrNull { it.name == n } }
                if (pkg.isNotBlank() && level != null) put(pkg, level)
            }
        }
        val providers = (root["providers"] as? JsonArray).orEmpty().mapNotNull { el ->
            runCatching { providerJson.decodeFromJsonElement(ProviderConfig.serializer(), el) }.getOrNull()
                ?.takeIf { it.id.isNotBlank() && it.baseUrl.isNotBlank() }
                ?.copy(extraHeaders = emptyMap())
        }.distinctBy { it.id }
        val servers = (root["mcp_servers"] as? JsonArray).orEmpty().mapNotNull(::cleanServer).distinctBy(::idOf)
        Backup(
            memory, rules, ruleElements.size - rules.size, permissions,
            parseSettings(root["settings"] as? JsonObject), providers, servers,
        )
    }

    /** Writes a backup that [read] already accepted, then refreshes every in-memory copy. */
    private fun notBackup() = app.getString(R.string.backup_not_backup)

    suspend fun apply(backup: Backup): ImportSummary {
        try {
            return withContext(Dispatchers.IO) {
                // Databases first: they are the user's data. Settings last, so a failure above leaves them untouched.
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
                val providers = addProviders(backup)
                val servers = addServers(backup.mcpServers)
                for ((name, values) in backup.settings) {
                    val edit = app.getSharedPreferences(name, Context.MODE_PRIVATE).edit()
                    values.forEach { (key, v) ->
                        when (v) {
                            is Boolean -> edit.putBoolean(key, v)
                            is Int -> edit.putInt(key, v)
                            is Float -> edit.putFloat(key, v)
                            is String -> edit.putString(key, v)
                            is Set<*> -> edit.putStringSet(key, v.filterIsInstance<String>().toSet())
                        }
                    }
                    edit.commit()
                }
                ImportSummary(
                    mem.added, mem.skipped, added, replaced, backup.skippedRules,
                    backup.permissions.size, backup.settingCount, providers, servers,
                )
            }
        } finally {
            // Also after a failure half way: in-memory copies must match storage, or the next save overwrites it.
            withContext(Dispatchers.Main) {
                app.providers.reload()
                app.webSettings.reload()
                app.mcpSettings.reload()
                app.appearance.reload()
                app.memorySettings.reload()
                app.proactiveSettings.reload()
                app.voiceSettings.reload()
                app.updateSettings.reload()
            }
            app.rulesStore.reload()
        }
    }

    /**
     * Adds the file's providers whose id is new here; a provider already on this phone is never
     * changed, since its stored key would then go to whatever base URL the file names. Any stray
     * key stored under a new id is removed for the same reason. The file's active provider is taken
     * only when this phone had none.
     */
    private fun addProviders(backup: Backup): Int {
        val prefs = app.getSharedPreferences("providers", Context.MODE_PRIVATE)
        val local = app.providers.providers.value
        val localIds = local.mapTo(HashSet()) { it.id }
        val fresh = backup.providers.filter { it.id !in localIds }
        val edit = prefs.edit()
        if (fresh.isNotEmpty()) {
            fresh.forEach { edit.remove(PROVIDER_KEY_PREFIX + it.id) }
            edit.putString(PROVIDERS_LIST, providerJson.encodeToString(providerList, local + fresh))
        }
        val active = backup.settings["providers"]?.get(PROVIDERS_ACTIVE) as? String
        if (local.isEmpty() && active != null && fresh.any { it.id == active }) edit.putString(PROVIDERS_ACTIVE, active)
        edit.commit()
        return fresh.size
    }

    /** Adds the file's MCP servers whose id is new here, with no header value and tools that ask before running. */
    private fun addServers(incoming: List<JsonObject>): Int {
        val prefs = app.getSharedPreferences("mcp", Context.MODE_PRIVATE)
        val local = localServers()
        val localIds = local.mapNotNullTo(HashSet(), ::idOf)
        val fresh = incoming.filter { idOf(it) !in localIds }
        if (fresh.isEmpty()) return 0
        val edit = prefs.edit()
        fresh.forEach { s ->
            val id = idOf(s)
            listOf("value_", "cache_", "notes_").forEach { edit.remove(it + id) }
        }
        edit.putString(MCP_SERVERS, JsonArray(local + fresh).toString()).commit()
        return fresh.size
    }

    private fun localServers(): List<JsonElement> =
        app.getSharedPreferences("mcp", Context.MODE_PRIVATE).getString(MCP_SERVERS, null)
            ?.let { runCatching { RuleJson.lenient.parseToJsonElement(it) as? JsonArray }.getOrNull() }.orEmpty()

    /** An MCP server entry as it may go into or come out of a file: no query string, no header value, no auto-run. */
    private fun cleanServer(el: JsonElement): JsonObject? {
        val o = el as? JsonObject ?: return null
        fun str(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val id = str("id")?.takeIf { it.isNotBlank() } ?: return null
        val url = str("url")?.substringBefore('?')?.substringBefore('#')?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
            ?: return null
        return buildJsonObject {
            put("id", id)
            put("url", url)
            str("name")?.let { put("name", it) }
            str("headerName")?.let { put("headerName", it) }
            put("hasValue", false)
            put("autoRun", false)
            put("enabled", (o["enabled"] as? JsonPrimitive)?.booleanOrNull ?: true)
        }
    }

    private fun idOf(el: JsonElement): String? = ((el as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull

    private fun exportSettings(): JsonObject = buildJsonObject {
        for ((name, specs) in SETTINGS) {
            val prefs = app.getSharedPreferences(name, Context.MODE_PRIVATE)
            val entries = prefs.all.mapNotNull { (key, v) ->
                val spec = specs[key] ?: return@mapNotNull null
                spec.check(v)?.let { key to encodeValue(it) }
            }
            if (entries.isNotEmpty()) put(name, JsonObject(entries.toMap()))
        }
    }

    private fun encodeValue(v: Any): JsonObject = when (v) {
        is Boolean -> typed("b", JsonPrimitive(v))
        is Int -> typed("i", JsonPrimitive(v))
        is Float -> typed("f", JsonPrimitive(v))
        is Set<*> -> typed("ss", buildJsonArray { v.filterIsInstance<String>().sorted().forEach { add(JsonPrimitive(it)) } })
        else -> typed("s", JsonPrimitive(v.toString()))
    }

    private fun typed(t: String, v: JsonElement) = buildJsonObject { put("t", t); put("v", v) }

    /** Keeps only whitelisted keys whose value has the type (and range) that key is read with. */
    private fun parseSettings(obj: JsonObject?): Map<String, Map<String, Any>> {
        val out = LinkedHashMap<String, Map<String, Any>>()
        for ((name, specs) in SETTINGS) {
            val values = LinkedHashMap<String, Any>()
            (obj?.get(name) as? JsonObject)?.forEach { (key, el) ->
                val spec = specs[key] ?: return@forEach
                spec.check(decodeValue(el))?.let { values[key] = it }
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
            if (buf.size() > MAX_BYTES) throw BackupException(notBackup())
        }
        return buf.toString(Charsets.UTF_8.name())
    }

    /** How one settings key is stored; [check] returns the value to keep (clamped) or null to drop it. */
    private sealed interface Spec {
        fun check(v: Any?): Any?

        object Bool : Spec { override fun check(v: Any?) = v as? Boolean }
        object Text : Spec { override fun check(v: Any?) = (v as? String)?.takeIf { it.length <= 200 } }
        object TextSet : Spec { override fun check(v: Any?) = (v as? Set<*>)?.filterIsInstance<String>()?.toSet() }
        class Number(private val range: IntRange) : Spec { override fun check(v: Any?) = (v as? Int)?.coerceIn(range) }
        class Fraction(private val range: ClosedFloatingPointRange<Float>) : Spec {
            override fun check(v: Any?) = (v as? Float)?.takeIf { !it.isNaN() }?.coerceIn(range.start, range.endInclusive)
        }
    }

    companion object {
        const val FORMAT = "beka-backup"

        /** Bump when the file's shape changes; files of older versions must still import. */
        const val VERSION = 1
        private const val MAX_BYTES = 16 * 1024 * 1024
        private const val PROVIDERS_LIST = "list"
        private const val PROVIDERS_ACTIVE = "active"
        private const val PROVIDER_KEY_PREFIX = "key_"
        private const val MCP_SERVERS = "servers"
        private val pretty = Json { prettyPrint = true }
        private val providerJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        private val providerList = kotlinx.serialization.builtins.ListSerializer(ProviderConfig.serializer())

        /**
         * The only settings that go into a backup: prefs name to key to how the app reads it. Provider
         * and MCP lists travel separately (see [addProviders], [addServers]). Everything else stays out:
         * keys and header values (SecretBox), confirmation settings (a file must not switch them off),
         * onboarding, counters, overlay, debug, oauth, adb, virtual_screen. The same table filters import.
         */
        private val SETTINGS: Map<String, Map<String, Spec>> = mapOf(
            "providers" to mapOf(PROVIDERS_ACTIVE to Spec.Text, "no_vision" to Spec.TextSet),
            "web" to mapOf("enabled" to Spec.Bool, "provider" to Spec.Text),
            "appearance" to mapOf(
                "bubble_idle_alpha" to Spec.Fraction(AppearanceSettings.MIN_BUBBLE_IDLE_ALPHA..1f),
                "panel_alpha" to Spec.Fraction(AppearanceSettings.MIN_PANEL_ALPHA..1f),
            ),
            "memory" to mapOf("enabled" to Spec.Bool, "auto_extract" to Spec.Bool),
            "proactive" to mapOf("enabled" to Spec.Bool, "daily_calls" to Spec.Number(1..1000)),
            "voice" to mapOf(
                "speak_replies" to Spec.Bool,
                "voice" to Spec.Text,
                "rate" to Spec.Fraction(VoiceSettings.RATE_RANGE),
                "pitch" to Spec.Fraction(VoiceSettings.PITCH_RANGE),
            ),
            "update" to mapOf("channel" to Spec.Text, "auto_check" to Spec.Bool),
        )
    }
}
