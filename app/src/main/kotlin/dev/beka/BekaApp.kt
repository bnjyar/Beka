package dev.beka

import android.app.Application
import dev.beka.chat.ChatSession
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
    val chat by lazy { ChatSession(providers) }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: BekaApp
            private set
    }
}
