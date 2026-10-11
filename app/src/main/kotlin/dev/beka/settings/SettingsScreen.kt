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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.beka.BekaApp
import dev.beka.BuildConfig
import dev.beka.SettingsActions
import dev.beka.accessibility.AppResolver
import dev.beka.accessibility.BekaAccessibilityService
import dev.beka.data.Backup
import dev.beka.data.BackupException
import dev.beka.data.BackupManager
import dev.beka.agent.Blocklist
import dev.beka.llm.ChatEvent
import dev.beka.llm.ChatRequest
import dev.beka.llm.Message
import dev.beka.llm.Presets
import dev.beka.llm.ProviderConfig
import dev.beka.llm.ProviderKind
import dev.beka.display.VirtualScreen
import dev.beka.update.UpdateChannel
import dev.beka.update.UpdateState
import dev.beka.ui.PanelCard
import dev.beka.voice.Speaker
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
            Text("Setelan", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)

            Section("Model AI") {
                if (providers.isEmpty()) Text("Belum ada provider.", style = MaterialTheme.typography.bodyMedium)
                providers.forEach { p ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = p.id == activeId, onClick = { app.providers.setActive(p.id) })
                        Column(Modifier.weight(1f).clickable { app.providers.setActive(p.id) }) {
                            Text(p.name)
                            Text(
                                "${p.model.ifBlank { "(model belum dipilih)" }} · ${if (p.kind == ProviderKind.ANTHROPIC_COMPATIBLE) "Anthropic" else "OpenAI"}-compatible",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (!app.providers.hasKey(p.id) && !isLocalUrl(p.baseUrl)) {
                                Text(
                                    "API key belum diisi",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                        TextButton(onClick = { editing = p; adding = false }) { Text("Edit") }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { adding = true; editing = null }) { Text("Tambah provider") }
                    OutlinedButton(onClick = actions::loginOpenRouter) { Text("Login OpenRouter") }
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

            Section("Tampilan") {
                AlphaSlider(
                    label = "Transparansi bubble saat diam",
                    value = bubbleAlpha,
                    range = AppearanceSettings.MIN_BUBBLE_IDLE_ALPHA..1f,
                    helper = "Bubble memudar setelah 3 detik tidak disentuh. 100% = tidak memudar.",
                    onChange = app.appearance::setBubbleIdleAlpha,
                )
                AlphaSlider(
                    label = "Background panel",
                    value = panelAlpha,
                    range = AppearanceSettings.MIN_PANEL_ALPHA..1f,
                    helper = "Teks tetap jelas; di bawah 100% panel sedikit tembus pandang.",
                    onChange = app.appearance::setPanelAlpha,
                )
            }

            Section("Suara") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Bacakan jawaban")
                        Text(
                            "Kalau kamu bertanya pakai suara, jawabannya dibacakan. Tap mic atau ■ buat memotong.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(checked = speakReplies, onCheckedChange = app.voiceSettings::setSpeakReplies)
                }
                Text("Pilih suara", Modifier.padding(top = 4.dp))
                VoiceRow("Bawaan HP", "Suara Indonesia default dari engine TTS", voice == null,
                    onSelect = { app.voiceSettings.setVoice(null) }, onPreview = { app.speaker.preview(null) })
                if (voices.isEmpty()) {
                    Text("Memuat daftar suara…", style = MaterialTheme.typography.bodySmall)
                }
                voices.forEach { v ->
                    VoiceRow(
                        v.label,
                        if (v.online) "Online · lebih natural, butuh internet" else "Offline",
                        voice == v.name,
                        onSelect = { app.voiceSettings.setVoice(v.name) },
                        onPreview = { app.speaker.preview(v.name) },
                    )
                }
                VoiceSlider("Kecepatan", rate, VoiceSettings.RATE_RANGE, app.voiceSettings::setRate)
                VoiceSlider("Nada", pitch, VoiceSettings.PITCH_RANGE, app.voiceSettings::setPitch)
                Row {
                    TextButton(onClick = { app.speaker.speak(Speaker.SAMPLE) }) { Text("Tes suara") }
                    TextButton(onClick = { app.voiceSettings.setRate(1f); app.voiceSettings.setPitch(1f) }) { Text("Reset kecepatan & nada") }
                }
                PermissionRow("Asisten default (tahan tombol power)", assistant, "Atur", actions::openAssistantSettings)
                Text(
                    "Pilih Beka di \"Aplikasi asisten digital\". Lalu tahan tombol power (atur di Setelan HP → Tombol power) atau tombol headset: Beka langsung mendengarkan.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            WebSection(app)

            ConfirmSection(app)

            McpSection(app)

            VirtualScreenSection(app)

            Section("Izin") {
                PermissionRow("Tampil di atas app lain", overlayOk, "Izinkan", actions::requestOverlayPermission)
                PermissionRow("Accessibility (baca layar)", a11y, "Buka", actions::openAccessibilitySettings)
                if (!a11y) {
                    Text(
                        "Kalau Beka tidak bisa dinyalakan (\"Restricted setting\"): App info → ⋮ → Allow restricted settings, lalu coba lagi.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = actions::openAppInfo) { Text("Buka App info") }
                }
                PermissionRow("Notifikasi", notifOk, "Izinkan", actions::requestNotificationPermission)
                PermissionRow("Akses notifikasi (info & kontrol media)", notifAccess, "Buka", actions::openNotificationAccessSettings)
                Text(
                    "Dipakai untuk tahu lagu/video apa yang sedang diputar, dan untuk rule notifikasi. Isi notifikasi hanya dibaca dari app yang punya rule notifikasi.",
                    style = MaterialTheme.typography.bodySmall,
                )
                PermissionRow("Mikrofon (perintah suara)", micOk, "Izinkan", actions::requestMicPermission)
                PermissionRow("Kontak (cari nomor)", contactsOk, "Izinkan", actions::requestContactsPermission)
                PermissionRow("Telepon (langsung menelepon)", callOk, "Izinkan", actions::requestCallPermission)
                Text(
                    "Tanpa izin telepon, Beka hanya membuka dialer dengan nomornya.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            DataSection(app)

            UpdateSection(app)

            Section("Lainnya") {
                Text(
                    "⋮ → \"Laporkan\" di panel membuka form, menyimpan bundel + zip ke Download/beka-debug/, lalu membuka issue GitHub yang sudah terisi.",
                    style = MaterialTheme.typography.bodySmall,
                )
                DebugLogSection()
                TextButton(onClick = { app.appearance.setOnboarded(false) }) { Text("Ulangi setup awal") }
            }
        }
    }
}

/** Export and import of memory, rules, app permissions and settings as one file. */
@Composable
private fun DataSection(app: BekaApp) {
    val context = LocalContext.current
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
                val out = context.contentResolver.openOutputStream(uri, "wt") ?: throw BackupException("Tidak bisa menulis ke file itu.")
                val summary = out.use { manager.export(it) }
                failed = false
                status = "Tersimpan: ${summary.memory} memori, ${summary.rules} rule."
            } catch (e: Exception) {
                failed = true
                status = (e as? BackupException)?.message ?: "Gagal menyimpan cadangan."
            }
            busy = false
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            try {
                val input = context.contentResolver.openInputStream(uri) ?: throw BackupException("Tidak bisa membuka file itu.")
                pending = input.use { manager.read(it) }
                status = null
            } catch (e: Exception) {
                failed = true
                status = (e as? BackupException)?.message ?: "Gagal membaca file."
            }
            busy = false
        }
    }

    pending?.let { backup ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Impor cadangan?") },
            text = {
                Text(
                    "${backup.memory.size} memori, ${backup.rules.size} rule, ${backup.permissions.size} izin app, dan setelan. " +
                        "Data yang sudah ada tetap ada; setelan yang sama ditimpa.",
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
                                append("Impor selesai: ${r.memoryAdded} memori baru (${r.memorySkipped} sudah ada), ")
                                append("${r.rulesAdded + r.rulesReplaced} rule, ${r.permissions} izin app")
                                if (r.providers > 0 || r.mcpServers > 0) append(", ${r.providers} provider, ${r.mcpServers} server MCP")
                                append(".")
                                if (r.rulesSkipped > 0) append(" ${r.rulesSkipped} rule tidak terbaca.")
                            }
                        } catch (e: Exception) {
                            failed = true
                            status = "Gagal mengimpor cadangan."
                        }
                        busy = false
                    }
                }) { Text("Impor") }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Batal") } },
        )
    }

    Section("Data") {
        Text(
            "Simpan memori, rule, izin app dan setelan ke satu file, untuk pindah HP atau pasang ulang. " +
                "API key tidak ikut; masukkan lagi atau login OpenRouter setelah impor.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { exportLauncher.launch("beka-cadangan-${LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)}.json") },
                enabled = !busy,
            ) { Text("Ekspor") }
            OutlinedButton(
                onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                enabled = !busy,
            ) { Text("Impor") }
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
    Section("Pembaruan") {
        Text("Versi sekarang: v${BuildConfig.VERSION_NAME}")
        Text("Saluran", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = channel == UpdateChannel.STABLE,
                onClick = { settings.setChannel(UpdateChannel.STABLE) },
                label = { Text("Stabil") },
            )
            FilterChip(
                selected = channel == UpdateChannel.NIGHTLY,
                onClick = { settings.setChannel(UpdateChannel.NIGHTLY) },
                label = { Text("Nightly") },
            )
        }
        Text(
            if (channel == UpdateChannel.STABLE) "Stabil: hanya rilis yang sudah dicoba."
            else "Nightly: build otomatis dari setiap perubahan, bisa belum stabil.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Cek otomatis")
                Text(
                    "Cek sekali sehari saat Beka dibuka.",
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
            OutlinedButton(onClick = updater::checkNow, enabled = !busy) { Text("Cek sekarang") }
            if (available != null) Button(onClick = updater::install, enabled = !busy) { Text("Pasang") }
        }
        if (available != null) {
            Text(
                "Beka akan berhenti sebentar saat dipasang, lalu bubble muncul lagi.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun updateStatus(state: UpdateState, availableName: String?): String? = when (state) {
    UpdateState.Idle -> availableName?.let { "Versi $it tersedia" }
    UpdateState.Checking -> "Mengecek…"
    is UpdateState.Downloading -> if (state.percent >= 0) "Mengunduh… ${state.percent}%" else "Mengunduh…"
    UpdateState.Installing -> "Membuka pemasangan… konfirmasi di layar yang muncul."
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
            Text("Rekam log debug")
            Text(
                "Semua aktivitas Beka disimpan di HP (maks ~24 MB) dan ikut masuk laporan. Matikan kalau tidak sedang mencari bug.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = recording, onCheckedChange = { dev.beka.debug.AppLog.setEnabled(context, it) })
    }
    Text("Laporan (${reports.size})", style = MaterialTheme.typography.labelLarge)
    if (reports.isEmpty()) {
        Text("Belum ada laporan.", style = MaterialTheme.typography.bodySmall)
    } else {
        reports.take(20).forEach { r ->
            Text("${r.stamp} · ${r.note}", style = MaterialTheme.typography.bodySmall, maxLines = 2)
        }
        if (reports.size > 20) Text("…dan ${reports.size - 20} lagi di Download/beka-debug/", style = MaterialTheme.typography.bodySmall)
    }
}

/** Servers on this phone or the home network usually run without a key. */
private fun isLocalUrl(url: String): Boolean {
    val host = url.substringAfter("://").substringBefore('/').substringBefore(':')
    return host == "localhost" || host.startsWith("127.") || host.startsWith("192.168.") || host.startsWith("10.")
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
        TextButton(onClick = onPreview) { Text("▶ Dengar") }
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

    Section(if (initial == null) "Tambah provider" else "Edit ${initial.name}") {
        if (initial == null) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Presets.all.forEach { preset ->
                    FilterChip(
                        selected = name == preset.name,
                        onClick = {
                            name = preset.name
                            kind = preset.kind
                            baseUrl = preset.baseUrl
                            model = preset.model
                        },
                        label = { Text(preset.name) },
                    )
                }
            }
        }
        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Nama") }, singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(kind == ProviderKind.OPENAI_COMPATIBLE, { kind = ProviderKind.OPENAI_COMPATIBLE }, { Text("OpenAI-compatible") })
            FilterChip(kind == ProviderKind.ANTHROPIC_COMPATIBLE, { kind = ProviderKind.ANTHROPIC_COMPATIBLE }, { Text("Anthropic-compatible") })
        }
        OutlinedTextField(
            baseUrl, { baseUrl = it }, Modifier.fillMaxWidth(),
            label = { Text("Base URL") },
            supportingText = {
                if (unencryptedRemote(baseUrl)) Text(
                    "Alamat http:// di internet: API key dan isi obrolan terkirim tanpa enkripsi. Pakai https://.",
                    color = MaterialTheme.colorScheme.error,
                )
                else Text(if (kind == ProviderKind.OPENAI_COMPATIBLE) "Termasuk /v1, mis. https://api.openai.com/v1" else "Tanpa /v1, mis. https://api.anthropic.com")
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Prompt caching (Claude)")
                Text(
                    "Riwayat langkah agent ditagih jauh lebih murah. Otomatis untuk api.anthropic.com; CLIProxyAPI sudah menambahkannya sendiri.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = promptCache ?: draft().cachingEnabled, onCheckedChange = { promptCache = it })
        }
        OutlinedTextField(
            apiKey, { apiKey = it }, Modifier.fillMaxWidth(),
            label = { Text("API key") },
            placeholder = { if (initial != null && app.providers.hasKey(id)) Text("(tersimpan — kosongkan untuk tetap)") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(model, { model = it }, Modifier.weight(1f), label = { Text("Model") }, singleLine = true)
            Spacer(Modifier.size(8.dp))
            OutlinedButton(onClick = {
                status = "Mengambil daftar model…"
                scope.launch {
                    runCatching { app.providers.client(draft(), keyOrStored()).listModels() }
                        .onSuccess { models = it.map { m -> m.id }; status = "${it.size} model" }
                        .onFailure { status = "Gagal: ${it.message}" }
                }
            }) { Text("Daftar") }
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
                status = "Mengetes…"
                scope.launch {
                    val reply = StringBuilder()
                    runCatching {
                        val request = ChatRequest(model.trim(), null, listOf(Message.user("Reply with exactly: OK")), maxTokens = 32)
                        app.providers.client(draft(), keyOrStored()).chat(request).collect { e ->
                            if (e is ChatEvent.TextDelta) reply.append(e.text)
                        }
                    }
                        .onSuccess { status = "Berhasil: \"${reply.toString().trim().take(80)}\"" }
                        .onFailure { status = "Gagal: ${it.message}" }
                }
            }) { Text("Tes") }
            Button(onClick = {
                app.providers.save(draft(), apiKey.ifBlank { null })
                onDone()
            }, enabled = baseUrl.isNotBlank() && model.isNotBlank()) { Text("Simpan") }
            if (initial != null) {
                TextButton(onClick = { app.providers.delete(initial.id); onDone() }) { Text("Hapus") }
            }
            TextButton(onClick = onDone) { Text("Batal") }
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
                Text("Cari di web")
                Text(
                    "Beka bisa mencari dan membaca halaman web sendiri untuk menjawab berita, harga, jadwal, dan fakta terbaru.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = enabled, onCheckedChange = app.webSettings::setEnabled)
        }
        if (enabled) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    TINYFISH to "TinyFish (gratis)",
                    BRAVE to "Brave",
                    TAVILY to "Tavily",
                    DUCKDUCKGO to "DuckDuckGo (tanpa key, kadang dibatasi)",
                ).forEach { (id, label) ->
                    FilterChip(selected = provider == id, onClick = { app.webSettings.setProvider(id); status = null }, label = { Text(label) })
                }
            }
            if (needsKey) {
                OutlinedTextField(
                    keyInput, { keyInput = it }, Modifier.fillMaxWidth(),
                    label = { Text("API key") },
                    placeholder = { if (provider in withKey) Text("(tersimpan — kosongkan untuk tetap)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                Text(
                    when (provider) {
                        TINYFISH -> "Daftar gratis di tinyfish.ai lalu salin API key dari dashboard. Key ini juga dipakai untuk membaca halaman."
                        BRAVE -> "Buat key di api.search.brave.com (Search API)."
                        else -> "Buat key di app.tavily.com."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    "Tanpa key, hasil dari DuckDuckGo. Kadang menolak kalau terlalu sering; TinyFish lebih stabil.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (needsKey) {
                    Button(
                        onClick = { app.webSettings.setApiKey(provider, keyInput); keyInput = ""; status = "Key disimpan" },
                        enabled = keyInput.isNotBlank(),
                    ) { Text("Simpan key") }
                    if (provider in withKey) {
                        TextButton(onClick = { app.webSettings.setApiKey(provider, ""); status = "Key dihapus" }) { Text("Hapus key") }
                    }
                }
                OutlinedButton(onClick = {
                    if (keyInput.isNotBlank()) { app.webSettings.setApiKey(provider, keyInput); keyInput = "" }
                    status = "Mengetes…"
                    scope.launch {
                        runCatching { app.webSettings.search(app.http).search("cuaca Jakarta hari ini", 5) }
                            .onSuccess { status = "OK · ${it.results.size} hasil" + (it.note?.let { n -> "\n$n" } ?: "") }
                            .onFailure { status = "Gagal: ${it.message}" }
                    }
                }) { Text("Tes") }
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

    Section("Konfirmasi aksi") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Minta izin sebelum aksi berisiko")
                Text(
                    "Kirim pesan, hapus, beli, posting, telepon, ubah rule.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = ask, onCheckedChange = { if (it) settings.setAskGlobal(true) else confirmOff = true })
        }
        Text("Per app", style = MaterialTheme.typography.labelLarge)
        if (overrides.isEmpty()) {
            Text(
                "Belum ada. Semua app ikut pilihan di atas.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        overrides.entries.sortedBy { labelOf(it.key).lowercase() }.forEach { (pkg, choice) ->
            Column {
                Text(labelOf(pkg))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = choice == AppConfirm.ASK, onClick = { settings.setOverride(pkg, AppConfirm.ASK) }, label = { Text("Selalu tanya") })
                    FilterChip(selected = choice == AppConfirm.SKIP, onClick = { settings.setOverride(pkg, AppConfirm.SKIP) }, label = { Text("Langsung jalan") })
                    TextButton(onClick = { settings.setOverride(pkg, null) }) { Text("Hapus") }
                }
            }
        }
        OutlinedButton(onClick = { picking = true }) { Text("Tambah app") }
        Text(
            "Telepon ikut pilihan app Telepon. Rule proaktif selalu minta izin. App bank dan dompet tidak pernah dijalankan.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (confirmOff) {
        AlertDialog(
            onDismissRequest = { confirmOff = false },
            title = { Text("Matikan konfirmasi?") },
            text = {
                Text(
                    "Beka akan langsung mengirim, menghapus, atau menelepon tanpa bertanya. Teks di layar atau web bisa saja mengarahkan Beka secara keliru. App bank dan dompet tetap diblokir.",
                )
            },
            confirmButton = { TextButton(onClick = { settings.setAskGlobal(false); confirmOff = false }) { Text("Matikan konfirmasi") } },
            dismissButton = { TextButton(onClick = { confirmOff = false }) { Text("Batal") } },
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
            title = { Text("Pilih app") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("Cari app") }, singleLine = true)
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
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Batal") } },
        )
    }
}

@Composable
private fun McpSection(app: BekaApp) {
    val scope = rememberCoroutineScope()
    val servers by app.mcpSettings.servers.collectAsState()
    var adding by remember { mutableStateOf(false) }

    Section("MCP") {
        Text(
            "Sambungkan layanan luar lewat MCP (Streamable HTTP). Tool-nya jadi bisa dipakai Beka di chat.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (servers.isEmpty()) {
            Text("Belum ada server.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        servers.forEach { s ->
            key(s.id) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s.name)
                            Text(s.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            when {
                                s.refreshing -> Text("Memuat…", style = MaterialTheme.typography.bodySmall)
                                s.error != null -> Text(s.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                                else -> Text("${s.tools.size} tools", style = MaterialTheme.typography.bodySmall)
                            }
                            if (s.error != null && !s.refreshing && s.tools.isNotEmpty()) {
                                Text("Memakai daftar lama (${s.tools.size} tools)", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Switch(checked = s.enabled, onCheckedChange = { app.mcpSettings.setEnabled(s.id, it) })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !s.autoRun, onClick = { app.mcpSettings.setAutoRun(s.id, false) }, label = { Text("Tanya dulu") })
                        FilterChip(selected = s.autoRun, onClick = { app.mcpSettings.setAutoRun(s.id, true) }, label = { Text("Langsung jalan") })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { scope.launch { app.mcpSettings.refresh(s.id) } }, enabled = !s.refreshing) { Text("Muat ulang") }
                        TextButton(onClick = { app.mcpSettings.remove(s.id) }) { Text("Hapus") }
                    }
                }
                HorizontalDivider()
            }
        }
        Text(
            "Tool yang hanya membaca selalu jalan langsung. Tool lain tanya dulu, kecuali server diset \"Langsung jalan\".",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = { adding = true }) { Text("Tambah server") }
    }

    if (adding) {
        var name by remember { mutableStateOf("") }
        var url by remember { mutableStateOf("") }
        var headerName by remember { mutableStateOf("Authorization") }
        var headerValue by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("Tambah server MCP") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Nama") }, singleLine = true)
                    OutlinedTextField(
                        url, { url = it }, Modifier.fillMaxWidth(), label = { Text("URL") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    )
                    OutlinedTextField(headerName, { headerName = it }, Modifier.fillMaxWidth(), label = { Text("Header (opsional)") }, singleLine = true)
                    OutlinedTextField(
                        headerValue, { headerValue = it }, Modifier.fillMaxWidth(), label = { Text("Nilai") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                    Text(
                        "Contoh tanpa key: Context7 https://mcp.context7.com/mcp",
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
                ) { Text("Tambah") }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("Batal") } },
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

    Section("Layar virtual (mode lanjutan)") {
        Text(
            "Beka bisa kerja di layar tersembunyi sambil HP kamu pakai, atau saat HP terkunci.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            when (status) {
                VirtualScreen.Status.NO_LAUNCHER -> "Belum disiapkan. Pasangkan Beka dengan Wireless debugging (sekali saja)."
                VirtualScreen.Status.NEED_PERMISSION -> "Beka perlu izin Shizuku."
                VirtualScreen.Status.CONNECTING -> "Menyambung…"
                VirtualScreen.Status.READY -> "Tersambung" + (screen.displayId?.let { " · layar #$it" } ?: "")
                VirtualScreen.Status.FAILED -> if (paired) "Belum tersambung. Pastikan Wi-Fi nyala." else "Belum tersambung."
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        // Shown even while connected through Shizuku: pairing makes Beka independent of it.
        if (!paired) {
            Text(
                "1. Aktifkan Opsi developer (Setelan → Tentang ponsel → ketuk Nomor build 7×).\n" +
                    "2. Tekan Pairing di bawah, nyalakan Wireless debugging, lalu pilih \"Pair device with pairing code\".\n" +
                    "3. Ketik kode 6 angka di notifikasi Beka yang muncul.",
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
                        .onFailure { note = "Opsi developer belum aktif. Ketuk Nomor build 7× dulu." }
                }) { Text("Pairing") }
                if (pairing != null) TextButton(onClick = { app.adbPairing.cancel() }) { Text("Batal") }
            }
            pairing?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (status != VirtualScreen.Status.READY) {
                Text("Sudah pakai Shizuku? Bisa juga lewat Shizuku:", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (status != VirtualScreen.Status.READY) {
            OutlinedButton(onClick = { screen.connect() }) { Text(if (paired) "Sambungkan" else "Sambungkan lewat Shizuku") }
        } else {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    val intent = app.packageManager.getLaunchIntentForPackage("com.android.settings")
                        ?: Intent(android.provider.Settings.ACTION_SETTINGS)
                    note = screen.launch(intent) ?: "Dibuka di layar virtual"
                    previewing = true
                }) { Text("Buka Setelan") }
                OutlinedButton(onClick = { note = "Back: ${screen.key(KeyEvent.KEYCODE_BACK)}" }) { Text("Back") }
                OutlinedButton(onClick = {
                    val pkg = screen.packages().firstOrNull()
                    note = if (pkg == null) "Layar virtual kosong" else "Pindah $pkg: ${screen.moveToMain(pkg)}"
                }) { Text("Ke layar utama") }
                OutlinedButton(onClick = { screen.close(); previewing = false; note = "Ditutup" }) { Text("Tutup") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { previewing = !previewing }) { Text(if (previewing) "Sembunyikan preview" else "Preview") }
                OutlinedButton(onClick = { screen.startLockTest() }) { Text("Tes kunci (3 menit)") }
            }
            Text(
                "Tes kunci: tekan, lalu kunci HP 3 menit. Beka mencatat apakah layar virtual tetap menyala.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        (testLine ?: note)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        frame?.let { Image(it.asImageBitmap(), contentDescription = "Preview layar virtual", Modifier.fillMaxWidth(0.6f)) }
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
