package dev.beka.debug

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import dev.beka.BuildConfig
import dev.beka.accessibility.ScreenContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Writes a debug bundle to Download/beka-debug/<time>/ so it can be read from a shell. */
object DebugReporter {

    suspend fun write(context: Context, screen: ScreenContext?, note: String): String = withContext(Dispatchers.IO) {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val dir = "${Environment.DIRECTORY_DOWNLOADS}/beka-debug/$stamp"

        put(context, dir, "steps.jsonl", "application/x-ndjson", DebugLog.toJsonLines().toByteArray())
        put(context, dir, "note.txt", "text/plain", buildString {
            append("version: ").append(BuildConfig.VERSION_NAME).append('\n')
            append("note: ").append(note).append('\n')
        }.toByteArray())
        screen?.let { s ->
            put(context, dir, "screen.txt", "text/plain", s.toPrompt().toByteArray())
            s.screenshotJpeg?.let { put(context, dir, "screen.jpg", "image/jpeg", it) }
        }
        put(context, dir, "logcat.txt", "text/plain", logcat())
        dir
    }

    private fun put(context: Context, dir: String, name: String, mime: String, bytes: ByteArray) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, dir)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
        resolver.openOutputStream(uri)?.use { it.write(bytes) }
    }

    private fun logcat(): ByteArray = runCatching {
        val pid = android.os.Process.myPid().toString()
        Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", "2000", "--pid", pid))
            .inputStream.use { it.readBytes() }
    }.getOrElse { "logcat failed: $it".toByteArray() }
}
