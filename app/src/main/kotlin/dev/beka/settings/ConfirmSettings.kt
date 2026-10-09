package dev.beka.settings

import android.content.Context
import dev.beka.agent.Blocklist
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class AppConfirm { ASK, SKIP }

/**
 * Whether Beka asks before risky actions in chats the user starts. Asking is the default; this can only
 * be changed from Setelan. A per-app choice wins over the global one. Never applies to rule runs or to
 * blocked apps.
 */
class ConfirmSettings(context: Context) {
    private val prefs = context.getSharedPreferences("confirm", Context.MODE_PRIVATE)

    private val _askGlobal = MutableStateFlow(prefs.getBoolean(KEY_ASK, true))
    private val _overrides = MutableStateFlow(decode(prefs.getString(KEY_OVERRIDES, null)))

    /** True (default) = ask before risky actions. */
    val askGlobal: StateFlow<Boolean> = _askGlobal

    /** Apps that differ from the global choice; an app that is absent follows it. */
    val overrides: StateFlow<Map<String, AppConfirm>> = _overrides

    fun setAskGlobal(value: Boolean) {
        prefs.edit().putBoolean(KEY_ASK, value).apply()
        _askGlobal.value = value
    }

    /** Null removes the override. */
    fun setOverride(pkg: String, choice: AppConfirm?) {
        if (Blocklist.isBlocked(pkg)) return
        val next = _overrides.value.toMutableMap()
        if (choice == null) next.remove(pkg) else next[pkg] = choice
        prefs.edit().putString(KEY_OVERRIDES, encode(next)).apply()
        _overrides.value = next
    }

    /** [pkg] is the app the action happens in, null for web and phone tools. */
    fun shouldSkip(pkg: String?): Boolean {
        if (Blocklist.isBlocked(pkg)) return false
        val own = pkg?.let { _overrides.value[it] }
        return if (own != null) own == AppConfirm.SKIP else !_askGlobal.value
    }

    private fun encode(map: Map<String, AppConfirm>) = map.entries.joinToString(";") { "${it.key}=${it.value.name}" }

    private fun decode(raw: String?): Map<String, AppConfirm> =
        raw.orEmpty().split(';').mapNotNull { part ->
            val (pkg, name) = part.split('=', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
            val choice = AppConfirm.values().firstOrNull { it.name == name } ?: return@mapNotNull null
            pkg.takeIf { it.isNotBlank() }?.let { it to choice }
        }.toMap()

    private companion object {
        const val KEY_ASK = "ask_global"
        const val KEY_OVERRIDES = "overrides"
    }
}
