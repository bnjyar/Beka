package dev.beka.display

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.Image
import android.media.ImageReader
import android.os.Binder
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.Display
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import rikka.shizuku.Shizuku

/**
 * Beka's virtual display (fase 9): a second screen the agent can work on while the user keeps
 * using the phone, or while it is locked. The display itself is created by [DisplayHostService]
 * running as the shell user; this side owns the surface it renders into, so frames for the
 * preview arrive here directly.
 */
class VirtualScreen(private val context: Context) {

    /** NO_LAUNCHER = neither Beka's own ADB pairing nor a running Shizuku. */
    enum class Status { NO_LAUNCHER, NEED_PERMISSION, CONNECTING, READY, FAILED }

    private val _status = MutableStateFlow(Status.NO_LAUNCHER)
    val status: StateFlow<Status> = _status

    /** Latest frame, scaled down, while someone is [watch]ing. */
    private val _frame = MutableStateFlow<Bitmap?>(null)
    val frame: StateFlow<Bitmap?> = _frame

    @Volatile
    private var host: IDisplayHost? = null

    /** Id of the open display, or null. */
    @Volatile
    var displayId: Int? = null
        private set

    private var reader: ImageReader? = null
    private val token = Binder()
    private val frames = HandlerThread("vd-frames").apply { start() }
    private val frameHandler = Handler(frames.looper)
    @Volatile
    private var watchers = 0
    @Volatile
    private var lastFrameAt = 0L

    /** The host process, while Beka started it through Shizuku. */
    private var process: Process? = null

    /** Called by [HostProvider] when a freshly started host hands over its binder. Returns our token. */
    fun onHost(binder: IBinder): IBinder {
        val h = IDisplayHost.Stub.asInterface(binder)
        host?.let { old -> if (old.asBinder() != binder) runCatching { old.destroy() } }
        dropDisplay()
        host = h
        binder.linkToDeath({
            if (host?.asBinder() == binder) {
                host = null
                dropDisplay()
                _status.value = Status.FAILED
                log("host died")
                // Set up once: bring it back without the user noticing.
                if (wanted) frameHandler.postDelayed({ connect() }, RESTART_DELAY_MS)
            }
        }, 0)
        _status.value = Status.READY
        wanted = true
        log("host connected, uid ${runCatching { h.uid() }.getOrNull()}")
        return token
    }

    // Before init: the sticky Shizuku listener may run right away and reads it.
    private val prefs = context.getSharedPreferences("virtual_screen", Context.MODE_PRIVATE)

    init {
        Shizuku.addBinderReceivedListenerSticky {
            refresh()
            // Connected before: come back on our own after a reboot or an update.
            if (wanted && host == null && !adb.paired) connect()
        }
        Shizuku.addBinderDeadListener {
            host = null
            dropDisplay()
            refresh()
        }
        Shizuku.addRequestPermissionResultListener { _, result ->
            if (result == PackageManager.PERMISSION_GRANTED) connect() else refresh()
        }
    }

    private val adb: AdbLink get() = (context.applicationContext as dev.beka.BekaApp).adbLink

    /** The user connected once; the host is started again by itself from then on. */
    private var wanted: Boolean
        get() = prefs.getBoolean("wanted", false)
        set(value) { prefs.edit().putBoolean("wanted", value).apply() }

    /** Ends the running host and starts a new one, e.g. to move from Shizuku to Beka's own ADB link. */
    fun reconnect() {
        val old = host
        host = null
        dropDisplay()
        runCatching { old?.destroy() }
        connect()
    }

    /** Starts the host if the user connected it before (app start, boot). */
    fun resume() {
        if (wanted) connect()
    }

    private fun command() = "app_process -Djava.class.path=${context.applicationInfo.sourceDir} /system/bin " +
        "--nice-name=beka-display ${HostMain::class.java.name}"

    /** Beka's own ADB link. At boot Wi-Fi may not be up yet, so it keeps trying for a while. */
    private fun connectAdb() {
        _status.value = Status.CONNECTING
        Thread {
            for (attempt in 1..ADB_ATTEMPTS) {
                if (host != null) return@Thread
                // Wireless debugging only runs on Wi-Fi. Without it, Shizuku (if running) starts the host;
                // otherwise wait for Wi-Fi instead of switching the setting on and off.
                if (!onWifi()) {
                    if (shizukuReady()) {
                        log("adb: no Wi-Fi, starting through Shizuku")
                        frameHandler.post(::connectShizuku)
                        return@Thread
                    }
                    if (attempt == 1) log("adb: no Wi-Fi, waiting")
                    Thread.sleep(ADB_RETRY_MS)
                    continue
                }
                // In its own session, so the host outlives the ADB connection (Wireless debugging
                // may be switched off later); it still exits when Beka dies.
                val detached = "setsid ${command()} </dev/null >/dev/null 2>&1 &"
                val failure = runCatching { adb.startShell(detached) { log("host> $it") } }.exceptionOrNull()
                if (failure == null) {
                    // The host calls back through HostProvider; give it a moment.
                    repeat(50) { if (host != null) return@Thread; Thread.sleep(200) }
                    log("adb: host did not call back")
                } else log("adb: attempt $attempt failed: ${failure.message ?: failure}")
                if (attempt < ADB_ATTEMPTS) Thread.sleep(ADB_RETRY_MS)
            }
            if (host == null) _status.value = Status.FAILED
        }.apply { isDaemon = true }.start()
    }

