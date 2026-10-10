package dev.beka.spike

import android.annotation.SuppressLint
import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicReference

/**
 * Fase-8 spike: creates a virtual display as the shell user (run through Shizuku's rish):
 *
 *   app_process -Djava.class.path=<beka apk> /system/bin dev.beka.spike.DisplayHost [w h dpi flags]
 *
 * The display lives as long as this process. Its frames go to an ImageReader; touching
 * /data/local/tmp/vd.req saves the latest frame to /data/local/tmp/vd.png, so rendering can be
 * checked without the accessibility service. Lines written to /data/local/tmp/vd.cmd are run as
 * commands (shot, new, wakelock [levels], unwakelock, methods, ipm <method> <args>); output goes to stdout.
 * Same approach as scrcpy's --new-display.
 */
object DisplayHost {
    private const val SHELL = "com.android.shell"
    private const val REQ = "/data/local/tmp/vd.req"
    private const val OUT = "/data/local/tmp/vd.png"
    private const val CMD = "/data/local/tmp/vd.cmd"

    // DisplayManager.VIRTUAL_DISPLAY_FLAG_* (several are hidden).
    private const val PUBLIC = 1
    private const val OWN_CONTENT_ONLY = 1 shl 3
    private const val SUPPORTS_TOUCH = 1 shl 6
    private const val DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
    private const val TRUSTED = 1 shl 10
    private const val OWN_DISPLAY_GROUP = 1 shl 11
    private const val ALWAYS_UNLOCKED = 1 shl 12
    private const val TOUCH_FEEDBACK_DISABLED = 1 shl 13
    private const val OWN_FOCUS = 1 shl 14

    const val DEFAULT_FLAGS = PUBLIC or OWN_CONTENT_ONLY or SUPPORTS_TOUCH or DESTROY_CONTENT_ON_REMOVAL or
        TRUSTED or OWN_DISPLAY_GROUP or ALWAYS_UNLOCKED or TOUCH_FEEDBACK_DISABLED or OWN_FOCUS

    @JvmStatic
    fun main(args: Array<String>) {
        val w = args.getOrNull(0)?.toInt() ?: 1080
        val h = args.getOrNull(1)?.toInt() ?: 2400
        val dpi = args.getOrNull(2)?.toInt() ?: 420
        val flags = args.getOrNull(3)?.let { Integer.decode(it) } ?: DEFAULT_FLAGS

        Looper.prepareMainLooper()
        val context = shellContext()
        val frames = HandlerThread("frames").apply { start() }
        val latest = AtomicReference<Bitmap?>(null)
        val dm = DisplayManager::class.java.getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }.newInstance(context)

        fun create(): VirtualDisplay {
            val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
            reader.setOnImageAvailableListener({ r ->
                val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                image.use {
                    val plane = it.planes[0]
                    val bmp = Bitmap.createBitmap(plane.rowStride / plane.pixelStride, h, Bitmap.Config.ARGB_8888)
                    bmp.copyPixelsFromBuffer(plane.buffer)
                    latest.set(Bitmap.createBitmap(bmp, 0, 0, w, h))
                }
            }, Handler(frames.looper))
            val vd = dm.createVirtualDisplay("beka-vd", w, h, dpi, reader.surface, flags)
                ?: error("createVirtualDisplay returned null")
            say("DISPLAY_ID=${vd.display.displayId} flags=0x${Integer.toHexString(flags)} ${w}x$h@$dpi")
            return vd
        }

        var display = create()
        Runtime.getRuntime().addShutdownHook(Thread { display.release() })
        val power = context.getSystemService(PowerManager::class.java)
        var wakeLock: PowerManager.WakeLock? = null

