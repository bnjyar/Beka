package dev.beka

import android.app.Application
import dev.beka.chat.ChatSession
import dev.beka.debug.ActionLog
import dev.beka.display.LockedNotices
import dev.beka.display.VirtualScreen
import dev.beka.media.MediaState
import dev.beka.phone.AndroidPhone
import dev.beka.memory.MemorySettings
import dev.beka.memory.SqliteMemoryStore
import dev.beka.rules.Proactive
import dev.beka.rules.ProactiveSettings
import dev.beka.rules.RulesStore
import dev.beka.settings.AppearanceSettings
import dev.beka.settings.ProviderStore
import dev.beka.voice.Listener
import dev.beka.settings.ConfirmSettings
import dev.beka.settings.McpSettings
import dev.beka.settings.WebSettings
import dev.beka.voice.Speaker
import dev.beka.voice.VoiceSettings
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
    val webSettings by lazy { WebSettings(this) }
    val mcpSettings by lazy { McpSettings(this, http) }
    val confirmSettings by lazy { ConfirmSettings(this) }
    val phone by lazy { AndroidPhone(this) }
    val chat by lazy { ChatSession(providers, actionLog, media, memoryStore, memorySettings, webSettings, mcpSettings, confirmSettings, http, phone, virtualScreen, lockedNotices) }
    val rulesStore by lazy { RulesStore(this) }
    val proactiveSettings by lazy { ProactiveSettings(this) }
    val proactive by lazy { Proactive(this) }
    val appearance by lazy { AppearanceSettings(this) }
    val listener by lazy { Listener(this) }
    val speaker by lazy { Speaker(this, voiceSettings) }
    val voiceSettings by lazy { VoiceSettings(this) }
    val virtualScreen by lazy { VirtualScreen(this) }
    val lockedNotices by lazy { LockedNotices(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Thread { runCatching { actionLog.prune() } }.start()
        proactive.start()
    }

    companion object {
        lateinit var instance: BekaApp
            private set
    }
}
