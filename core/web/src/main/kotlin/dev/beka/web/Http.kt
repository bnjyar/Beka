package dev.beka.web

import dev.beka.llm.tr
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A web request failed. [status] is the HTTP status when there was one. The message is meant for the model and the user. */
class WebException(message: String, val status: Int? = null) : Exception(message)

internal class HttpReply(val status: Int, val body: ByteArray, val contentType: MediaType?, val location: String?) {
    fun text(): String = String(body, contentType?.charset() ?: Charsets.UTF_8)
}

/** Runs [request] without blocking a thread; reads at most [maxBytes] of the body (the rest is dropped). */
internal suspend fun OkHttpClient.send(request: Request, maxBytes: Int = 2_000_000): HttpReply =
    suspendCancellableCoroutine { cont ->
        val call = newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(WebException(tr("Cannot connect: ", "Tidak bisa terhubung: ") + (e.message ?: e.javaClass.simpleName)))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        val body = it.body
                        val out = java.io.ByteArrayOutputStream()
                        val chunk = ByteArray(8192)
                        val stream = body?.byteStream() ?: java.io.ByteArrayInputStream(ByteArray(0))
                        while (out.size() < maxBytes) {
                            val n = stream.read(chunk, 0, minOf(chunk.size, maxBytes - out.size()))
                            if (n < 0) break
                            out.write(chunk, 0, n)
                        }
                        val reply = HttpReply(it.code, out.toByteArray(), body?.contentType(), it.header("Location"))
                        if (cont.isActive) cont.resume(reply)
                    } catch (e: IOException) {
                        if (cont.isActive) cont.resumeWithException(WebException(tr("Could not read the response: ${e.message}", "Gagal membaca respons: ${e.message}")))
                    }
                }
            }
        })
    }

internal fun HttpReply.failure(): WebException =
    WebException("HTTP $status: ${text().replace(Regex("\\s+"), " ").take(200)}", status)
