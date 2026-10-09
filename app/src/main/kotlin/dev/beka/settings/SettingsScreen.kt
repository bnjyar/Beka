package dev.beka.settings

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
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.beka.BekaApp
import dev.beka.BuildConfig
import dev.beka.SettingsActions
import dev.beka.accessibility.BekaAccessibilityService
import dev.beka.llm.ChatEvent
import dev.beka.llm.ChatRequest
import dev.beka.llm.Message
import dev.beka.llm.Presets
import dev.beka.llm.ProviderConfig
import dev.beka.llm.ProviderKind
import dev.beka.ui.PanelCard
import dev.beka.voice.Speaker
import dev.beka.voice.VoiceSettings
import dev.beka.settings.WebSettings.Companion.BRAVE
import dev.beka.settings.WebSettings.Companion.DUCKDUCKGO
import dev.beka.settings.WebSettings.Companion.TAVILY
import dev.beka.settings.WebSettings.Companion.TINYFISH
import kotlinx.coroutines.launch
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

            Section("Lainnya") {
                Text(
                    "Tombol \"Laporkan\" di panel menyimpan bundel ke Download/beka-debug/.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text("v${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { app.appearance.setOnboarded(false) }) { Text("Ulangi setup awal") }
            }
        }
    }
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
                Text(if (kind == ProviderKind.OPENAI_COMPATIBLE) "Termasuk /v1, mis. https://api.openai.com/v1" else "Tanpa /v1, mis. https://api.anthropic.com")
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
