package dev.beka.display

import android.annotation.SuppressLint
import android.content.AttributionSource
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.util.Log
import kotlin.system.exitProcess

/**
 * Entry point of the shell-side display host:
 *
 *   app_process -Djava.class.path=<beka apk> /system/bin --nice-name=beka-display dev.beka.display.HostMain
 *
 * Started through Shizuku (or, later, Beka's own ADB connection). It hands its binder to the app
 * through [HostProvider] and exits when the app process dies.
 */
object HostMain {
    @JvmStatic
    fun main(args: Array<String>) {
        Looper.prepareMainLooper()
        val host = DisplayHostService()
        val client = runCatching { sendToApp(host) }.getOrElse {
            Log.e(DisplayHostService.TAG, "host: could not reach the app", it)
            exitProcess(1)
        }
        client.linkToDeath({ Log.i(DisplayHostService.TAG, "host: app died, exiting"); exitProcess(0) }, 0)
        Log.i(DisplayHostService.TAG, "host: running as uid ${android.os.Process.myUid()}")
        println("BEKA_HOST_READY")
        System.out.flush()
        Looper.loop()
    }

    /** Calls the app's provider with our binder; the reply carries the app's token to watch. */
    @SuppressLint("PrivateApi")
    private fun sendToApp(host: IBinder): IBinder {
        val amBinder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
            .invoke(null, "activity") as IBinder
        val am = Class.forName("android.app.IActivityManager\$Stub").getMethod("asInterface", IBinder::class.java)
            .invoke(null, amBinder)!!
        val token = Binder()
        val holder = am.javaClass.methods.first { it.name == "getContentProviderExternal" && it.parameterCount == 4 }
            .invoke(am, HostProvider.AUTHORITY, 0, token, HostProvider.AUTHORITY)
            ?: error("provider ${HostProvider.AUTHORITY} not found")
        val provider = holder.javaClass.getField("provider").get(holder)!!
        try {
            val extras = Bundle().apply { putBinder(HostProvider.EXTRA_BINDER, host) }
            val attribution = AttributionSource.Builder(android.os.Process.myUid()).setPackageName(SHELL).build()
            val reply = provider.javaClass.methods
                .first { it.name == "call" && it.parameterCount == 5 && it.parameterTypes[0] == AttributionSource::class.java }
                .invoke(provider, attribution, HostProvider.AUTHORITY, HostProvider.METHOD_HOST, null, extras) as Bundle?
            return reply?.getBinder(HostProvider.EXTRA_BINDER) ?: error("app did not answer")
        } finally {
            runCatching {
                am.javaClass.methods.first { it.name == "removeContentProviderExternalAsUser" && it.parameterCount == 3 }
                    .invoke(am, HostProvider.AUTHORITY, token, 0)
            }
        }
    }

    private const val SHELL = "com.android.shell"
}
