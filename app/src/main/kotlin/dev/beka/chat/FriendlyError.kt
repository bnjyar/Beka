package dev.beka.chat

import dev.beka.llm.LlmException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException

/** A short Indonesian message for a failed request, instead of raw HTTP bodies or exception text. */
fun friendlyError(e: Throwable): String {
    val causes = generateSequence(e) { it.cause }.take(6).toList()
    val status = causes.filterIsInstance<LlmException>().firstNotNullOfOrNull { it.status }
    return when {
        status == 401 || status == 403 -> "Kunci API salah atau kedaluwarsa. Cek di Setelan → Model AI."
        status == 402 -> "Saldo di provider habis. Untuk OpenRouter, isi di openrouter.ai/settings/credits."
        status == 429 -> "Terlalu banyak permintaan. Coba lagi sebentar."
        status == 404 -> "Model tidak ditemukan. Pilih model lain di Setelan → Model AI."
        status != null && status >= 500 -> "Server provider sedang bermasalah. Coba lagi nanti."
        causes.any { it is UnknownHostException || it is ConnectException || it is NoRouteToHostException } ->
            "Tidak ada koneksi internet."
        causes.any { it is InterruptedIOException } -> "Provider terlalu lama menjawab. Coba lagi."
        else -> {
            val line = (e.message ?: e.javaClass.simpleName).lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trim()
            "Ada error: " + if (line.length > 120) line.take(117) + "…" else line
        }
    }
}

/** What is safe for logcat: the exception class and the status, never the message. */
fun errorSummary(e: Throwable): String =
    e.javaClass.simpleName + ((e as? LlmException)?.status?.let { " status=$it" } ?: "")
