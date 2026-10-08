package dev.beka

import android.Manifest
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import dev.beka.auth.OpenRouterOAuth
import dev.beka.llm.ProviderConfig
import dev.beka.llm.ProviderKind
import dev.beka.settings.SettingsScreen
import dev.beka.ui.BekaTheme
import kotlinx.coroutines.launch
import java.util.UUID

class MainActivity : ComponentActivity() {
    private var resumeTick by mutableIntStateOf(0)
    private val app get() = application as BekaApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { BekaTheme { SettingsScreen(app = app, resumeTick = resumeTick, actions = actions) } }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        resumeTick++
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
}