    private fun refresh() {
        if (host != null || adb.paired) return
        _status.value = when {
            !Shizuku.pingBinder() -> Status.NO_LAUNCHER
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> Status.NEED_PERMISSION
            else -> if (_status.value == Status.CONNECTING) Status.CONNECTING else Status.FAILED
        }
    }

    /** Starts the host through Beka's own ADB pairing, else Shizuku (asking its permission first if needed). */
    fun connect() {
        // App start and the Shizuku binder arriving both call this; one host is enough.
        if (host != null || _status.value == Status.CONNECTING) return
        if (adb.paired) return connectAdb()
        if (!Shizuku.pingBinder()) return refresh()
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            _status.value = Status.NEED_PERMISSION
            Shizuku.requestPermission(PERMISSION_REQUEST)
            return
        }
        connectShizuku()
    }

    private fun shizukuReady(): Boolean = runCatching {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    private fun onWifi(): Boolean {
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
        return cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true }
    }

    /** Starts the host as a Shizuku process (permission already granted). */
    private fun connectShizuku() {
        if (host != null) return
        _status.value = Status.CONNECTING
        val command = arrayOf(
            "app_process", "-Djava.class.path=${context.applicationInfo.sourceDir}", "/system/bin",
            "--nice-name=beka-display", HostMain::class.java.name,
        )
        runCatching {
            // Shizuku keeps newProcess private since API 13 in favour of user services, whose starter
            // crashes on Android 16 (LoadedApk.makeApplication NPE), so it is called directly.
            val newProcess = Shizuku::class.java.getDeclaredMethod(
                "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java,
            ).apply { isAccessible = true }
            val p = newProcess.invoke(null, command, null, null) as Process
            process = p
            Thread {
                p.inputStream.bufferedReader().forEachLine { log("host> $it") }
                val code = runCatching { p.waitFor() }.getOrNull()
                log("host process exited ($code)")
                if (host == null && _status.value == Status.CONNECTING) _status.value = Status.FAILED
            }.apply { isDaemon = true }.start()
        }.onFailure { log("start failed: ${it.cause ?: it}"); _status.value = Status.FAILED }
    }

    /** Opens the display (if not open yet) the size of the phone screen. Returns its id. */
    @Synchronized
    fun open(): Int? {
        displayId?.let { return it }
        val h = host ?: return null
        val metrics = context.resources.displayMetrics
        val dm = context.getSystemService(DisplayManager::class.java)
        val real = android.graphics.Point().also { @Suppress("DEPRECATION") dm.getDisplay(Display.DEFAULT_DISPLAY).getRealSize(it) }
        val w = real.x
        val h2 = real.y
        val r = ImageReader.newInstance(w, h2, PixelFormat.RGBA_8888, 3)
        r.setOnImageAvailableListener({ onFrame(it, w, h2) }, frameHandler)
        return runCatching { h.create(r.surface, w, h2, metrics.densityDpi, token).also { check(it > 0) { "bad display id $it" } } }
            .onSuccess { id ->
                reader = r
                displayId = id
                log("display $id open ${w}x$h2@${metrics.densityDpi}")
            }
            .onFailure { log("create failed: $it"); r.close() }
            .getOrNull()
    }

    @Synchronized
    fun close() {
        runCatching { host?.release() }
        dropDisplay()
    }

    @Synchronized
    private fun dropDisplay() {
        displayId = null
        val r = reader
        reader = null
        _frame.value = null
        // The image belongs to the frames thread; close it and the reader there.
        frameHandler.post {
            lastImage?.close()
            lastImage = null
            r?.close()
        }
    }

    /** Display.STATE_* of the virtual display, or null when none is open. */
    fun state(): Int? = displayId?.let { context.getSystemService(DisplayManager::class.java).getDisplay(it)?.state }

    /**
     * Makes sure the display is awake. The power button puts every display group to sleep and the
     * virtual one does not wake with the phone, so it is woken on its own before each step.
     */
    suspend fun ensureAwake(): Boolean {
        if (state() == Display.STATE_ON) return true
        val h = host ?: return false
        runCatching { h.wake() }
        repeat(10) {
            delay(100)
            if (state() == Display.STATE_ON) return true
        }
        log("display still ${state()} after wake")
        return false
    }

    fun holdAwake(on: Boolean): Boolean = runCatching { host?.holdAwake(on) == true }.getOrDefault(false)

    /** Shows [intent] on the display; null on success, else why not. */
    fun launch(intent: Intent): String? {
        val h = host ?: return "Layar virtual belum tersambung"
        open() ?: return "Layar virtual gagal dibuat"
        return runCatching { h.launch(intent) }.getOrElse { it.message ?: "launch failed" }
    }

    fun key(keyCode: Int): Boolean = runCatching { host?.key(keyCode) == true }.getOrDefault(false)

    fun moveToMain(packageName: String): Boolean = runCatching { host?.moveToMain(packageName) == true }.getOrDefault(false)

    /** Gives the user's apps that a task took over back to the main screen. Returns how many. */
    fun returnBorrowed(): Int = runCatching { host?.returnBorrowed() ?: 0 }.getOrDefault(0)

    fun packages(): List<String> = runCatching { host?.packages().orEmpty() }.getOrDefault(emptyList())

    /** Keeps [frame] updated (a few times a second) until the returned handle is closed. */
    fun watch(): AutoCloseable {
        watchers++
        frameHandler.post(::render)
        return AutoCloseable {
            watchers--
            if (watchers <= 0) {
                watchers = 0
                _frame.value = null
            }
        }
    }

    /** The newest frame stays acquired: a still screen sends no new ones, so the preview needs the last. */
    private var lastImage: Image? = null
    private var frameSize = 0 to 0
    private var renderPending = false

    private fun onFrame(r: ImageReader, w: Int, h: Int) {
        val image = runCatching { r.acquireLatestImage() }.getOrNull() ?: return
        lastImage?.close()
        lastImage = image
        frameSize = w to h
        if (watchers == 0 || renderPending) return
        renderPending = true
        val wait = (lastFrameAt + FRAME_INTERVAL_MS - SystemClock.uptimeMillis()).coerceAtLeast(0)
        frameHandler.postDelayed(::render, wait)
    }

    /** Runs on the frames thread, like [onFrame], so [lastImage] is never closed underneath it. */
    private fun render() {
        renderPending = false
        val image = lastImage ?: return
        if (watchers == 0) return
        lastFrameAt = SystemClock.uptimeMillis()
        val (w, h) = frameSize
        runCatching {
            val plane = image.planes[0]
            val full = Bitmap.createBitmap(plane.rowStride / plane.pixelStride, h, Bitmap.Config.ARGB_8888)
            full.copyPixelsFromBuffer(plane.buffer.rewind())
            val scale = PREVIEW_WIDTH.toFloat() / w
            _frame.value = Bitmap.createScaledBitmap(Bitmap.createBitmap(full, 0, 0, w, h), PREVIEW_WIDTH, (h * scale).toInt(), true)
            full.recycle()
        }.onFailure { log("frame: $it") }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var lockTest: Job? = null

    private val _testLine = MutableStateFlow<String?>(null)
    /** Progress of [startLockTest] for the settings screen. */
    val testLine: StateFlow<String?> = _testLine

    /** The lock test owns the display while it runs. */
    val testing: Boolean get() = lockTest?.isActive == true

    /**
     * Fase-9 power check: for 90 s the display is kept on by a wake lock alone, then for 90 s it is
     * woken only when found asleep. Each 5 s sample is logged, so a run with the phone locked shows
     * which approach keeps it usable.
     */
    fun startLockTest() {
        val previous = lockTest
        lockTest = scope.launch {
            // The old test releases its display before this one opens one.
            previous?.cancelAndJoin()
            try {
                open() ?: return@launch run { _testLine.value = "Layar virtual gagal dibuat" }
                val held = holdAwake(true)
                log("lock test: wake lock held=$held")
                for (i in 1..36) {
                    delay(5_000)
                    val phase = if (i <= 18) "wakelock" else "wake-per-step"
                    if (i == 19) log("lock test: wake lock released=${holdAwake(false)}")
                    val before = state()
                    val woke = if (phase == "wake-per-step" && before != Display.STATE_ON) ensureAwake() else null
                    val line = "lock test ${i * 5}s $phase state=${stateName(before)}" +
                        (woke?.let { " woke=$it now=${stateName(state())}" } ?: "")
                    log(line)
                    _testLine.value = line
                }
                _testLine.value = "Selesai — cek log BekaDisplay"
            } finally {
                // Stopped, replaced by a task or finished: what it opened is released.
                holdAwake(false)
                close()
            }
        }
    }

    /** A background task takes the display over: the lock test ends (and releases it) first. */
    suspend fun stopLockTest() {
        lockTest?.let { if (it.isActive) { it.cancel(); it.join(); _testLine.value = "Tes kunci dihentikan oleh tugas Beka" } }
    }

    private fun stateName(state: Int?) = when (state) {
        Display.STATE_ON -> "ON"
        Display.STATE_OFF -> "OFF"
        Display.STATE_DOZE -> "DOZE"
        Display.STATE_DOZE_SUSPEND -> "DOZE_SUSPEND"
        null -> "none"
        else -> state.toString()
    }

    private fun log(msg: String) {
        Log.i(DisplayHostService.TAG, msg)
    }

    private companion object {
        const val PERMISSION_REQUEST = 9
        const val ADB_ATTEMPTS = 20
        const val ADB_RETRY_MS = 30_000L
        const val RESTART_DELAY_MS = 3_000L
        const val FRAME_INTERVAL_MS = 250L
        const val PREVIEW_WIDTH = 360
    }
}
