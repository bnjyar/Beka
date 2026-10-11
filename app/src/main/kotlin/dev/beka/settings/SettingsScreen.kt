package dev.beka.settings

import android.content.Intent
import android.view.KeyEvent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.beka.BekaApp
import dev.beka.BuildConfig
import dev.beka.R
import dev.beka.SettingsActions
import dev.beka.accessibility.AppResolver
import dev.beka.accessibility.BekaAccessibilityService
import dev.beka.data.Backup
import dev.beka.data.BackupException
import dev.beka.data.BackupManager
import dev.beka.agent.Blocklist
import dev.beka.llm.ChatEvent
import dev.beka.llm.ChatRequest
import dev.beka.llm.Lang
import dev.beka.llm.Message
import dev.beka.chat.friendlyError
import dev.beka.llm.Presets
import dev.beka.llm.ProviderConfig
import dev.beka.llm.ProviderKind
import dev.beka.display.VirtualScreen
import dev.beka.update.UpdateChannel
import dev.beka.update.UpdateState
import dev.beka.ui.Oem
import dev.beka.ui.OemHints
import dev.beka.ui.PanelCard
import dev.beka.voice.VoiceSettings
import dev.beka.settings.WebSettings.Companion.BRAVE
import dev.beka.settings.WebSettings.Companion.DUCKDUCKGO
import dev.beka.settings.WebSettings.Companion.TAVILY
import dev.beka.settings.WebSettings.Companion.TINYFISH
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(app: BekaApp, resumeTick: Int, actions: SettingsActions, startAdding: Boolean = false) {
    val a11y by BekaAccessibilityService.connected.collectAsState()
    val providers by app.providers.providers.collectAsState()
    val activeId by app.providers.activeId.collectAsState()
    val bubbleAlpha by app.appearance.bubbleIdleAlpha.collectAsState()
    val panelAlpha by app.appearance.panelAlpha.collectAsState()
    val speakReplies by app.voiceSettings.speakReplies.collectAsState()
    val voices by app.speaker.voices.collectAsState()
    val speechProblem by app.speaker.problem.collectAsState()
    val voice by app.voiceSettings.voice.collectAsState()
    val rate by app.voiceSettings.rate.collectAsState()
    val pitch by app.voiceSettings.pitch.collectAsState()
    LaunchedEffect(Unit) { app.speaker.warmUp() }
    var editing by remember { mutableStateOf<ProviderConfig?>(null) }
    var adding by remember { mutableStateOf(startAdding) }

    // Re-read permission state whenever the activity resumes.
    val overlayOk = remember(resumeTick) { actions.hasOverlayPermission() }
    val notifOk = remember(resumeTick) { actions.hasNotificationPermission() }
    val notifAccess = remember(resumeTick) { actions.hasNotificationAccess() }
    val micOk = remember(resumeTick) { actions.hasMicPermission() }
    val batteryOk = remember(resumeTick) { actions.hasBatteryExemption() }
    val contactsOk = remember(resumeTick) { actions.hasContactsPermission() }
    val callOk = remember(resumeTick) { actions.hasCallPermission() }
    val assistant = remember(resumeTick) { actions.isAssistant() }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(stringResource(R.string.tab_settings), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)

            Section(stringResource(R.string.settings_section_model)) {
                if (providers.isEmpty()) Text(stringResource(R.string.settings_no_model), style = MaterialTheme.typography.bodyMedium)
                providers.forEach { p ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = p.id == activeId, onClick = { app.providers.setActive(p.id) })
                        Column(Modifier.weight(1f).clickable { app.providers.setActive(p.id) }) {
                            Text(p.name)
                            Text(
                                "${p.model.ifBlank { stringResource(R.string.model_not_picked) }} · ${if (p.kind == ProviderKind.ANTHROPIC_COMPATIBLE) "Anthropic" else "OpenAI"}-compatible",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (!app.providers.hasKey(p.id) && !isLocalUrl(p.baseUrl)) {
                                Text(
                                    stringResource(R.string.settings_no_key),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                        TextButton(onClick = { editing = p; adding = false }) { Text(stringResource(R.string.edit)) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { adding = true; editing = null }) { Text(stringResource(R.string.add_provider)) }
                    OutlinedButton(onClick = actions::loginOpenRouter) { Text(stringResource(R.string.login_openrouter)) }
                }
            }

            if (adding || editing != null) {
                key(editing?.id ?: "new") {
                    ProviderEditor(
                        app = app,
                        initial = editing,
                        onDone = { adding = false; editing = null },
                    )
                }
            }

            Section(stringResource(R.string.settings_section_appearance)) {
                LanguageSetting()
                AlphaSlider(
                    label = stringResource(R.string.settings_bubble_alpha),
                    value = bubbleAlpha,
                    range = AppearanceSettings.MIN_BUBBLE_IDLE_ALPHA..1f,
                    helper = stringResource(R.string.settings_bubble_alpha_desc),
                    onChange = app.appearance::setBubbleIdleAlpha,
                )
                AlphaSlider(
                    label = stringResource(R.string.settings_panel_alpha),
                    value = panelAlpha,
                    range = AppearanceSettings.MIN_PANEL_ALPHA..1f,
                    helper = stringResource(R.string.settings_panel_alpha_desc),
                    onChange = app.appearance::setPanelAlpha,
                )
            }

            Section(stringResource(R.string.settings_section_voice)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_speak_replies))
                        Text(
                            stringResource(R.string.settings_speak_replies_desc),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(checked = speakReplies, onCheckedChange = app.voiceSettings::setSpeakReplies)
                }
                speechProblem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Text(stringResource(R.string.settings_pick_voice), Modifier.padding(top = 4.dp))
                VoiceRow(stringResource(R.string.settings_voice_default), stringResource(R.string.settings_voice_default_desc), voice == null,
                    onSelect = { app.voiceSettings.setVoice(null) }, onPreview = { app.speaker.preview(null) })
                if (voices.isEmpty() && speechProblem == null) {
                    Text(stringResource(R.string.settings_voices_loading), style = MaterialTheme.typography.bodySmall)
                }
                voices.forEach { v ->
                    VoiceRow(
                        v.label,
                        if (v.online) stringResource(R.string.settings_voice_online) else stringResource(R.string.settings_voice_offline),
                        voice == v.name,
                        onSelect = { app.voiceSettings.setVoice(v.name) },
                        onPreview = { app.speaker.preview(v.name) },
                    )
                }
                VoiceSlider(stringResource(R.string.settings_voice_rate), rate, VoiceSettings.RATE_RANGE, app.voiceSettings::setRate)
                VoiceSlider(stringResource(R.string.settings_voice_pitch), pitch, VoiceSettings.PITCH_RANGE, app.voiceSettings::setPitch)
                Row {
                    TextButton(onClick = { app.speaker.speak(app.getString(R.string.voice_sample)) }) { Text(stringResource(R.string.settings_voice_test)) }
                    TextButton(onClick = { app.voiceSettings.setRate(1f); app.voiceSettings.setPitch(1f) }) { Text(stringResource(R.string.settings_voice_reset)) }
                }
                PermissionRow(stringResource(R.string.settings_assistant), assistant, stringResource(R.string.set_up), actions::openAssistantSettings)
                Text(
                    stringResource(R.string.settings_assistant_desc),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            WebSection(app)

            ConfirmSection(app)

            McpSection(app)

            VirtualScreenSection(app)

            Section(stringResource(R.string.settings_section_permissions)) {
                PermissionRow(stringResource(R.string.perm_overlay), overlayOk, stringResource(R.string.allow), actions::requestOverlayPermission)
                PermissionRow(stringResource(R.string.perm_a11y), a11y, stringResource(R.string.open), actions::openAccessibilitySettings)
                if (!a11y) {
                    Text(
                        stringResource(R.string.settings_restricted_hint),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = actions::openAppInfo) { Text(stringResource(R.string.open_app_info)) }
                }
                PermissionRow(stringResource(R.string.perm_notifications), notifOk, stringResource(R.string.allow), actions::requestNotificationPermission)
                PermissionRow(stringResource(R.string.perm_notification_access_media), notifAccess, stringResource(R.string.open), actions::openNotificationAccessSettings)
                Text(
                    stringResource(R.string.perm_notification_access_desc),
                    style = MaterialTheme.typography.bodySmall,
                )
                PermissionRow(stringResource(R.string.perm_battery), batteryOk, stringResource(R.string.allow), actions::requestBatteryExemption)
                val oem = Oem.current()
                if (!batteryOk || OemHints.showsSettingsButton(oem)) {
                    oem?.let { Text(stringResource(it.hint), style = MaterialTheme.typography.bodySmall) }
                    if (OemHints.showsSettingsButton(oem)) TextButton(onClick = actions::openOemSettings) { Text(stringResource(R.string.onb_open_phone_settings)) }
                }
                PermissionRow(stringResource(R.string.perm_mic), micOk, stringResource(R.string.allow), actions::requestMicPermission)
                PermissionRow(stringResource(R.string.perm_contacts), contactsOk, stringResource(R.string.allow), actions::requestContactsPermission)
                PermissionRow(stringResource(R.string.perm_call), callOk, stringResource(R.string.allow), actions::requestCallPermission)
                Text(
                    stringResource(R.string.perm_call_desc),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            DataSection(app)

            UpdateSection(app)

            Section(stringResource(R.string.settings_section_more)) {
                Text(
                    stringResource(R.string.settings_report_desc),
                    style = MaterialTheme.typography.bodySmall,
                )
                DebugLogSection()
                TextButton(onClick = { app.appearance.setOnboarded(false) }) { Text(stringResource(R.string.settings_redo_setup)) }
            }
        }
    }
}

/** Beka's own language on Android 13+; older versions follow the phone's. */
@Composable
private fun LanguageSetting() {
    val context = LocalContext.current
    Text(stringResource(R.string.settings_language), style = MaterialTheme.typography.labelLarge)
    if (!AppLanguage.canChoose) {
        Text(
            stringResource(R.string.settings_language_follows_phone),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    // Android recreates the screen in the new language, which reads this again.
    val chosen = remember { AppLanguage.chosen(context) }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AppLanguage.choices.forEach { lang ->
            FilterChip(
                selected = chosen == lang,
                onClick = { if (chosen != lang) AppLanguage.choose(context, lang) },
                label = {
                    Text(
                        when (lang) {
                            null -> stringResource(R.string.settings_language_phone)
                            Lang.EN -> "English"
                            Lang.ID -> "Bahasa Indonesia"
                        },
                    )
                },
            )
        }
    }
}

/** Export and import of memory, rules, app permissions and settings as one file. */
@Composable
private fun DataSection(app: BekaApp) {
    val scope = rememberCoroutineScope()
    val manager = remember { BackupManager(app) }
    var status by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<Backup?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            try {
                val summary = manager.export(uri)
                failed = false
                status = app.getString(R.string.backup_saved, summary.memory, summary.rules)
            } catch (e: Exception) {
                failed = true
                status = (e as? BackupException)?.message ?: app.getString(R.string.backup_save_failed)
            }
            busy = false
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            try {
                pending = manager.read(uri)
                status = null
            } catch (e: Exception) {
                failed = true
                status = (e as? BackupException)?.message ?: app.getString(R.string.backup_read_failed)
            }
            busy = false
        }
    }

    pending?.let { backup ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(stringResource(R.string.backup_import_q)) },
            text = {
                Text(
                    stringResource(R.string.backup_import_desc, backup.memory.size, backup.rules.size, backup.permissions.size, backup.providers.size, backup.mcpServers.size),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    scope.launch {
                        busy = true
                        try {
                            val r = manager.apply(backup)
                            failed = false
                            status = buildString {
                                append(app.getString(R.string.import_done, r.memoryAdded, r.memorySkipped, r.rulesAdded + r.rulesReplaced, r.permissions))
                                if (r.providers > 0 || r.mcpServers > 0) append(", ").append(app.getString(R.string.import_done_servers, r.providers, r.mcpServers))
                                append(".")
                                if (r.rulesSkipped > 0) append(" ").append(app.getString(R.string.import_rules_skipped, r.rulesSkipped))
                            }
                        } catch (e: Exception) {
                            failed = true
                            status = app.getString(R.string.backup_import_failed)
                        }
                        busy = false
                    }
                }) { Text(stringResource(R.string.backup_import)) }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    Section(stringResource(R.string.settings_section_data)) {
        Text(
            stringResource(R.string.settings_data_desc),
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { exportLauncher.launch("beka-backup-${LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)}.json") },
                enabled = !busy,
            ) { Text(stringResource(R.string.backup_export)) }
            OutlinedButton(
                onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                enabled = !busy,
            ) { Text(stringResource(R.string.backup_import)) }
        }
        status?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** App version, update channel and the install of a newer build. */
@Composable
private fun UpdateSection(app: BekaApp) {
    val settings = app.updateSettings
    val updater = app.updater
    val channel by settings.channel.collectAsState()
    val autoCheck by settings.autoCheck.collectAsState()
    val available by settings.available.collectAsState()
    val state by updater.state.collectAsState()
    val busy = state is UpdateState.Checking || state is UpdateState.Downloading || state is UpdateState.Installing
    Section(stringResource(R.string.settings_section_updates)) {
        Text(stringResource(R.string.settings_current_version, BuildConfig.VERSION_NAME))
        Text(stringResource(R.string.settings_channel), style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = channel == UpdateChannel.STABLE,
                onClick = { settings.setChannel(UpdateChannel.STABLE) },
                label = { Text(stringResource(R.string.channel_stable)) },
            )
            FilterChip(
                selected = channel == UpdateChannel.NIGHTLY,
                onClick = { settings.setChannel(UpdateChannel.NIGHTLY) },
                label = { Text("Nightly") },
            )
        }
        Text(
            if (channel == UpdateChannel.STABLE) stringResource(R.string.settings_channel_stable_desc)
            else stringResource(R.string.settings_channel_nightly_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_auto_check))
                Text(
                    stringResource(R.string.settings_auto_check_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = autoCheck, onCheckedChange = settings::setAutoCheck)
        }
        val status = updateStatus(state, available?.versionName)
        if (status != null) {
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                color = if (state is UpdateState.Error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
        }
        (state as? UpdateState.Downloading)?.let { d ->
            if (d.percent >= 0) LinearProgressIndicator(progress = { d.percent / 100f }, modifier = Modifier.fillMaxWidth())
            else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = updater::checkNow, enabled = !busy) { Text(stringResource(R.string.settings_check_now)) }
            if (available != null) Button(onClick = updater::install, enabled = !busy) { Text(stringResource(R.string.install)) }
        }
        if (available != null) {
            Text(
                stringResource(R.string.settings_install_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun updateStatus(state: UpdateState, availableName: String?): String? = when (state) {
    UpdateState.Idle -> availableName?.let { stringResource(R.string.update_available, it) }
    UpdateState.Checking -> stringResource(R.string.update_checking)
    is UpdateState.Downloading -> if (state.percent >= 0) stringResource(R.string.update_downloading_pct, state.percent) else stringResource(R.string.update_downloading)
    UpdateState.Installing -> stringResource(R.string.update_opening_installer)
    is UpdateState.Info -> state.text
    is UpdateState.Error -> state.text
}

/** Debug recording (on in debug builds) and every report made so far. */
@Composable
private fun DebugLogSection() {
    val context = LocalContext.current
    val recording by dev.beka.debug.AppLog.enabled.collectAsState()
    val reports = remember { dev.beka.debug.DebugReporter.reports(context) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.settings_record_log))
            Text(
                stringResource(R.string.settings_record_log_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = recording, onCheckedChange = { dev.beka.debug.AppLog.setEnabled(context, it) })
    }
    Text(stringResource(R.string.settings_reports, reports.size), style = MaterialTheme.typography.labelLarge)
    if (reports.isEmpty()) {
        Text(stringResource(R.string.settings_no_reports), style = MaterialTheme.typography.bodySmall)
    } else {
        reports.take(20).forEach { r ->
            Text("${r.stamp} · ${r.note}", style = MaterialTheme.typography.bodySmall, maxLines = 2)
        }
        if (reports.size > 20) Text(stringResource(R.string.settings_more_reports, reports.size - 20), style = MaterialTheme.typography.bodySmall)
    }
}

/** Servers on this phone or the home network usually run without a key. */
private fun isLocalUrl(url: String): Boolean {
    val host = url.substringAfter("://").substringBefore('/').substringBefore(':')
    val second = host.removePrefix("172.").substringBefore('.').toIntOrNull()
    return host == "localhost" || host.endsWith(".local") || host.startsWith("127.") || host.startsWith("192.168.") ||
        host.startsWith("10.") || (host.startsWith("172.") && second != null && second in 16..31)
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    PanelCard(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun AlphaSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, helper: String, onChange: (Float) -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f))
            Text("${(value * 100).roundToInt()}%", style = MaterialTheme.typography.labelLarge)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range)
        Text(helper, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun VoiceRow(label: String, detail: String, selected: Boolean, onSelect: () -> Unit, onPreview: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(Modifier.weight(1f).clickable(onClick = onSelect)) {
            Text(label)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = onPreview) { Text(stringResource(R.string.voice_listen)) }
    }
}

@Composable
private fun VoiceSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f))
            Text("%.1f×".format(value), style = MaterialTheme.typography.labelLarge)
        }
        Slider(value = value, onValueChange = { onChange((it * 10).roundToInt() / 10f) }, valueRange = range)
    }
}

