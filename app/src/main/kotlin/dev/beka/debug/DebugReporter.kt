package dev.beka.debug

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import dev.beka.BuildConfig
import dev.beka.accessibility.ScreenContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Writes a debug bundle to Download/beka-debug/<time>/ (and as one zip beside it) so it can be read from a shell or attached to an issue. */
object DebugReporter {

    /** What the user chose to put in the bundle. */
    data class Include(
        val screenText: Boolean = false,
        val screenshot: Boolean = false,
        val logs: Boolean = true,
        val steps: Boolean = true,
    )

    /** A saved bundle: its folder, the zip next to it, and the stamp both are named by. */
    data class Written(val stamp: String, val dir: String, val zip: String)

    suspend fun write(
        context: Context,
        screen: ScreenContext?,
        happened: String,
        expected: String,
        include: Include,
    ): Written = withContext(Dispatchers.IO) {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val root = "${Environment.DIRECTORY_DOWNLOADS}/beka-debug"
        val dir = "$root/$stamp"
        // Older reports are listed first, before this one's folder exists.
        if (!index(context).exists()) seed(context)

        // Name -> (mime, bytes); written to the folder and into the zip alike.
        val files = linkedMapOf<String, Pair<String, ByteArray>>()
        files["note.txt"] = "text/plain" to buildString {
            append("version: ").append(BuildConfig.VERSION_NAME).append(" (").append(BuildConfig.VERSION_CODE).append(")\n")
            append("device: ").append(device()).append('\n')
            append("android: ").append(androidVersion()).append('\n')
            append("\nApa yang terjadi?\n").append(happened.trim()).append('\n')
            append("\nSeharusnya bagaimana?\n").append(expected.trim().ifEmpty { "(tidak diisi)" }).append('\n')
        }.toByteArray()
        if (include.steps) {
            files["steps.jsonl"] = "application/x-ndjson" to DebugLog.toJsonLines().toByteArray()
            (context.applicationContext as? dev.beka.BekaApp)?.actionLog?.recent()?.takeIf { it.isNotEmpty() }?.let {
                files["actions.jsonl"] = "application/x-ndjson" to it.toByteArray()
            }
        }
        if (include.screenText) {
            screen?.let { files["screen.txt"] = "text/plain" to it.toPrompt().toByteArray() }
        }
        if (include.screenshot) {
            screen?.screenshotJpeg?.let { files["screen.jpg"] = "image/jpeg" to it }
        }
        if (include.logs) {
            files["logcat.txt"] = "text/plain" to logcat()
            // While recording: everything since the recording started, not just what logcat still holds.
            if (AppLog.enabled.value) {
                runCatching { AppLog.collect() }
                AppLog.files().takeIf { it.isNotEmpty() }?.let { fs ->
                    files["applog.txt"] = "text/plain" to fs.fold(ByteArray(0)) { acc, f -> acc + f.readBytes() }
                }
                AppLog.files("steps").takeIf { it.isNotEmpty() }?.let { fs ->
                    files["steps-all.jsonl"] = "application/x-ndjson" to fs.fold(ByteArray(0)) { acc, f -> acc + f.readBytes() }
                }
            }
        }

        files.forEach { (name, f) -> put(context, dir, name, f.first, f.second) }
        val zipName = "beka-report-$stamp.zip"
        val zipped = java.io.ByteArrayOutputStream().use { out ->
            java.util.zip.ZipOutputStream(out).use { zip ->
                files.forEach { (name, f) ->
                    zip.putNextEntry(java.util.zip.ZipEntry(name))
                    zip.write(f.second)
                    zip.closeEntry()
                }
            }
            out.toByteArray()
        }
        put(context, root, zipName, "application/zip", zipped)

        val note = happened.trim().lineSequence().firstOrNull().orEmpty()
        remember(context, Report(stamp, note.ifBlank { "(tanpa catatan)" }, dir))
        Written(stamp, dir, "$root/$zipName")
    }

    private fun device() = "${Build.MANUFACTURER} ${Build.MODEL}"

    private fun androidVersion() = "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"

    /** A prefilled new-issue page; long texts are cut until the URL fits what GitHub accepts. */
    fun issueUrl(happened: String, expected: String, stamp: String): String {
        val title = happened.trim().lineSequence().firstOrNull().orEmpty().trim().take(80)
        var limit = 2_000
        while (true) {
            val url = buildUrl(title, issueBody(happened, expected, stamp, limit))
            if (url.length <= MAX_URL || limit <= 100) return url
            limit /= 2
        }
    }

    private fun issueBody(happened: String, expected: String, stamp: String, limit: Int): String {
        val cut = { s: String -> s.trim().let { if (it.length > limit) it.take(limit) + "…" else it } }
        return buildString {
            append("### Apa yang terjadi?\n").append(cut(happened)).append("\n\n")
            append("### Seharusnya bagaimana?\n").append(expected.takeIf { it.isNotBlank() }?.let(cut) ?: "_(tidak diisi)_").append("\n\n")
            append("### Perangkat\n")
            append("- Beka: ").append(BuildConfig.VERSION_NAME).append(" (").append(BuildConfig.VERSION_CODE).append(")\n")
            append("- Perangkat: ").append(device()).append('\n')
            append("- Android: ").append(androidVersion()).append("\n\n")
            append("### Lampiran\n")
            append("Seret berkas `Download/beka-debug/beka-report-").append(stamp).append(".zip` ke kolom ini ")
            append("(GitHub tidak bisa menerima lampiran lewat tautan). ")
            append("Periksa isinya dulu: bisa memuat teks obrolan, isi layar, nama aplikasi, dan teks notifikasi.\n")
        }
    }

    private fun buildUrl(title: String, body: String): String {
        val enc = { s: String -> URLEncoder.encode(s, "UTF-8").replace("+", "%20") }
        return "https://github.com/${BuildConfig.REPO}/issues/new?labels=bug&title=${enc(title)}&body=${enc(body)}"
    }

    private const val MAX_URL = 7_000

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
            )?.use { c -> while (c.moveToNext()) c.getString(0)?.trimEnd('/')?.takeIf { it.substringAfterLast('/') != "beka-debug" }?.let(dirs::add) }
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
