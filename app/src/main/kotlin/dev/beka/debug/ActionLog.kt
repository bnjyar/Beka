package dev.beka.debug

import android.content.Context
import java.io.File

/** Every agent action, as JSON lines in app storage, kept for [RETENTION_MS]. Never leaves the phone. */
class ActionLog(context: Context) {
    private val file = File(context.filesDir, "actions.jsonl")

    @Synchronized
    fun append(time: Long, jsonLine: String) {
        file.appendText("$time\t$jsonLine\n")
    }

    /** Drops entries older than the retention window. Call once at startup. */
    @Synchronized
    fun prune(now: Long = System.currentTimeMillis()) {
        if (!file.exists()) return
        val keep = file.readLines().filter { line ->
            val time = line.substringBefore('\t').toLongOrNull() ?: return@filter false
            now - time < RETENTION_MS
        }
        file.writeText(keep.joinToString("") { "$it\n" })
    }

    @Synchronized
    fun recent(limit: Int = 300): String =
        if (!file.exists()) "" else file.readLines().takeLast(limit).joinToString("\n") { it.substringAfter('\t') }

    private companion object {
        const val RETENTION_MS = 7L * 24 * 60 * 60 * 1000
    }
}
