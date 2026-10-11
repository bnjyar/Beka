package dev.beka.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Push-to-talk speech input through the phone's own recognizer (Indonesian, live partial text).
 * Every call must come from the main thread, as SpeechRecognizer requires.
 */
class Listener(private val context: Context) {
    data class State(
        val listening: Boolean = false,
        /** What has been heard so far in this turn. */
        val partial: String = "",
        /** Input level 0..1, for the listening animation. */
        val level: Float = 0f,
        /** Why the last turn ended without text, in the user's language. */
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private var recognizer: SpeechRecognizer? = null
    private var onText: ((String) -> Unit)? = null
    /** A listen nobody asked for (a follow-up): silence just ends it, without a message. */
    private var quiet = false
    /** Set by [stop] and [cancel]: an ERROR_CLIENT after those is expected, not a broken recognizer. */
    private var deliberate = false
    private var startedAt = 0L
    private var peakRms = -100f

    fun hasPermission() =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun isAvailable() = SpeechRecognizer.isRecognitionAvailable(context)

    /**
     * Starts listening; [onResult] gets the final text once the user stops talking. A [quiet]
     * listen ends without an error message when nothing was said.
     */
    fun start(quiet: Boolean = false, onResult: (String) -> Unit) {
        cancel()
        if (!isAvailable()) {
            _state.value = State(error = "Pengenal suara tidak ada di HP ini. Pasang Google (Layanan Suara) atau app pengenal suara.")
            return
        }
        onText = onResult
        this.quiet = quiet
        deliberate = false
        startedAt = System.currentTimeMillis()
        peakRms = -100f
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        r.setRecognitionListener(callbacks)
        recognizer = r
        _state.value = State(listening = true)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, LANGUAGE)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, LANGUAGE)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        Log.i(TAG, "listen start")
        r.startListening(intent)
    }

    /** Stops recording; whatever was heard is still recognized and delivered. */
    fun stop() {
        deliberate = true
        recognizer?.stopListening()
    }

    /** Drops the current turn without delivering anything. */
    fun cancel() {
        deliberate = true
        onText = null
        recognizer?.let {
            it.cancel()
            it.destroy()
        }
        recognizer = null
        if (_state.value.listening) _state.value = State()
    }

    fun clearError() {
        if (_state.value.error != null) _state.value = _state.value.copy(error = null)
    }

    private fun finish(text: String?, error: String?) {
        val deliver = onText
        onText = null
        recognizer?.destroy()
        recognizer = null
        _state.value = State(error = error)
        if (!text.isNullOrBlank()) deliver?.invoke(text.trim())
    }

    private val callbacks = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            Log.i(TAG, "ready after ${System.currentTimeMillis() - startedAt} ms")
        }

        override fun onBeginningOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) {
            if (rmsdB > peakRms) peakRms = rmsdB
            // The recognizer reports roughly -2 (silence) to 10 dB.
            val level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            if (_state.value.listening) _state.value = _state.value.copy(level = level)
        }

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            Log.i(TAG, "end of speech, peak rms $peakRms")
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!text.isNullOrBlank() && _state.value.listening) _state.value = _state.value.copy(partial = text)
        }

        override fun onResults(results: Bundle?) {
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                ?.takeIf { it.isNotBlank() } ?: _state.value.partial.takeIf { it.isNotBlank() }
            Log.i(TAG, "result ${text?.length ?: 0} chars after ${System.currentTimeMillis() - startedAt} ms, peak rms $peakRms")
            finish(text, if (text == null && !quiet) "Nggak kedengeran, coba lagi" else null)
        }

        override fun onError(error: Int) {
            Log.w(TAG, "error $error after ${System.currentTimeMillis() - startedAt} ms, peak rms $peakRms")
            // Speech already shown as partial text is kept rather than lost to a late error.
            val heard = _state.value.partial.takeIf { it.isNotBlank() }
            if (heard != null && error in RECOVERABLE) finish(heard, null) else finish(null, message(error))
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun message(error: Int): String? = when (error) {
        SpeechRecognizer.ERROR_CLIENT ->
            if (deliberate) null else "Pengenal suara di HP ini tidak bisa dipakai. Cek Setelan HP → Pengenal suara (Google)."
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> if (quiet) null else "Nggak kedengeran, coba lagi"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Izin mikrofon belum ada"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER ->
            "Pengenal suara butuh internet"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
            "Bahasa Indonesia belum terpasang di pengenal suara"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Pengenal suara lagi dipakai app lain"
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS, SpeechRecognizer.ERROR_SERVER_DISCONNECTED ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "Pengenal suara sibuk, coba lagi sebentar" else "Pengenal suara error ($error)"
        SpeechRecognizer.ERROR_AUDIO -> "Mikrofon nggak bisa dipakai"
        else -> "Pengenal suara error ($error)"
    }

    companion object {
        private const val TAG = "BekaVoice"
        private const val LANGUAGE = "id-ID"
        private val RECOVERABLE = setOf(
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
            SpeechRecognizer.ERROR_SERVER,
        )
    }
}