        fun command(line: String) {
            val parts = line.trim().split(Regex("\\s+"))
            say("CMD $line")
            runCatching {
                when (parts[0]) {
                    "shot" -> {
                        val bmp = latest.get()
                        if (bmp == null) say("SHOT none (no frame yet)")
                        else {
                            FileOutputStream(OUT).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                            say("SHOT $OUT")
                        }
                    }
                    "new" -> {
                        display.release()
                        latest.set(null)
                        display = create()
                    }
                    // Screen wake lock bound to this display's power group (hidden 3-arg newWakeLock).
                    "wakelock" -> {
                        wakeLock?.release()
                        val levels = parts.getOrNull(1)?.let { Integer.decode(it) } ?: (0xa or 0x10000000)
                        val wl = PowerManager::class.java
                            .getMethod("newWakeLock", Int::class.java, String::class.java, Int::class.java)
                            .invoke(power, levels, "beka:vd", display.display.displayId) as PowerManager.WakeLock
                        wl.acquire()
                        wakeLock = wl
                        say("wakelock 0x${Integer.toHexString(levels)} held on display ${display.display.displayId}")
                    }
                    "unwakelock" -> { wakeLock?.release(); wakeLock = null; say("wakelock released") }
                    "methods" -> ipm().javaClass.methods
                        .filter { m -> listOf("wake", "sleep", "awake", "dream", "group").any { m.name.contains(it, true) } }
                        .forEach { m -> say("  ${m.name}(${m.parameterTypes.joinToString { it.simpleName }})") }
                    // ipm <method> <args…>: call IPowerManager directly; "now" = uptimeMillis, "vd" = display id.
                    "ipm" -> {
                        val args = parts.drop(2)
                        val m = ipm().javaClass.methods.first { it.name == parts[1] && it.parameterCount == args.size }
                        val values = m.parameterTypes.zip(args).map { (type, raw) ->
                            val v = when (raw) {
                                "now" -> SystemClock.uptimeMillis().toString()
                                "vd" -> display.display.displayId.toString()
                                else -> raw
                            }
                            when (type) {
                                Long::class.java -> v.toLong()
                                Int::class.java -> v.toInt()
                                Boolean::class.java -> v.toBoolean()
                                else -> if (v == "null") null else v
                            }
                        }
                        say("ipm ${m.name} → ${m.invoke(ipm(), *values.toTypedArray())}")
                    }
                    else -> say("unknown command")
                }
            }.onFailure { say("ERROR ${it.javaClass.simpleName}: ${it.cause ?: it.message}") }
        }

        val main = Handler(Looper.getMainLooper())
        val poll = object : Runnable {
            override fun run() {
                if (File(REQ).exists()) { File(REQ).delete(); command("shot") }
                val cmd = File(CMD)
                if (cmd.exists()) {
                    val lines = cmd.readLines()
                    cmd.delete()
                    lines.filter { it.isNotBlank() }.forEach(::command)
                }
                main.postDelayed(this, 300)
            }
        }
        main.post(poll)
        Looper.loop()
    }

    private fun say(msg: String) {
        println(msg)
        System.out.flush()
    }

    @SuppressLint("PrivateApi")
    private fun ipm(): Any {
        val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
            .invoke(null, "power")
        return Class.forName("android.os.IPowerManager\$Stub").getMethod("asInterface", android.os.IBinder::class.java)
            .invoke(null, binder)!!
    }

    /** A system context that says it is the shell package, so DisplayManager accepts the caller. */
    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    private fun shellContext(): Context {
        val atClass = Class.forName("android.app.ActivityThread")
        val thread = atClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        atClass.getDeclaredField("sCurrentActivityThread").apply { isAccessible = true }.set(null, thread)
        atClass.getDeclaredField("mSystemThread").apply { isAccessible = true }.setBoolean(thread, true)
        val system = atClass.getDeclaredMethod("getSystemContext").invoke(thread) as Context
        return object : ContextWrapper(system) {
            override fun getPackageName() = SHELL
            override fun getOpPackageName() = SHELL
            override fun getAttributionSource(): AttributionSource =
                if (Build.VERSION.SDK_INT >= 31) AttributionSource.Builder(2000).setPackageName(SHELL).build()
                else super.getAttributionSource()
            override fun getApplicationContext() = this
        }
    }
}
