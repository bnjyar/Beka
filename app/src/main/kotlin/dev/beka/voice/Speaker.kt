package dev.beka.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import dev.beka.R
import dev.beka.llm.Lang
import dev.beka.settings.AppLanguage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Reads replies aloud through the phone's text-to-speech engine, in the app language, with the voice,
 * speed and pitch from [VoiceSettings]. Other audio is ducked while it talks. The engine starts
 * on first use.
 */
class Speaker(private val context: Context, private val settings: VoiceSettings) {
    /** A voice the engine offers for the app language. */
    data class VoiceOption(val name: String, val label: String, val online: Boolean)

    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking

    private val _voices = MutableStateFlow<List<VoiceOption>>(emptyList())
    /** Filled once the engine has started (see [warmUp]). */
    val voices: StateFlow<List<VoiceOption>> = _voices

    private val _problem = MutableStateFlow<String?>(null)
    /** Why speech is unavailable (no engine, no voice for the app language), in the user's language; null when fine. */
    val problem: StateFlow<String?> = _problem

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
    /** Text (and voice) asked for before the engine finished starting. */
    private var waiting: Pair<String, String?>? = null
    private var lastId: String? = null
    /** Runs once the whole text has been read, not when it is cut off. */
    private var onDone: (() -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())
    private var counter = 0
    /** The language [voices] was listed for. */
    private var voicesLang: Lang? = null

    fun speak(reply: String, onDone: (() -> Unit)? = null) = speak(reply, settings.voice.value, onDone)

    /** Reads [sample] with [voice] (null = engine default) without changing the setting. */
    fun preview(voice: String?, sample: String = context.getString(R.string.voice_sample)) = speak(sample, voice, null)

    /** Starts the engine so [voices] gets filled; after a language change, lists that language's voices. */
    fun warmUp() {
        val engine = tts
        if (engine == null) start()
        else if (ready && voicesLang != Lang.current) {
            applyLanguage(engine)
            _voices.value = languageVoices(engine)
        }
    }

    private fun speak(reply: String, voice: String?, onDone: (() -> Unit)?) {
        val text = SpokenText.of(reply)
        this.onDone = null
        if (text.isBlank()) {
            onDone?.invoke()
            return
        }
        this.onDone = onDone
        val engine = tts
        if (engine == null || !ready) {
            waiting = text to voice
            if (engine == null) start()
            return
        }
        say(engine, text, voice)
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
        var created: TextToSpeech? = null
        // The engine may report back inside the constructor, before `created` is assigned.
        var early: Int? = null
        created = try {
            TextToSpeech(context) { status ->
                val engine = created
                if (engine == null) early = status else initialised(engine, status, started)
            }
        } catch (e: Exception) {
            Log.w(TAG, "tts constructor failed: ${e.javaClass.simpleName}")
            null
        }
        val engine = created
        if (engine == null) {
            _problem.value = context.getString(R.string.tts_no_engine)
            waiting = null
            return
        }
        tts = engine
        early?.let { initialised(engine, it, started) }
    }

    private fun initialised(engine: TextToSpeech, status: Int, started: Long) {
        if (status != TextToSpeech.SUCCESS) {
            Log.w(TAG, "tts init failed ($status)")
            // The engine may be missing altogether or just broken; either way the next speak retries.
            val none = runCatching { engine.engines.isEmpty() }.getOrDefault(false)
            _problem.value = context.getString(if (none) R.string.tts_no_engine else R.string.tts_engine_failed)
            runCatching { engine.shutdown() }
            tts = null
            ready = false
            waiting = null
            onDone = null
            return
        }
        val lang = applyLanguage(engine)
        Log.i(TAG, "tts ready in ${System.currentTimeMillis() - started} ms, engine ${engine.defaultEngine}, ${Lang.current.tag} result $lang")
        engine.setAudioAttributes(attributes)
        engine.setOnUtteranceProgressListener(progress)
        _voices.value = languageVoices(engine)
        Log.i(TAG, "${Lang.current.tag} voices: ${_voices.value.joinToString { it.name }}")
        ready = true
        waiting?.let { (text, voice) ->
            waiting = null
            say(engine, text, voice)
        }
    }

    /** Sets the app language and records whether the engine can actually speak it. */
    private fun applyLanguage(engine: TextToSpeech): Int {
        val result = engine.setLanguage(AppLanguage.speechLocale())
        _problem.value = if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            context.getString(R.string.tts_language_missing)
        } else {
            null
        }
        return result
    }

    private fun speaksAppLanguage(voice: Voice): Boolean = when (Lang.current) {
        Lang.ID -> voice.locale.language in INDONESIAN
        Lang.EN -> voice.locale.language in ENGLISH
    }

    private fun languageVoices(engine: TextToSpeech): List<VoiceOption> =
        runCatching { engine.voices.orEmpty() }.getOrDefault(emptySet())
            .also { voicesLang = Lang.current }
            .filter { speaksAppLanguage(it) }
            // English comes in many accents; list the phone's own (US when unknown).
            .let { all -> all.filter { it.locale.country == AppLanguage.speechLocale().country }.ifEmpty { all } }
            .filterNot { TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED in it.features.orEmpty() }
            .let { all -> all.filter { "-x-" in it.name }.ifEmpty { all } }
            .sortedWith(compareBy({ code(it.name) }, { it.isNetworkConnectionRequired }))
            .let(::labelled)

    /** "id-id-x-dfz-network" → "dfz": one speaker, offered offline and online. */
    private fun code(name: String) = name.substringAfter("-x-", name).substringBefore('-')

    private fun labelled(voices: List<Voice>): List<VoiceOption> {
        val codes = voices.map { code(it.name) }.distinct()
        return voices.map {
            val n = codes.indexOf(code(it.name)) + 1
            VoiceOption(it.name, context.getString(R.string.voice_n, n), it.isNetworkConnectionRequired)
        }
    }

    private fun say(engine: TextToSpeech, text: String, voiceName: String?) {
        engine.stop()
        // A voice picked for the other language would read this one with the wrong accent.
        val voice = voiceName?.let { name -> runCatching { engine.voices }.getOrNull()?.firstOrNull { it.name == name } }
            ?.takeIf(::speaksAppLanguage)
        if (voice != null) engine.voice = voice else applyLanguage(engine)
        engine.setSpeechRate(settings.rate.value)
        engine.setPitch(settings.pitch.value)
        audio.requestAudioFocus(focus)
        _speaking.value = true
        val pieces = SpokenText.chunks(text, TextToSpeech.getMaxSpeechInputLength() - 1)
        pieces.forEachIndexed { i, piece ->
            val id = "beka-${++counter}"
            if (i == pieces.lastIndex) lastId = id
            engine.speak(piece, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, id)
        }
        Log.i(TAG, "speaking ${text.length} chars in ${pieces.size} piece(s), voice ${engine.voice?.name}")
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

    companion object {
        private const val TAG = "BekaVoice"
        /** Android may still report Indonesian under its legacy code "in". */
        private val INDONESIAN = setOf("id", "in", "ind")
        private val ENGLISH = setOf("en", "eng")
    }
}
