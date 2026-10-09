package dev.beka.settings

import android.content.Context
import dev.beka.web.BraveSearch
import dev.beka.web.DuckDuckGoSearch
import dev.beka.web.PageFetcher
import dev.beka.web.SearchProvider
import dev.beka.web.TavilySearch
import dev.beka.web.TinyFishSearch
import dev.beka.web.WebSearch
import dev.beka.web.WebTools
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient

/** Web search and page reading for the agent. Keys are encrypted with [SecretBox]. */
class WebSettings(context: Context) {
    private val prefs = context.getSharedPreferences("web", Context.MODE_PRIVATE)

    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, true))
    private val _provider = MutableStateFlow(prefs.getString(KEY_PROVIDER, null)?.takeIf { it in PROVIDERS } ?: TINYFISH)
    private val _keys = MutableStateFlow(PROVIDERS.filter { hasKey(it) }.toSet())

    val enabled: StateFlow<Boolean> = _enabled

    /** One of [PROVIDERS]. */
    val provider: StateFlow<String> = _provider

    /** Providers that have a key saved (so the screen can show "tersimpan"). */
    val providersWithKey: StateFlow<Set<String>> = _keys

    fun setEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, value).apply()
        _enabled.value = value
    }

    fun setProvider(id: String) {
        if (id !in PROVIDERS) return
        prefs.edit().putString(KEY_PROVIDER, id).apply()
        _provider.value = id
    }

    fun hasKey(id: String): Boolean = prefs.contains(keyName(id))

    fun apiKey(id: String): String? = prefs.getString(keyName(id), null)?.let(SecretBox::decrypt)?.takeIf { it.isNotBlank() }

    /** Blank removes the key. */
    fun setApiKey(id: String, key: String) {
        val edit = prefs.edit()
        if (key.isBlank()) edit.remove(keyName(id)) else edit.putString(keyName(id), SecretBox.encrypt(key.trim()))
        edit.apply()
        _keys.value = PROVIDERS.filter { hasKey(it) }.toSet()
    }

    /** The search set up as chosen; DuckDuckGo stands in when the chosen provider has no key or fails. */
    fun search(http: OkHttpClient): WebSearch {
        val primary: SearchProvider? = when (val id = _provider.value) {
            TINYFISH -> apiKey(id)?.let { TinyFishSearch(http, it) }
            BRAVE -> apiKey(id)?.let { BraveSearch(http, it) }
            TAVILY -> apiKey(id)?.let { TavilySearch(http, it) }
            else -> null
        }
        return WebSearch(primary, DuckDuckGoSearch(http))
    }

    fun tools(http: OkHttpClient): WebTools = WebTools(search(http), PageFetcher(http, tinyFishKey = apiKey(TINYFISH)))

    companion object {
        const val TINYFISH = "tinyfish"
        const val BRAVE = "brave"
        const val TAVILY = "tavily"
        const val DUCKDUCKGO = "duckduckgo"
        val PROVIDERS = listOf(TINYFISH, BRAVE, TAVILY, DUCKDUCKGO)
        private const val KEY_ENABLED = "enabled"
        private const val KEY_PROVIDER = "provider"
        private fun keyName(id: String) = "key_$id"
    }
}