@Composable
private fun PermissionRow(label: String, ok: Boolean, action: String, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text((if (ok) "✓ " else "✗ ") + label, Modifier.weight(1f))
        if (!ok) TextButton(onClick = onClick) { Text(action) }
    }
}

@Composable
private fun ProviderEditor(app: BekaApp, initial: ProviderConfig?, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var kind by remember { mutableStateOf(initial?.kind ?: ProviderKind.OPENAI_COMPATIBLE) }
    var baseUrl by remember { mutableStateOf(initial?.baseUrl ?: "") }
    var model by remember { mutableStateOf(initial?.model ?: "") }
    var apiKey by remember { mutableStateOf("") }
    var promptCache by remember { mutableStateOf(initial?.promptCache) }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var status by remember { mutableStateOf<String?>(null) }
    val id = remember { initial?.id ?: UUID.randomUUID().toString() }

    fun draft() = ProviderConfig(
        id, name.ifBlank { "Provider" }, kind, baseUrl.trim(), model.trim(),
        extraHeaders = initial?.extraHeaders.orEmpty(),
        promptCache = promptCache,
    )
    fun keyOrStored() = apiKey.ifBlank { null } ?: app.providers.apiKey(id)

    var confirmDelete by remember { mutableStateOf(false) }
    if (confirmDelete && initial != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.provider_delete_q, initial.name)) },
            text = { Text(stringResource(R.string.provider_delete_desc)) },
            confirmButton = { TextButton(onClick = { confirmDelete = false; app.providers.delete(initial.id); onDone() }) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    Section(if (initial == null) stringResource(R.string.add_provider) else stringResource(R.string.provider_edit, initial.name)) {
        if (initial == null) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Presets.all.forEach { preset ->
                    FilterChip(
                        selected = name == preset.name,
                        onClick = {
                            // A model the user typed stays; only a blank or still-default one is replaced.
                            val previousDefault = Presets.all.firstOrNull { it.name == name }?.model
                            if (model.isBlank() || model == previousDefault) model = preset.model
                            name = preset.name
                            kind = preset.kind
                            baseUrl = preset.baseUrl
                        },
                        label = { Text(preset.name) },
                    )
                }
            }
        }
        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.provider_name)) }, singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(kind == ProviderKind.OPENAI_COMPATIBLE, { kind = ProviderKind.OPENAI_COMPATIBLE }, { Text("OpenAI-compatible") })
            FilterChip(kind == ProviderKind.ANTHROPIC_COMPATIBLE, { kind = ProviderKind.ANTHROPIC_COMPATIBLE }, { Text("Anthropic-compatible") })
        }
        OutlinedTextField(
            baseUrl, { baseUrl = it }, Modifier.fillMaxWidth(),
            label = { Text("Base URL") },
            supportingText = {
                if (unencryptedRemote(baseUrl)) Text(
                    stringResource(R.string.provider_http_warning),
                    color = MaterialTheme.colorScheme.error,
                )
                else Text(if (kind == ProviderKind.OPENAI_COMPATIBLE) stringResource(R.string.provider_url_hint_openai) else stringResource(R.string.provider_url_hint_anthropic))
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Prompt caching (Claude)")
                Text(
                    stringResource(R.string.provider_cache_desc),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = promptCache ?: draft().cachingEnabled, onCheckedChange = { promptCache = it })
        }
        OutlinedTextField(
            apiKey, { apiKey = it }, Modifier.fillMaxWidth(),
            label = { Text("API key") },
            placeholder = { if (initial != null && app.providers.hasKey(id)) Text(stringResource(R.string.provider_key_saved)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(model, { model = it }, Modifier.weight(1f), label = { Text("Model") }, singleLine = true)
            Spacer(Modifier.size(8.dp))
            OutlinedButton(onClick = {
                status = app.getString(R.string.provider_fetching_models)
                scope.launch {
                    runCatching { app.providers.client(draft(), keyOrStored()).listModels() }
                        .onSuccess { models = it.map { m -> m.id }; status = app.getString(R.string.provider_models_count, it.size) }
                        .onFailure { status = app.getString(R.string.chat_failed, friendlyError(it)) }
                }
            }) { Text(stringResource(R.string.provider_list)) }
        }
        val filtered = models.filter { model.isBlank() || it.contains(model, ignoreCase = true) }.take(30)
        if (filtered.isNotEmpty() && model !in models) {
            Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                filtered.forEach { m ->
                    Text(
                        m,
                        Modifier.fillMaxWidth().clickable { model = m }.padding(vertical = 6.dp),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    HorizontalDivider()
                }
            }
        }
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                status = app.getString(R.string.provider_testing)
                scope.launch {
                    val reply = StringBuilder()
                    runCatching {
                        val request = ChatRequest(model.trim(), null, listOf(Message.user("Reply with exactly: OK")), maxTokens = 32)
                        app.providers.client(draft(), keyOrStored()).chat(request).collect { e ->
                            if (e is ChatEvent.TextDelta) reply.append(e.text)
                        }
                    }
                        .onSuccess {
                            val text = reply.toString().trim()
                            status = if (text.isEmpty()) app.getString(R.string.provider_test_empty) else app.getString(R.string.provider_test_ok, text.take(80))
                        }
                        .onFailure { status = app.getString(R.string.chat_failed, friendlyError(it)) }
                }
            }) { Text(stringResource(R.string.provider_test)) }
            Button(onClick = {
                app.providers.save(draft(), apiKey.ifBlank { null })
                onDone()
            }, enabled = baseUrl.isNotBlank() && model.isNotBlank()) { Text(stringResource(R.string.save)) }
            if (initial != null) {
                TextButton(onClick = { confirmDelete = true }) { Text(stringResource(R.string.delete)) }
            }
            TextButton(onClick = onDone) { Text(stringResource(R.string.cancel)) }
        }
    }
}

