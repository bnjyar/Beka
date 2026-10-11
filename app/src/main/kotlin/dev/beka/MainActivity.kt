package dev.beka

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import dev.beka.auth.OAuthException
import dev.beka.auth.OpenRouterOAuth
import dev.beka.debug.AppLog
import dev.beka.chat.errorSummary
import dev.beka.chat.friendlyError
import dev.beka.llm.ProviderConfig
import dev.beka.llm.ProviderKind
import dev.beka.media.BekaNotificationListener
import dev.beka.ui.BekaTheme
import dev.beka.ui.MainScreen
import dev.beka.ui.Onboarding
import dev.beka.ui.Oem
import dev.beka.ui.OemHints
import dev.beka.overlay.OverlayService
import kotlinx.coroutines.CancellationException
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
        // Not on recreation (rotation, theme): the OAuth callback was already handled the first time.
        if (savedInstanceState == null) handleIntent(intent)
        if (app.appearance.onboarded.value) app.updater.autoCheckIfDue()
        // An OEM battery manager may have killed the bubble; opening the app brings it back if it was on.
        if (savedInstanceState == null && !OverlayService.running.value) OverlayService.restore(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        visible = true
        resumeTick++
        app.updater.onAppVisible()
    }

    override fun onPause() {
        visible = false
        super.onPause()
    }

    private fun handleIntent(intent: Intent?) {
        // The overlay can't ask for a runtime permission itself; it opens this screen to do it.
        if (intent?.action == ACTION_REQUEST_MIC) {
            actions.requestMicPermission()
            return
        }
        val uri = intent?.data
        if (!OpenRouterOAuth.isCallback(uri)) return
        // App scope: a rotation mid-exchange must not cancel it, and nothing would retry.
        val appContext = applicationContext
        app.appScope.launch {
            runCatching {
                val key = OpenRouterOAuth.complete(appContext, uri!!, app.http)
                val existing = app.providers.providers.value.firstOrNull { "openrouter.ai" in it.baseUrl }
                val config = existing ?: ProviderConfig(
                    id = UUID.randomUUID().toString(),
                    name = "OpenRouter",
                    kind = ProviderKind.OPENAI_COMPATIBLE,
                    baseUrl = "https://openrouter.ai/api/v1",
                    model = "openrouter/auto",
                )
                app.providers.save(config, key)
            }
                .onSuccess { toast(appContext, appContext.getString(R.string.login_openrouter_ok)) }
                .onFailure {
                    if (it is CancellationException) throw it
                    Log.e("BekaOverlay", "openrouter login failed: ${errorSummary(it)}")
                    AppLog.step("openrouter login failed: ${it.message}")
                    toast(appContext, if (it is OAuthException) friendlyError(it) else appContext.getString(R.string.login_openrouter_failed, friendlyError(it)))
                }
        }
    }

    private fun toast(text: String) = toast(this, text)

    private fun toast(context: Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()

    /**
     * Asks for a runtime permission. After the system has stopped showing the dialog (denied twice) the
     * request returns denied at once; [onRequestPermissionsResult] then sends the user to the right settings page.
     */
    private fun askPermission(permission: String, code: Int) = requestPermissions(arrayOf(permission), code)

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val permission = permissions.firstOrNull() ?: return
        if (grantResults.firstOrNull() != PackageManager.PERMISSION_DENIED) return
        // Still "show rationale" means a normal denial the dialog can be shown again for.
        if (shouldShowRequestPermissionRationale(permission)) return
        val page = if (permission == Manifest.permission.POST_NOTIFICATIONS) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        }
        toast(getString(R.string.perm_denied_before))
        runCatching { startActivity(page) }.onFailure { actions.openAppInfo() }
    }

    companion object {
        const val ACTION_REQUEST_MIC = "dev.beka.REQUEST_MIC"

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
                askPermission(Manifest.permission.POST_NOTIFICATIONS, 1)
            }
        }

        override fun hasMicPermission() =
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        override fun requestMicPermission() {
            askPermission(Manifest.permission.RECORD_AUDIO, 2)
        }

        override fun hasContactsPermission() =
            checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

        override fun requestContactsPermission() {
            askPermission(Manifest.permission.READ_CONTACTS, 3)
        }

        override fun hasCallPermission() =
            checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED

        override fun requestCallPermission() {
            askPermission(Manifest.permission.CALL_PHONE, 4)
        }

        override fun hasBatteryExemption() =
            getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

        override fun requestBatteryExemption() {
            runCatching {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            }.onFailure {
                runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                    .onFailure { openAppInfo() }
            }
        }

        override fun openOemSettings() {
            for (intent in OemHints.intents(this@MainActivity, Oem.current())) {
                if (runCatching { startActivity(intent) }.isSuccess) return
            }
        }

        override fun isAssistant() =
            getSystemService(android.app.role.RoleManager::class.java).isRoleHeld(android.app.role.RoleManager.ROLE_ASSISTANT)

        override fun openAssistantSettings() {
            runCatching { startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) }
                .onFailure {
                    runCatching { startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) }
                        .onFailure { toast(this@MainActivity.getString(R.string.assistant_settings_missing)) }
                }
        }

        override fun loginOpenRouter() {
            runCatching { OpenRouterOAuth.start(this@MainActivity) }
                .onFailure { toast(if (it is ActivityNotFoundException) this@MainActivity.getString(R.string.login_no_browser) else friendlyError(it)) }
        }

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
    fun hasMicPermission(): Boolean
    fun requestMicPermission()
    fun hasContactsPermission(): Boolean
    fun requestContactsPermission()
    fun hasCallPermission(): Boolean
    fun requestCallPermission()
    fun hasBatteryExemption(): Boolean
    fun requestBatteryExemption()
    /** The brand's autostart / background pop-up screen, or app details where there is none. */
    fun openOemSettings()
    fun isAssistant(): Boolean
    fun openAssistantSettings()
    fun loginOpenRouter()
    fun hasNotificationAccess(): Boolean
    fun openNotificationAccessSettings()
}
