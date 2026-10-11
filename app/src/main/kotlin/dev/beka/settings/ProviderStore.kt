package dev.beka.settings

import android.content.Context
import dev.beka.llm.LlmProvider
import dev.beka.llm.ProviderConfig
import dev.beka.llm.Providers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
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

    private fun load(): List<ProviderConfig> =
        prefs.getString(KEY_LIST, null)?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }
            ?: emptyList()

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

    /** Re-reads the stored values, after a backup was imported. */
    fun reload() {
        _providers.value = load()
        _activeId.value = prefs.getString(KEY_ACTIVE, null)
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
        const val KEY_LIST = "list"
        const val KEY_ACTIVE = "active"
        const val KEY_NO_VISION = "no_vision"
    }
}
