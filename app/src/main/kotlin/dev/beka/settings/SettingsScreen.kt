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
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
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
import dev.beka.memory.MemoryItem
import dev.beka.memory.MemoryKind
import dev.beka.memory.MemoryScreen
import dev.beka.memory.live
import dev.beka.overlay.OverlayService
import dev.beka.rules.RulesScreen
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
fun SettingsScreen(app: BekaApp, resumeTick: Int, actions: SettingsActions) {
    val context = LocalContext.current
    val a11y by BekaAccessibilityService.connected.collectAsState()
    val running by OverlayService.running.collectAsState()
    val providers by app.providers.providers.collectAsState()
    val activeId by app.providers.activeId.collectAsState()
    var editing by remember { mutableStateOf<ProviderConfig?>(null) }
    var adding by remember { mutableStateOf(false) }
    var showMemory by remember { mutableStateOf(false) }
    var showRules by remember { mutableStateOf(false) }
    val proactiveOn by app.proactiveSettings.enabled.collectAsState()
    val rules by app.rulesStore.rules.collectAsState()
    val memoryChanges by app.memoryStore.changes.collectAsState()
    var memoryItems by remember { mutableStateOf<List<MemoryItem>>(emptyList()) }
    LaunchedEffect(memoryChanges) { memoryItems = app.memoryStore.all() }

    // Re-read permission state whenever the activity resumes.
    val overlayOk = remember(resumeTick) { actions.hasOverlayPermission() }
    val notifOk = remember(resumeTick) { actions.hasNotificationPermission() }
    val notifAccess = remember(resumeTick) { actions.hasNotificationAccess() }

    if (showMemory) {
        MemoryScreen(app) { showMemory = false }
        return
    }
    if (showRules) {
        RulesScreen(app) { showRules = false }
        return
    }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Beka", style = MaterialTheme.typography.headlineMedium)
            Text("v${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall)

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
            }

            Section("Overlay") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (running) "Bubble aktif" else "Bubble mati", Modifier.weight(1f))
                    if (running) {
                        OutlinedButton(onClick = { OverlayService.stop(context) }) { Text("Stop") }
                    } else {
                        Button(onClick = { OverlayService.start(context) }, enabled = overlayOk) { Text("Mulai") }
                    }
                }
            }

            Section("Provider") {
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

            Section("Memory") {
                val live = memoryItems.live()
                val count = { kind: MemoryKind -> live.count { it.kind == kind } }
                val review = memoryItems.count { it.pending }
                val summary = "${count(MemoryKind.FACT)} fakta · ${count(MemoryKind.EPISODE)} episode · ${count(MemoryKind.PROCEDURE)} prosedur" +
                    if (review > 0) " · $review perlu review" else ""
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(summary, Modifier.weight(1f))
                    Button(onClick = { showMemory = true }) { Text("Buka") }
                }
            }

            Section("Proaktif") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${rules.count { it.enabled }} rule aktif dari ${rules.size}")
                        Text(if (proactiveOn) "Rule berjalan" else "Semua rule dimatikan", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = proactiveOn, onCheckedChange = app.proactiveSettings::setEnabled)
                    Spacer(Modifier.size(8.dp))
                    Button(onClick = { showRules = true }) { Text("Buka") }
                }
            }

            Section("Debug") {
                Text(
                    "Tombol \"Laporkan\" di panel menyimpan bundel ke Download/beka-debug/.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
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
