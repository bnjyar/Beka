package dev.beka.chat

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dev.beka.BekaApp
import dev.beka.R
import dev.beka.auth.OAuthException
import dev.beka.llm.LlmException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** A short message in the app language for a failed request, instead of raw HTTP bodies or exception text. */
fun friendlyError(e: Throwable): String {
    val causes = generateSequence(e) { it.cause }.take(6).toList()
    val status = causes.filterIsInstance<LlmException>().firstNotNullOfOrNull { it.status }
    return when {
        causes.any { it is OAuthException } -> BekaApp.instance.getString(R.string.err_oauth)
        // A local or LAN proxy answers 5xx when its upstream is unreachable; say what is really wrong.
        (status == null || status >= 500) && offline() -> BekaApp.instance.getString(R.string.err_offline)
        status == 401 || status == 403 -> BekaApp.instance.getString(R.string.err_bad_key)
        status == 402 -> BekaApp.instance.getString(R.string.err_no_credit)
        status == 429 -> BekaApp.instance.getString(R.string.err_rate_limit)
        status == 404 -> BekaApp.instance.getString(R.string.err_no_model)
        status != null && status >= 500 -> BekaApp.instance.getString(R.string.err_server)
        status != null && status in 400..499 -> BekaApp.instance.getString(R.string.err_rejected)
        causes.any { it is UnknownHostException || it is ConnectException || it is NoRouteToHostException } ->
            BekaApp.instance.getString(R.string.err_offline)
        causes.any { it is InterruptedIOException } -> BekaApp.instance.getString(R.string.err_timeout)
        causes.any { it is SocketException || it is SSLException } -> BekaApp.instance.getString(R.string.err_dropped)
        else -> {
            val line = (e.message ?: e.javaClass.simpleName).lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trim()
            BekaApp.instance.getString(R.string.err_other, if (line.length > 120) line.take(117) + "…" else line)
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
