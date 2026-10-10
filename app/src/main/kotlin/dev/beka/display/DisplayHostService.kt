package dev.beka.display

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.content.AttributionSource
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.Surface
import kotlin.system.exitProcess

/**
 * The shell-side half of the virtual display (fase 9). Runs as uid 2000, which holds the
 * permissions for trusted, always-unlocked displays and for waking a single power group.
 * Created by [HostMain] in an app_process started through Shizuku (later also Beka's own ADB link).
 *
 * Everything here goes through hidden system APIs by reflection, the same calls scrcpy uses.
 */
class DisplayHostService() : IDisplayHost.Stub() {

    private val context: Context by lazy { shellContext() }
    private var display: VirtualDisplay? = null
    private var client: IBinder? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val clientDeath = IBinder.DeathRecipient { log("client died, releasing display"); release() }

    /** Ends the host process (a newer host replaced it). */
    override fun destroy() {
        release()
        exitProcess(0)
    }

    override fun uid(): Int = android.os.Process.myUid()

    @Synchronized
    override fun create(surface: Surface, width: Int, height: Int, dpi: Int, client: IBinder): Int {
        release()
        val dm = DisplayManager::class.java.getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }.newInstance(context)
        val vd = dm.createVirtualDisplay(NAME, width, height, dpi, surface, FLAGS)
            ?: error("createVirtualDisplay returned null")
        display = vd
        this.client = client.also { it.linkToDeath(clientDeath, 0) }
        val id = vd.display.displayId
        // Without this, focusing a field on the display pops the keyboard on the main screen.
        runCatching { windowManager().javaClass.getMethod("setDisplayImePolicy", Int::class.java, Int::class.java)
            .invoke(windowManager(), id, DISPLAY_IME_POLICY_HIDE) }
            .onFailure { log("ime policy: ${it.cause ?: it}") }
        log("display $id created ${width}x$height@$dpi")
        return id
    }

    @Synchronized
    override fun release() {
        wakeLock?.let { runCatching { it.release() } }
        wakeLock = null
        client?.let { runCatching { it.unlinkToDeath(clientDeath, 0) } }
        client = null
        display?.let { log("display ${it.display.displayId} released"); it.release() }
        display = null
    }

    override fun wake(): Boolean {
        val id = displayId() ?: return false
        return runCatching {
            val ipm = service("power", "android.os.IPowerManager")
            ipm.javaClass.methods.first { it.name == "wakeUpWithDisplayId" && it.parameterCount == 5 }
                .invoke(ipm, SystemClock.uptimeMillis(), 0, "beka", SHELL, id)
            true
        }.getOrElse { log("wake: ${it.cause ?: it}"); false }
    }

    @Synchronized
    override fun holdAwake(on: Boolean): Boolean {
        wakeLock?.let { runCatching { it.release() } }
        wakeLock = null
        if (!on) return true
        val id = displayId() ?: return false
        return runCatching {
            val power = context.getSystemService(PowerManager::class.java)
            val wl = PowerManager::class.java
                .getMethod("newWakeLock", Int::class.java, String::class.java, Int::class.java)
                .invoke(power, SCREEN_BRIGHT or ACQUIRE_CAUSES_WAKEUP, "beka:vd", id) as PowerManager.WakeLock
            wl.acquire()
            wakeLock = wl
            true
        }.getOrElse { log("wake lock: ${it.cause ?: it}"); false }
    }

    override fun launch(intent: Intent): String? {
        val id = displayId() ?: return "No virtual display"
        val pkg = intent.`package` ?: intent.component?.packageName
        if (pkg != null) {
            val task = rootTasks().firstOrNull { it.pkg == pkg && it.displayId != id }
            if (task != null && moveTask(task.id, id)) {
                log("moved task ${task.id} ($pkg) from display ${task.displayId} to $id")
                return null
            }
        }
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(id).toBundle()
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            val am = service("activity", "android.app.IActivityManager")
            val start = am.javaClass.methods.first { it.name == "startActivityAsUserWithFeature" && it.parameterCount == 12 }
            val code = start.invoke(am, null, SHELL, null, intent, null, null, null, 0, 0, null, options, USER_CURRENT) as Int
            if (code >= 0) null else "Start failed ($code)"
        }.getOrElse { (it.cause ?: it).let { e -> log("launch: $e"); e.message ?: e.javaClass.simpleName } }
    }

    override fun key(keyCode: Int): Boolean {
        val id = displayId() ?: return false
        val now = SystemClock.uptimeMillis()
        return listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP).all { action ->
            val event = KeyEvent(now, now, action, keyCode, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)
            runCatching {
                KeyEvent::class.java.getMethod("setDisplayId", Int::class.java).invoke(event, id)
                val im = inputManager()
                im.javaClass.getMethod("injectInputEvent", android.view.InputEvent::class.java, Int::class.java)
                    .invoke(im, event, INJECT_WAIT_FOR_FINISH) as Boolean
            }.getOrElse { log("key: ${it.cause ?: it}"); false }
        }
    }

    override fun moveToMain(packageName: String): Boolean {
        val id = displayId() ?: return false
        val task = rootTasks().firstOrNull { it.pkg == packageName && it.displayId == id } ?: return false
        return moveTask(task.id, 0)
    }

    override fun packages(): List<String> {
        val id = displayId() ?: return emptyList()
        return rootTasks().filter { it.displayId == id }.map { it.pkg }
    }

    private fun displayId(): Int? = display?.display?.displayId

    private class RootTask(val id: Int, val displayId: Int, val pkg: String)

    /** Standard-activity root tasks on all displays, top first. */
    private fun rootTasks(): List<RootTask> = runCatching {
        val atm = service("activity_task", "android.app.IActivityTaskManager")
        val infos = atm.javaClass.methods.first { it.name == "getAllRootTaskInfos" && it.parameterCount == 0 }
            .invoke(atm) as List<*>
        infos.mapNotNull { info ->
            info ?: return@mapNotNull null
            val f = { name: String -> findField(info.javaClass, name)?.get(info) }
            if (f("activityType") != ACTIVITY_TYPE_STANDARD) return@mapNotNull null
            val component = (f("baseActivity") ?: f("topActivity")) as? ComponentName ?: return@mapNotNull null
            RootTask(f("taskId") as Int, f("displayId") as Int, component.packageName)
        }
    }.getOrElse { log("root tasks: ${it.cause ?: it}"); emptyList() }

    private fun moveTask(taskId: Int, displayId: Int): Boolean = runCatching {
        val atm = service("activity_task", "android.app.IActivityTaskManager")
        atm.javaClass.methods.first { it.name == "moveRootTaskToDisplay" && it.parameterCount == 2 }
            .invoke(atm, taskId, displayId)
        true
    }.getOrElse { log("move task $taskId → $displayId: ${it.cause ?: it}"); false }

    private fun findField(type: Class<*>, name: String): java.lang.reflect.Field? {
        var c: Class<*>? = type
        while (c != null) {
            runCatching { return c.getDeclaredField(name).apply { isAccessible = true } }
            c = c.superclass
        }
        return null
    }

    private fun windowManager() = service("window", "android.view.IWindowManager")

    @SuppressLint("PrivateApi")
    private fun inputManager(): Any {
        // Android 14 moved injection to InputManagerGlobal; older versions keep it on InputManager.
        runCatching {
            return Class.forName("android.hardware.input.InputManagerGlobal").getMethod("getInstance").invoke(null)!!
        }
        return android.hardware.input.InputManager::class.java.getMethod("getInstance").invoke(null)!!
    }

    @SuppressLint("PrivateApi")
    private fun service(name: String, iface: String): Any {
        val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
            .invoke(null, name) as IBinder
        return Class.forName("$iface\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, binder)!!
    }

    /** A system context that says it is the shell package, so DisplayManager accepts uid 2000 as the caller. */
    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    private fun shellContext(): Context {
        val atClass = Class.forName("android.app.ActivityThread")
        // Shizuku's starter already made one; a bare app_process has none, so make a detached system thread.
        val thread = atClass.getMethod("currentActivityThread").invoke(null)
            ?: atClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance().also { t ->
                atClass.getDeclaredField("sCurrentActivityThread").apply { isAccessible = true }.set(null, t)
                atClass.getDeclaredField("mSystemThread").apply { isAccessible = true }.setBoolean(t, true)
            }
        val system = atClass.getMethod("getSystemContext").invoke(thread) as Context
        return object : ContextWrapper(system) {
            override fun getPackageName() = SHELL
            override fun getOpPackageName() = SHELL
            override fun getAttributionSource(): AttributionSource =
                AttributionSource.Builder(android.os.Process.myUid()).setPackageName(SHELL).build()
            override fun getApplicationContext() = this
        }
    }

    private fun log(msg: String) {
        Log.i(TAG, msg)
    }

    companion object {
        const val TAG = "BekaDisplay"
        private const val NAME = "beka-vd"
        private const val SHELL = "com.android.shell"
        private const val USER_CURRENT = -2
        private const val ACTIVITY_TYPE_STANDARD = 1
        private const val DISPLAY_IME_POLICY_HIDE = 2
        private const val INJECT_WAIT_FOR_FINISH = 2
        private const val SCREEN_BRIGHT = 0xa
        private const val ACQUIRE_CAUSES_WAKEUP = 0x10000000

        // DisplayManager.VIRTUAL_DISPLAY_FLAG_* (several are hidden); verified in the fase-8 spike.
        private const val FLAGS = 1 or // PUBLIC
            (1 shl 3) or // OWN_CONTENT_ONLY
            (1 shl 6) or // SUPPORTS_TOUCH
            (1 shl 8) or // DESTROY_CONTENT_ON_REMOVAL
            (1 shl 10) or // TRUSTED
            (1 shl 11) or // OWN_DISPLAY_GROUP
            (1 shl 12) or // ALWAYS_UNLOCKED
            (1 shl 13) or // TOUCH_FEEDBACK_DISABLED
            (1 shl 14) // OWN_FOCUS
    }
}
