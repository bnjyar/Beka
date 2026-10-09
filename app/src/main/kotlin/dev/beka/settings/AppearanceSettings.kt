package dev.beka.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** How the overlay looks, plus whether first-run setup has been seen. */
class AppearanceSettings(context: Context) {
    private val prefs = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)

    private val _bubbleIdleAlpha = MutableStateFlow(prefs.getFloat(KEY_BUBBLE_IDLE_ALPHA, DEFAULT_BUBBLE_IDLE_ALPHA))

    /** The bubble's opacity once it has been left alone for a few seconds (1 = never fades). */
    val bubbleIdleAlpha: StateFlow<Float> = _bubbleIdleAlpha

    private val _panelAlpha = MutableStateFlow(prefs.getFloat(KEY_PANEL_ALPHA, 1f))

    /** The chat panel's background opacity; text stays fully opaque. */
    val panelAlpha: StateFlow<Float> = _panelAlpha

    private val _onboarded = MutableStateFlow(prefs.getBoolean(KEY_ONBOARDED, false))

    /** True once first-run setup was finished or skipped. */
    val onboarded: StateFlow<Boolean> = _onboarded

    fun setBubbleIdleAlpha(value: Float) {
        val v = value.coerceIn(MIN_BUBBLE_IDLE_ALPHA, 1f)
        prefs.edit().putFloat(KEY_BUBBLE_IDLE_ALPHA, v).apply()
        _bubbleIdleAlpha.value = v
    }

    fun setPanelAlpha(value: Float) {
        val v = value.coerceIn(MIN_PANEL_ALPHA, 1f)
        prefs.edit().putFloat(KEY_PANEL_ALPHA, v).apply()
        _panelAlpha.value = v
    }

    fun setOnboarded(value: Boolean) {
        prefs.edit().putBoolean(KEY_ONBOARDED, value).apply()
        _onboarded.value = value
    }

    companion object {
        const val DEFAULT_BUBBLE_IDLE_ALPHA = 0.4f
        const val MIN_BUBBLE_IDLE_ALPHA = 0.15f
        /** Below this, text over a busy app stops being readable. */
        const val MIN_PANEL_ALPHA = 0.7f
        private const val KEY_BUBBLE_IDLE_ALPHA = "bubble_idle_alpha"
        private const val KEY_PANEL_ALPHA = "panel_alpha"
        private const val KEY_ONBOARDED = "onboarded"
    }
}
