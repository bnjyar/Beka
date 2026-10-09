package dev.beka

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import dev.beka.auth.OpenRouterOAuth
import dev.beka.llm.ProviderConfig
import dev.beka.llm.ProviderKind
import dev.beka.media.BekaNotificationListener
import dev.beka.ui.BekaTheme
import dev.beka.ui.MainScreen
import dev.beka.ui.Onboarding
import kotlinx.coroutines.launch
import java.util.UUID

class MainActivity : ComponentActivity() {
    private var resumeTick by mutableIntStateOf(0)
    private val app get() = application as BekaApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BekaTheme {
                val onboarded by app.appearance.onboarded.collectAsState()
                if (!onboarded) Onboarding(app = app, resumeTick = resumeTick, actions = actions)
                else MainScreen(app = app, resumeTick = resumeTick, actions = actions)
            }
        }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        visible = true
        resumeTick++
    }

    override fun onPause() {
        visible = false
        super.onPause()
    }

    private fun handleIntent(intent: Intent?) {
        val uri = intent?.data
        if (!OpenRouterOAuth.isCallback(uri)) return
        lifecycleScope.launch {
            runCatching { OpenRouterOAuth.complete(this@MainActivity, uri!!, app.http) }
                .onSuccess { key ->
                    val existing = app.providers.providers.value.firstOrNull { "openrouter.ai" in it.baseUrl }
                    val config = existing ?: ProviderConfig(
                        id = UUID.randomUUID().toString(),
                        name = "OpenRouter",
                        kind = ProviderKind.OPENAI_COMPATIBLE,
                        baseUrl = "https://openrouter.ai/api/v1",
                        model = "openrouter/auto",
                    )
                    app.providers.save(config, key)
                    toast("Login OpenRouter berhasil")
                }
                .onFailure { toast("Login OpenRouter gagal: ${it.message}") }
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    companion object {
        /** True while the settings screen is in the foreground. */
        @Volatile
        var visible = false
            private set
    }

    private val actions = object : SettingsActions {
        override fun hasOverlayPermission() = Settings.canDrawOverlays(this@MainActivity)

        override fun hasNotificationPermission() =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

        override fun requestOverlayPermission() {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }

        override fun openAccessibilitySettings() {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        override fun openAppInfo() {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }

        override fun requestNotificationPermission() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        }

        override fun loginOpenRouter() = OpenRouterOAuth.start(this@MainActivity)

        override fun hasNotificationAccess() = (application as BekaApp).media.hasAccess()

        override fun openNotificationAccessSettings() {
            val component = ComponentName(this@MainActivity, BekaNotificationListener::class.java)
            val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component.flattenToString())
            runCatching { startActivity(detail) }
                .onFailure { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        }
    }
}

interface SettingsActions {
    fun hasOverlayPermission(): Boolean
    fun hasNotificationPermission(): Boolean
    fun requestOverlayPermission()
    fun openAccessibilitySettings()
    fun openAppInfo()
    fun requestNotificationPermission()
    fun loginOpenRouter()
    fun hasNotificationAccess(): Boolean
    fun openNotificationAccessSettings()
}
