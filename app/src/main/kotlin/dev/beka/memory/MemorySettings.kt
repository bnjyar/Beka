package dev.beka.memory

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class MemorySettings(context: Context) {
    private val prefs = context.getSharedPreferences("memory", Context.MODE_PRIVATE)

    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, true))

    /** Master switch: off means Beka neither reads nor writes memory. */
    val enabled: StateFlow<Boolean> = _enabled

    private val _autoExtract = MutableStateFlow(prefs.getBoolean(KEY_AUTO_EXTRACT, true))

    /** Whether the end-of-session extraction pass runs. */
    val autoExtract: StateFlow<Boolean> = _autoExtract

    fun setEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, value).apply()
        _enabled.value = value
    }

    fun setAutoExtract(value: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_EXTRACT, value).apply()
        _autoExtract.value = value
    }

    private companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_AUTO_EXTRACT = "auto_extract"
    }
}
