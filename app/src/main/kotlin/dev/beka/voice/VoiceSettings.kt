package dev.beka.voice

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** How Beka talks back. */
class VoiceSettings(context: Context) {
    private val prefs = context.getSharedPreferences("voice", Context.MODE_PRIVATE)

    private val _speakReplies = MutableStateFlow(prefs.getBoolean(KEY_SPEAK_REPLIES, true))
    private val _voice = MutableStateFlow(prefs.getString(KEY_VOICE, null))
    private val _rate = MutableStateFlow(prefs.getFloat(KEY_RATE, 1f))
    private val _pitch = MutableStateFlow(prefs.getFloat(KEY_PITCH, 1f))

    /** Read the answer aloud when the question was spoken. */
    val speakReplies: StateFlow<Boolean> = _speakReplies

    /** Engine voice name; null = the engine's default voice for the app language. */
    val voice: StateFlow<String?> = _voice

    /** 1.0 = normal speed. */
    val rate: StateFlow<Float> = _rate

    /** 1.0 = normal pitch. */
    val pitch: StateFlow<Float> = _pitch

    fun setSpeakReplies(value: Boolean) {
        prefs.edit().putBoolean(KEY_SPEAK_REPLIES, value).apply()
        _speakReplies.value = value
    }

    fun setVoice(name: String?) {
        prefs.edit().putString(KEY_VOICE, name).apply()
        _voice.value = name
    }

    fun setRate(value: Float) {
        prefs.edit().putFloat(KEY_RATE, value).apply()
        _rate.value = value
    }

    fun setPitch(value: Float) {
        prefs.edit().putFloat(KEY_PITCH, value).apply()
        _pitch.value = value
    }

    /** Re-reads the stored values, after a backup was imported. */
    fun reload() {
        _speakReplies.value = prefs.getBoolean(KEY_SPEAK_REPLIES, true)
        _voice.value = prefs.getString(KEY_VOICE, null)
        _rate.value = prefs.getFloat(KEY_RATE, 1f)
        _pitch.value = prefs.getFloat(KEY_PITCH, 1f)
    }

    companion object {
        private const val KEY_SPEAK_REPLIES = "speak_replies"
        private const val KEY_VOICE = "voice"
        private const val KEY_RATE = "rate"
        private const val KEY_PITCH = "pitch"
        val RATE_RANGE = 0.6f..1.8f
        val PITCH_RANGE = 0.7f..1.4f
    }
}
