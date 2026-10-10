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
        // Older reports are listed first, before this one's folder exists.
        if (!index(context).exists()) seed(context)

        put(context, dir, "steps.jsonl", "application/x-ndjson", DebugLog.toJsonLines().toByteArray())
        put(context, dir, "note.txt", "text/plain", buildString {
            append("version: ").append(BuildConfig.VERSION_NAME).append('\n')
            append("note: ").append(note).append('\n')
        }.toByteArray())
        screen?.let { s ->
            put(context, dir, "screen.txt", "text/plain", s.toPrompt().toByteArray())
            s.screenshotJpeg?.let { put(context, dir, "screen.jpg", "image/jpeg", it) }
        }
        (context.applicationContext as? dev.beka.BekaApp)?.actionLog?.recent()?.takeIf { it.isNotEmpty() }?.let {
            put(context, dir, "actions.jsonl", "application/x-ndjson", it.toByteArray())
        }
        put(context, dir, "logcat.txt", "text/plain", logcat())
        // While recording: everything since the recording started, not just what logcat still holds.
        if (AppLog.enabled.value) {
            AppLog.files().takeIf { it.isNotEmpty() }?.let { files ->
                put(context, dir, "applog.txt", "text/plain", files.fold(ByteArray(0)) { acc, f -> acc + f.readBytes() })
            }
            AppLog.files("steps").takeIf { it.isNotEmpty() }?.let { files ->
                put(context, dir, "steps-all.jsonl", "application/x-ndjson", files.fold(ByteArray(0)) { acc, f -> acc + f.readBytes() })
            }
        }
        remember(context, Report(stamp, note.ifBlank { "(tanpa catatan)" }, dir))
        dir
    }

    /** A report made earlier; the files stay in Download/beka-debug until the user deletes them. */
    data class Report(val stamp: String, val note: String, val dir: String)

    private fun index(context: Context) = java.io.File(context.filesDir, "reports.tsv")

    /** Every report made on this phone, newest first. */
    fun reports(context: Context): List<Report> = runCatching {
        if (!index(context).exists()) seed(context)
        index(context).readLines().mapNotNull { line ->
            line.split('\t').takeIf { it.size == 3 }?.let { Report(it[0], it[1], it[2]) }
        }.reversed()
    }.getOrDefault(emptyList())

    /** Reports from before the index existed: their folders in Download/beka-debug, oldest first. */
    private fun seed(context: Context) {
        val dirs = sortedSetOf<String>()
        runCatching {
            context.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns.RELATIVE_PATH),
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                arrayOf("${Environment.DIRECTORY_DOWNLOADS}/beka-debug/%"),
                null,
            )?.use { c -> while (c.moveToNext()) c.getString(0)?.trimEnd('/')?.let(dirs::add) }
        }
        index(context).writeText(dirs.joinToString("") { "${it.substringAfterLast('/')}\t(laporan lama)\t$it\n" })
    }

    private fun remember(context: Context, report: Report) {
        val clean = { s: String -> s.replace('\t', ' ').replace('\n', ' ') }
        index(context).appendText("${report.stamp}\t${clean(report.note)}\t${report.dir}\n")
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
