package dev.beka.settings

import android.content.Context
import android.util.Log
import dev.beka.llm.LlmProvider
import dev.beka.llm.ProviderConfig
import dev.beka.llm.Providers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import okhttp3.OkHttpClient

/** Provider configs in plain prefs; API keys encrypted separately via [SecretBox]. */
class ProviderStore(context: Context, private val http: OkHttpClient) {
    private val prefs = context.getSharedPreferences("providers", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(ProviderConfig.serializer())

    private val _providers = MutableStateFlow(load())
    val providers: StateFlow<List<ProviderConfig>> = _providers

    private val _activeId = MutableStateFlow(prefs.getString(KEY_ACTIVE, null))
    val activeId: StateFlow<String?> = _activeId

    /**
     * Parses entry by entry so one bad entry (an unknown kind from a newer build) is skipped, not the
     * whole list. If the JSON is unreadable altogether it is copied to [KEY_UNREADABLE] first, so a
     * later [save] cannot destroy it.
     */
    private fun load(): List<ProviderConfig> {
        val raw = prefs.getString(KEY_LIST, null) ?: return emptyList()
        val entries = runCatching { json.parseToJsonElement(raw).jsonArray }.getOrElse {
            prefs.edit().putString(KEY_UNREADABLE, raw).apply()
            Log.w(TAG, "provider list unreadable; raw copy kept")
            return emptyList()
        }
        var skipped = 0
        val list = entries.mapNotNull { el ->
            runCatching { json.decodeFromJsonElement(ProviderConfig.serializer(), el) }.getOrNull().also { if (it == null) skipped++ }
        }
        if (skipped > 0) {
            // Keep the original so the skipped entries are not lost for good on the next save.
            prefs.edit().putString(KEY_UNREADABLE, raw).apply()
            Log.w(TAG, "skipped $skipped unreadable provider entries; raw copy kept")
        }
        return list
    }

    fun save(config: ProviderConfig, apiKey: String?) {
        val list = _providers.value.filterNot { it.id == config.id } + config
        prefs.edit()
            .putString(KEY_LIST, json.encodeToString(serializer, list))
            .apply { if (apiKey != null) putString(secretKey(config.id), SecretBox.encrypt(apiKey)) }
            .apply()
        _providers.value = list
        if (_activeId.value == null) setActive(config.id)
    }

    fun delete(id: String) {
        val list = _providers.value.filterNot { it.id == id }
        prefs.edit()
            .putString(KEY_LIST, json.encodeToString(serializer, list))
            .remove(secretKey(id))
            .apply()
        _providers.value = list
        if (_activeId.value == id) setActive(list.firstOrNull()?.id)
    }

    fun setActive(id: String?) {
        prefs.edit().putString(KEY_ACTIVE, id).apply()
        _activeId.value = id
    }

    fun hasKey(id: String): Boolean = prefs.contains(secretKey(id))

    fun apiKey(id: String): String? = prefs.getString(secretKey(id), null)?.let(SecretBox::decrypt)

    /** Models that refused an image once; screenshots are not sent to them again. */
    fun rejectsImages(config: ProviderConfig): Boolean =
        visionKey(config) in prefs.getStringSet(KEY_NO_VISION, emptySet()).orEmpty()

    fun markRejectsImages(config: ProviderConfig) {
        val set = prefs.getStringSet(KEY_NO_VISION, emptySet()).orEmpty() + visionKey(config)
        prefs.edit().putStringSet(KEY_NO_VISION, set).apply()
    }

    private fun visionKey(config: ProviderConfig) = "${config.id}|${config.model}"

    fun active(): ProviderConfig? = _providers.value.firstOrNull { it.id == _activeId.value }

    fun client(config: ProviderConfig, apiKeyOverride: String? = null): LlmProvider =
        Providers.create(config, apiKeyOverride ?: apiKey(config.id), http)

    private fun secretKey(id: String) = "key_$id"

    private companion object {
        const val TAG = "BekaChat"
        const val KEY_LIST = "list"
        const val KEY_UNREADABLE = "list_unreadable"
        const val KEY_ACTIVE = "active"
        const val KEY_NO_VISION = "no_vision"
    }
}
