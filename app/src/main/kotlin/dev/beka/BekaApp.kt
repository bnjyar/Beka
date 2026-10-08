package dev.beka

import android.app.Application
import dev.beka.chat.ChatSession
import dev.beka.debug.ActionLog
import dev.beka.settings.ProviderStore
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class BekaApp : Application() {
    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }
    val providers by lazy { ProviderStore(this, http) }
    val actionLog by lazy { ActionLog(this) }
    val chat by lazy { ChatSession(providers, actionLog) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Thread { runCatching { actionLog.prune() } }.start()
    }

    companion object {
        lateinit var instance: BekaApp
            private set
    }
}
