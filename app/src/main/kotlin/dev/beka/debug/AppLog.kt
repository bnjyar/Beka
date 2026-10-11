package dev.beka.debug

import android.content.Context
import android.util.Log
import dev.beka.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Debug log recorder: copies this process's log from logcat into files as it is written, so
 * nothing is lost when the system log rotates (on this phone it does within minutes), and keeps
 * the model calls and agent steps of [DebugLog] on disk too. Off by default in release builds,
 * where only the report button records anything.
 */
object AppLog {
    private const val TAG = "BekaLog"
    private const val PREFS = "debug"
    private const val KEY_ON = "app_log"
    private const val MAX_BYTES = 4L * 1024 * 1024
    /** app-log.txt plus this many older files. */
    private const val OLD_FILES = 2
    private const val POLL_MS = 5_000L

    /** System chatter from inside our process that only buries Beka's own lines. */
    private val NOISE = Regex(
        """InteractionJankMonitor|BLASTBufferQueue|BufferQueueConsumer|BufferQueueProducer|VRI\[|InputEventReceiver|""" +
            """ImeTracker|HWUI|OpenGLRenderer|SurfaceComposerClient|PackageConfigPersister"""
    )

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled

    private lateinit var dir: File
    private var reader: Thread? = null
    /** Bumped by [stop]: a reader from an earlier start ends instead of writing on. */
    @Volatile
    private var generation = 0

    fun init(context: Context) {
        dir = File(context.filesDir, "logs").apply { mkdirs() }
        _enabled.value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ON, BuildConfig.RECORD_LOG)
        installCrashHandler()
        if (_enabled.value) start()
    }

    fun setEnabled(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ON, on).apply()
        _enabled.value = on
        if (on) start() else stop()
        // Agent actions are only kept on disk while recording: turning it off drops the ones kept so far.
        if (!on) (context.applicationContext as? dev.beka.BekaApp)?.actionLog?.clear()
    }

    /** The log files, oldest first. */
    fun files(prefix: String = "app-log"): List<File> =
        (OLD_FILES downTo 1).map { File(dir, "$prefix.$it.txt") }.filter { it.exists() } +
            listOf(File(dir, "$prefix.txt")).filter { it.exists() }

    /** One line of [DebugLog] (a model call or agent step), kept on disk while recording. */
    fun step(line: String) {
        if (!_enabled.value) return
        append("steps", line)
    }

    @Synchronized
    private fun start() {
        if (reader != null) return
        val pid = android.os.Process.myPid()
        append("app-log", "----- recording started, pid $pid, v${BuildConfig.VERSION_NAME} -----")
        val mine = generation
        // Polled, not streamed: a streaming logcat's pipe is block-buffered, so a quiet app's lines sat
        // unwritten for hours. Every few seconds the new lines are copied; the buffer keeps far longer.
        reader = Thread {
            while (generation == mine) {
                runCatching { collect(pid) }
                runCatching { Thread.sleep(POLL_MS) }
            }
        }.apply {
            name = "beka-applog"
            isDaemon = true
            start()
        }
        Log.i(TAG, "recording to $dir")
    }

    /** The last line copied, so the next poll (which starts at its timestamp) skips what it already has. */
    private var lastLine: String? = null

    /** Copies this process's log lines that are new since the last call. Also called before a report. */
    @Synchronized
    fun collect(pid: Int = android.os.Process.myPid()) {
        if (!_enabled.value || !::dir.isInitialized) return
        val since = lastLine?.take(18)
        val args = mutableListOf("logcat", "-d", "-v", "threadtime", "--pid", pid.toString())
        if (since != null) args += listOf("-T", since)
        val lines = Runtime.getRuntime().exec(args.toTypedArray()).inputStream.bufferedReader().readLines()
            .filter { it.length > 18 && it[2] == '-' }
        val last = lastLine
        val fresh = if (last == null) lines else lines.indexOf(last).let { at -> if (at >= 0) lines.drop(at + 1) else lines }
        fresh.forEach { if (!NOISE.containsMatchIn(it)) append("app-log", it) }
        fresh.lastOrNull()?.let { lastLine = it }
    }

    @Synchronized
    private fun stop() {
        collect()
        append("app-log", "----- recording stopped -----")
        generation++
        reader = null
    }

    @Synchronized
    private fun append(prefix: String, line: String) {
        if (!::dir.isInitialized) return
        val file = File(dir, "$prefix.txt")
        if (file.length() > MAX_BYTES) rotate(prefix)
        runCatching { FileWriter(file, true).use { it.append(line).append('\n') } }
    }

    private fun rotate(prefix: String) {
        File(dir, "$prefix.$OLD_FILES.txt").delete()
        for (i in OLD_FILES - 1 downTo 1) File(dir, "$prefix.$i.txt").renameTo(File(dir, "$prefix.${i + 1}.txt"))
        File(dir, "$prefix.txt").renameTo(File(dir, "$prefix.1.txt"))
    }

    /** A crash kills the logcat reader with the process; its stack trace is written here first. */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            if (_enabled.value) runCatching {
                val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }
                val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
                append("app-log", "$stamp CRASH in ${thread.name}: $trace")
            }
            previous?.uncaughtException(thread, error)
        }
    }
}
