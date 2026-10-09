package dev.beka.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/**
 * Reads replies aloud through the phone's text-to-speech engine, in Indonesian. Other audio is
 * ducked while it talks. The engine starts on first use.
 */
class Speaker(private val context: Context) {
    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking

    private val audio = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener { }
        .build()

    private var tts: TextToSpeech? = null
    private var ready = false
    /** Text asked for before the engine finished starting. */
    private var waiting: String? = null
    private var lastId: String? = null
    /** Runs once the whole text has been read, not when it is cut off. */
    private var onDone: (() -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())
    private var counter = 0

    fun speak(reply: String, onDone: (() -> Unit)? = null) {
        val text = SpokenText.of(reply)
        this.onDone = null
        if (text.isBlank()) {
            onDone?.invoke()
            return
        }
        this.onDone = onDone
        val engine = tts
        if (engine == null || !ready) {
            waiting = text
            if (engine == null) start()
            return
        }
        say(engine, text)
    }

    fun stop() {
        onDone = null
        waiting = null
        lastId = null
        tts?.stop()
        done()
    }

    private fun start() {
        val started = System.currentTimeMillis()
        tts = TextToSpeech(context) { status ->
            val engine = tts ?: return@TextToSpeech
            if (status != TextToSpeech.SUCCESS) {
                Log.w(TAG, "tts init failed ($status)")
                tts = null
                waiting = null
                return@TextToSpeech
            }
            val lang = engine.setLanguage(Locale.forLanguageTag("id-ID"))
            Log.i(TAG, "tts ready in ${System.currentTimeMillis() - started} ms, engine ${engine.defaultEngine}, id-ID result $lang")
            engine.setAudioAttributes(attributes)
            engine.setOnUtteranceProgressListener(progress)
            ready = true
            waiting?.let {
                waiting = null
                say(engine, it)
            }
        }
    }

    private fun say(engine: TextToSpeech, text: String) {
        engine.stop()
        audio.requestAudioFocus(focus)
        _speaking.value = true
        val pieces = SpokenText.chunks(text, TextToSpeech.getMaxSpeechInputLength() - 1)
        pieces.forEachIndexed { i, piece ->
            val id = "beka-${++counter}"
            if (i == pieces.lastIndex) lastId = id
            engine.speak(piece, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, id)
        }
        Log.i(TAG, "speaking ${text.length} chars in ${pieces.size} piece(s)")
    }

    private fun done() {
        if (!_speaking.value) return
        _speaking.value = false
        audio.abandonAudioFocusRequest(focus)
    }

    private val progress = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit

        override fun onDone(utteranceId: String?) {
            if (utteranceId != lastId) return
            done()
            val next = onDone ?: return
            onDone = null
            main.post(next)
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            Log.w(TAG, "tts error on $utteranceId")
            done()
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            Log.w(TAG, "tts error $errorCode on $utteranceId")
            done()
        }

        override fun onStop(utteranceId: String?, interrupted: Boolean) {
            if (utteranceId == lastId || lastId == null) done()
        }
    }

    private companion object {
        const val TAG = "BekaVoice"
    }
}