@Composable
private fun WebSection(app: BekaApp) {
    val scope = rememberCoroutineScope()
    val enabled by app.webSettings.enabled.collectAsState()
    val provider by app.webSettings.provider.collectAsState()
    val withKey by app.webSettings.providersWithKey.collectAsState()
    var keyInput by remember(provider) { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    val needsKey = provider != DUCKDUCKGO

    Section("Web") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.web_search_toggle))
                Text(
                    stringResource(R.string.web_search_desc),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = enabled, onCheckedChange = app.webSettings::setEnabled)
        }
        if (enabled) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    TINYFISH to stringResource(R.string.web_tinyfish),
                    BRAVE to "Brave",
                    TAVILY to "Tavily",
                    DUCKDUCKGO to stringResource(R.string.web_ddg),
                ).forEach { (id, label) ->
                    FilterChip(selected = provider == id, onClick = { app.webSettings.setProvider(id); status = null }, label = { Text(label) })
                }
            }
            if (needsKey) {
                OutlinedTextField(
                    keyInput, { keyInput = it }, Modifier.fillMaxWidth(),
                    label = { Text("API key") },
                    placeholder = { if (provider in withKey) Text(stringResource(R.string.provider_key_saved)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                Text(
                    when (provider) {
                        TINYFISH -> stringResource(R.string.web_key_tinyfish)
                        BRAVE -> stringResource(R.string.web_key_brave)
                        else -> stringResource(R.string.web_key_tavily)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    stringResource(R.string.web_ddg_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (needsKey) {
                    Button(
                        onClick = { app.webSettings.setApiKey(provider, keyInput); keyInput = ""; status = app.getString(R.string.web_key_saved) },
                        enabled = keyInput.isNotBlank(),
                    ) { Text(stringResource(R.string.web_save_key)) }
                    if (provider in withKey) {
                        TextButton(onClick = { app.webSettings.setApiKey(provider, ""); status = app.getString(R.string.web_key_deleted) }) { Text(stringResource(R.string.web_delete_key)) }
                    }
                }
                OutlinedButton(onClick = {
                    if (keyInput.isNotBlank()) { app.webSettings.setApiKey(provider, keyInput); keyInput = "" }
                    status = app.getString(R.string.provider_testing)
                    scope.launch {
                        runCatching { app.webSettings.search(app.http).search("cuaca Jakarta hari ini", 5) }
                            .onSuccess { status = app.getString(R.string.web_test_ok, it.results.size) + (it.note?.let { n -> "\n$n" } ?: "") }
                            .onFailure { status = app.getString(R.string.chat_failed, it.message) }
                    }
                }) { Text(stringResource(R.string.provider_test)) }
            }
        }
    }
}

@Composable
private fun ConfirmSection(app: BekaApp) {
    val settings = app.confirmSettings
    val ask by settings.askGlobal.collectAsState()
    val overrides by settings.overrides.collectAsState()
    val pm = LocalContext.current.packageManager
    // Label for each overridden app; an uninstalled one falls back to its package name.
    fun labelOf(pkg: String) = runCatching { pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString() }.getOrDefault(pkg)
    var confirmOff by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }

    Section(stringResource(R.string.confirm_section)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.confirm_ask))
                Text(
                    stringResource(R.string.confirm_ask_desc),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = ask, onCheckedChange = { if (it) settings.setAskGlobal(true) else confirmOff = true })
        }
        Text(stringResource(R.string.confirm_per_app), style = MaterialTheme.typography.labelLarge)
        if (overrides.isEmpty()) {
            Text(
                stringResource(R.string.confirm_per_app_none),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        overrides.entries.sortedBy { labelOf(it.key).lowercase() }.forEach { (pkg, choice) ->
            Column {
                Text(labelOf(pkg))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = choice == AppConfirm.ASK, onClick = { settings.setOverride(pkg, AppConfirm.ASK) }, label = { Text(stringResource(R.string.confirm_always_ask)) })
                    FilterChip(selected = choice == AppConfirm.SKIP, onClick = { settings.setOverride(pkg, AppConfirm.SKIP) }, label = { Text(stringResource(R.string.confirm_just_run)) })
                    TextButton(onClick = { settings.setOverride(pkg, null) }) { Text(stringResource(R.string.delete)) }
                }
            }
        }
        OutlinedButton(onClick = { picking = true }) { Text(stringResource(R.string.confirm_add_app)) }
        Text(
            stringResource(R.string.confirm_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (confirmOff) {
        AlertDialog(
            onDismissRequest = { confirmOff = false },
            title = { Text(stringResource(R.string.confirm_off_q)) },
            text = {
                Text(
                    stringResource(R.string.confirm_off_desc),
                )
            },
            confirmButton = { TextButton(onClick = { settings.setAskGlobal(false); confirmOff = false }) { Text(stringResource(R.string.confirm_off)) } },
            dismissButton = { TextButton(onClick = { confirmOff = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    if (picking) {
        val ownPkg = LocalContext.current.packageName
        var query by remember { mutableStateOf("") }
        val apps = remember {
            AppResolver.launcherApps(pm)
                .filter { !Blocklist.isBlocked(it.pkg) && it.pkg != ownPkg }
                .sortedBy { it.label.lowercase() }
        }
        val shown = apps.filter { it.pkg !in overrides && (query.isBlank() || it.label.contains(query, ignoreCase = true)) }
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text(stringResource(R.string.confirm_pick_app)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.confirm_search_app)) }, singleLine = true)
                    Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                        shown.forEach { a ->
                            Text(
                                a.label,
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        settings.setOverride(a.pkg, if (ask) AppConfirm.SKIP else AppConfirm.ASK)
                                        picking = false
                                    }
                                    .padding(vertical = 10.dp),
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { picking = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun McpSection(app: BekaApp) {
    val scope = rememberCoroutineScope()
    val servers by app.mcpSettings.servers.collectAsState()
    var adding by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<McpServer?>(null) }

    removing?.let { target ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.mcp_delete_q, target.name)) },
            confirmButton = { TextButton(onClick = { app.mcpSettings.remove(target.id); removing = null }) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    Section("MCP") {
        Text(
            stringResource(R.string.mcp_desc),
            style = MaterialTheme.typography.bodySmall,
        )
        if (servers.isEmpty()) {
            Text(stringResource(R.string.mcp_none), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        servers.forEach { s ->
            key(s.id) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s.name)
                            Text(s.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            when {
                                s.refreshing -> Text(stringResource(R.string.mcp_loading), style = MaterialTheme.typography.bodySmall)
                                s.error != null -> Text(s.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                                else -> Text(stringResource(R.string.mcp_tools, s.tools.size), style = MaterialTheme.typography.bodySmall)
                            }
                            if (s.error != null && !s.refreshing && s.tools.isNotEmpty()) {
                                Text(stringResource(R.string.mcp_old_list, s.tools.size), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Switch(checked = s.enabled, onCheckedChange = { app.mcpSettings.setEnabled(s.id, it) })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !s.autoRun, onClick = { app.mcpSettings.setAutoRun(s.id, false) }, label = { Text(stringResource(R.string.mcp_ask_first)) })
                        FilterChip(selected = s.autoRun, onClick = { app.mcpSettings.setAutoRun(s.id, true) }, label = { Text(stringResource(R.string.confirm_just_run)) })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { scope.launch { app.mcpSettings.refresh(s.id) } }, enabled = !s.refreshing) { Text(stringResource(R.string.mcp_reload)) }
                        TextButton(onClick = { removing = s }) { Text(stringResource(R.string.delete)) }
                    }
                }
                HorizontalDivider()
            }
        }
        Text(
            stringResource(R.string.mcp_readonly_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = { adding = true }) { Text(stringResource(R.string.mcp_add_server)) }
    }

    if (adding) {
        var name by remember { mutableStateOf("") }
        var url by remember { mutableStateOf("") }
        var headerName by remember { mutableStateOf("Authorization") }
        var headerValue by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text(stringResource(R.string.mcp_add_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.provider_name)) }, singleLine = true)
                    OutlinedTextField(
                        url, { url = it }, Modifier.fillMaxWidth(), label = { Text("URL") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    )
                    OutlinedTextField(headerName, { headerName = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.mcp_header)) }, singleLine = true)
                    OutlinedTextField(
                        headerValue, { headerValue = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.mcp_value)) }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                    Text(
                        stringResource(R.string.mcp_example),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val id = app.mcpSettings.add(name, url, headerName, headerValue)
                        adding = false
                        scope.launch { app.mcpSettings.refresh(id) }
                    },
                    enabled = url.trim().startsWith("http://") || url.trim().startsWith("https://"),
                ) { Text(stringResource(R.string.add)) }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** Fase 9 step 1: connect the shell display host and try the virtual display by hand. */
@Composable
private fun VirtualScreenSection(app: BekaApp) {
    val screen = app.virtualScreen
    val status by screen.status.collectAsState()
    val frame by screen.frame.collectAsState()
    val testLine by screen.testLine.collectAsState()
    var note by remember { mutableStateOf<String?>(null) }
    var previewing by remember { mutableStateOf(false) }
    if (previewing) DisposableEffect(Unit) {
        val handle = screen.watch()
        onDispose { handle.close() }
    }
    // What these test buttons open is released when the screen is left, unless a task or the lock test uses it.
    DisposableEffect(Unit) {
        onDispose { if (!app.chat.busy.value && !screen.testing) screen.close() }
    }

    val context = LocalContext.current
    val pairing by app.adbPairing.state.collectAsState()
    // Re-read after pairing finishes (the state line changes then).
    val paired = remember(pairing, status) { app.adbLink.paired }

    Section(stringResource(R.string.vs_section)) {
        Text(
            stringResource(R.string.vs_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            when (status) {
                VirtualScreen.Status.NO_LAUNCHER -> stringResource(R.string.vs_not_set_up)
                VirtualScreen.Status.NEED_PERMISSION -> stringResource(R.string.vs_need_shizuku)
                VirtualScreen.Status.CONNECTING -> stringResource(R.string.vs_connecting)
                VirtualScreen.Status.READY -> screen.displayId?.let { stringResource(R.string.vs_connected_display, it) } ?: stringResource(R.string.vs_connected)
                VirtualScreen.Status.FAILED -> if (paired) stringResource(R.string.vs_not_connected_wifi) else stringResource(R.string.vs_not_connected)
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        // Shown even while connected through Shizuku: pairing makes Beka independent of it.
        if (!paired) {
            Text(
                stringResource(R.string.vs_pair_steps),
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    app.adbPairing.start()
                    // Opens developer options scrolled to Wireless debugging.
                    val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                        .putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(intent) }
                        .onFailure { note = app.getString(R.string.vs_no_dev_options) }
                }) { Text(stringResource(R.string.vs_pairing)) }
                if (pairing != null) TextButton(onClick = { app.adbPairing.cancel() }) { Text(stringResource(R.string.cancel)) }
            }
            pairing?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (status != VirtualScreen.Status.READY) {
                Text(stringResource(R.string.vs_shizuku_too), style = MaterialTheme.typography.bodySmall)
            }
        }
        if (status != VirtualScreen.Status.READY) {
            OutlinedButton(onClick = { screen.connect() }) { Text(if (paired) stringResource(R.string.vs_connect) else stringResource(R.string.vs_connect_shizuku)) }
        } else {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    val intent = app.packageManager.getLaunchIntentForPackage("com.android.settings")
                        ?: Intent(android.provider.Settings.ACTION_SETTINGS)
                    note = screen.launch(intent) ?: app.getString(R.string.vs_opened)
                    previewing = true
                }) { Text(stringResource(R.string.vs_open_settings)) }
                OutlinedButton(onClick = { note = "Back: ${screen.key(KeyEvent.KEYCODE_BACK)}" }) { Text("Back") }
                OutlinedButton(onClick = {
                    val pkg = screen.packages().firstOrNull()
                    note = if (pkg == null) app.getString(R.string.vs_empty) else app.getString(R.string.vs_moved, pkg, screen.moveToMain(pkg))
                }) { Text(stringResource(R.string.vs_to_main)) }
                OutlinedButton(onClick = { screen.close(); previewing = false; note = app.getString(R.string.vs_closed) }) { Text(stringResource(R.string.close)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { previewing = !previewing }) { Text(if (previewing) stringResource(R.string.vs_hide_preview) else stringResource(R.string.vs_preview)) }
                OutlinedButton(onClick = { screen.startLockTest() }) { Text(stringResource(R.string.vs_lock_test)) }
            }
            Text(
                stringResource(R.string.vs_lock_test_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        (testLine ?: note)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        frame?.let { Image(it.asImageBitmap(), contentDescription = stringResource(R.string.vs_preview_desc), Modifier.fillMaxWidth(0.6f)) }
    }
}

/**
 * A plain-http base URL whose host is not on this phone or a private network (judged from the text alone, no lookup):
 * cleartext stays allowed for local model servers, but keys sent this way cross the internet unencrypted.
 */
private fun unencryptedRemote(baseUrl: String): Boolean {
    val uri = runCatching { java.net.URI(baseUrl.trim()) }.getOrNull() ?: return false
    if (!uri.scheme.equals("http", ignoreCase = true)) return false
    val host = uri.host?.lowercase()?.trim('[', ']') ?: return false
    if (host == "localhost" || '.' !in host && ':' !in host) return false
    if (host.endsWith(".local") || host.endsWith(".lan") || host.endsWith(".home.arpa") || host.endsWith(".ts.net")) return false
    if (host == "::1" || host.startsWith("fe80:") || host.startsWith("fd") || host.startsWith("fc")) return false
    val ip = host.split('.').mapNotNull { it.toIntOrNull() }.takeIf { it.size == 4 } ?: return true
    return !(ip[0] == 127 || ip[0] == 10 || (ip[0] == 192 && ip[1] == 168) || (ip[0] == 172 && ip[1] in 16..31) ||
        (ip[0] == 100 && ip[1] in 64..127) || (ip[0] == 169 && ip[1] == 254))
}
