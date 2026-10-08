package dev.beka.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request

data class SseEvent(val event: String?, val data: String)

/** Incremental parser for text/event-stream, fed one line at a time. */
class SseParser {
    private var event: String? = null
    private val data = StringBuilder()
    private var hasData = false

    fun feed(line: String): SseEvent? {
        when {
            line.isEmpty() -> return flush()
            line.startsWith(":") -> Unit
            else -> {
                val field = line.substringBefore(':')
                val value = if (':' in line) line.substringAfter(':').removePrefix(" ") else ""
                when (field) {
                    "event" -> event = value
                    "data" -> {
                        if (hasData) data.append('\n')
                        data.append(value)
                        hasData = true
                    }
                }
            }
        }
        return null
    }

    fun finish(): SseEvent? = flush()

    private fun flush(): SseEvent? {
        val out = if (hasData) SseEvent(event, data.toString()) else null
        event = null
        data.clear()
        hasData = false
        return out
    }
}

internal fun OkHttpClient.sse(request: Request): Flow<SseEvent> = callbackFlow {
    val call = newCall(request)
    launch(Dispatchers.IO) {
        call.execute().use { response ->
            if (!response.isSuccessful) {
                throw LlmException(
                    "HTTP ${response.code}: ${response.body?.string().orEmpty().take(500)}",
                    response.code,
                )
            }
            val source = response.body!!.source()
            val parser = SseParser()
            while (true) {
                val line = source.readUtf8Line() ?: break
                parser.feed(line)?.let { send(it) }
            }
            parser.finish()?.let { send(it) }
        }
        close()
    }
    awaitClose { call.cancel() }
}
