package dev.beka.voice

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** How Beka talks back. */
class VoiceSettings(context: Context) {
    private val prefs = context.getSharedPreferences("voice", Context.MODE_PRIVATE)

    private val _speakReplies = MutableStateFlow(prefs.getBoolean(KEY_SPEAK_REPLIES, true))

    /** Read the answer aloud when the question was spoken. */
    val speakReplies: StateFlow<Boolean> = _speakReplies

    fun setSpeakReplies(value: Boolean) {
        prefs.edit().putBoolean(KEY_SPEAK_REPLIES, value).apply()
        _speakReplies.value = value
    }

    private companion object {
        const val KEY_SPEAK_REPLIES = "speak_replies"
    }
}
