package dev.beka.chat

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dev.beka.BekaApp
import dev.beka.auth.OAuthException
import dev.beka.llm.LlmException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** A short Indonesian message for a failed request, instead of raw HTTP bodies or exception text. */
fun friendlyError(e: Throwable): String {
    val causes = generateSequence(e) { it.cause }.take(6).toList()
    val status = causes.filterIsInstance<LlmException>().firstNotNullOfOrNull { it.status }
    return when {
        causes.any { it is OAuthException } -> "Login OpenRouter gagal. Coba login lagi."
        // A local or LAN proxy answers 5xx when its upstream is unreachable; say what is really wrong.
        (status == null || status >= 500) && offline() -> "Tidak ada koneksi internet."
        status == 401 || status == 403 -> "Kunci API salah atau kedaluwarsa. Cek di Setelan → Model AI."
        status == 402 -> "Saldo di provider habis. Untuk OpenRouter, isi di openrouter.ai/settings/credits."
        status == 429 -> "Terlalu banyak permintaan. Coba lagi sebentar."
        status == 404 -> "Model tidak ditemukan. Pilih model lain di Setelan → Model AI."
        status != null && status >= 500 -> "Server provider sedang bermasalah. Coba lagi nanti."
        status != null && status in 400..499 -> "Permintaan ditolak provider. Coba model lain atau mulai obrolan baru."
        causes.any { it is UnknownHostException || it is ConnectException || it is NoRouteToHostException } ->
            "Tidak ada koneksi internet."
        causes.any { it is InterruptedIOException } -> "Provider terlalu lama menjawab. Coba lagi."
        causes.any { it is SocketException || it is SSLException } -> "Koneksi ke provider terputus. Coba lagi."
        else -> {
            val line = (e.message ?: e.javaClass.simpleName).lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trim()
            "Ada error: " + if (line.length > 120) line.take(117) + "…" else line
        }
    }
}

/** True when the phone has no network that reaches the internet (Wi-Fi and mobile data both off, airplane mode). */
private fun offline(): Boolean = runCatching {
    val cm = BekaApp.instance.getSystemService(ConnectivityManager::class.java)
    val caps = cm.activeNetwork?.let(cm::getNetworkCapabilities)
    caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}.getOrDefault(false)

/** What is safe for logcat: the exception class and the status, never the message. */
fun errorSummary(e: Throwable): String =
    e.javaClass.simpleName + ((e as? LlmException)?.status?.let { " status=$it" } ?: "")
