package dev.beka

import android.app.Application
import dev.beka.chat.ChatSession
import dev.beka.debug.ActionLog
import dev.beka.media.MediaState
import dev.beka.memory.MemorySettings
import dev.beka.memory.SqliteMemoryStore
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
    val media by lazy { MediaState(this) }
    val memoryStore by lazy { SqliteMemoryStore(this) }
    val memorySettings by lazy { MemorySettings(this) }
    val chat by lazy { ChatSession(providers, actionLog, media, memoryStore, memorySettings) }

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
