package dev.beka.rules

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.beka.BekaApp
import dev.beka.accessibility.AgentDevice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.format.DateTimeFormatter
import java.text.SimpleDateFormat
import java.time.LocalDateTime
import java.util.Date
import java.util.Locale
import java.util.UUID

/** A rule being created or edited: the JSON as typed, and the rule it parses to (if it does). */
private class Draft(val id: String, val json: String, val rule: Rule?, val parseError: String?, val isNew: Boolean)

private fun draftOf(rule: Rule, isNew: Boolean) = Draft(rule.id, rule.toJson(), rule, null, isNew)

@Composable
fun RulesScreen(app: BekaApp, onBack: (() -> Unit)? = null) {
    if (onBack != null) BackHandler(onBack = onBack)
    val store = app.rulesStore
    val scope = rememberCoroutineScope()
    val enabled by app.proactiveSettings.enabled.collectAsState()
    val rules by store.rules.collectAsState()
    val permissions by store.permissions.collectAsState()
    val runsChanged by store.runsChanged.collectAsState()
    var runs by remember { mutableStateOf<List<RuleRun>>(emptyList()) }
    var request by remember { mutableStateOf("") }
    var drafting by remember { mutableStateOf(false) }
    var draftError by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf<Draft?>(null) }
    val label = app.proactive::label

    LaunchedEffect(runsChanged) { runs = store.recentRuns() }
    /** Per rule: when it last ran and how often today, for the "ready again at" line. */
    var usage by remember { mutableStateOf<Map<String, Pair<LocalDateTime?, Int>>>(emptyMap()) }
    LaunchedEffect(runsChanged, rules) {
        usage = withContext(Dispatchers.IO) { rules.associate { it.id to (store.lastRun(it.id) to store.runsToday(it.id)) } }
    }
    // Ticks so "bisa jalan lagi" turns into "siap" without leaving the screen.
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = LocalDateTime.now()
        }
    }

    fun makeDraft() {
        val config = app.providers.active() ?: run { draftError = "Belum ada provider aktif."; return }
        drafting = true
        draftError = null
        scope.launch {
            try {
                val rule = RuleDrafter(app.providers.client(config), config.model)
                    .draft(request, LocalDateTime.now(), UUID.randomUUID().toString())
                    .resolveApps { AgentDevice(app.media).resolveApp(it) }
                draft = draftOf(rule, isNew = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                draftError = e.message ?: e.toString()
            } finally {
                drafting = false
            }
        }
    }

    Surface(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.safeDrawingPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (onBack != null) TextButton(onClick = onBack) { Text("‹ Kembali") }
                    Text("Proaktif", style = MaterialTheme.typography.headlineMedium)
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Rule proaktif aktif")
                        Text(
                            "Panggilan model hari ini: ${app.proactiveSettings.callsToday()} / ${app.proactiveSettings.dailyCalls}. " +
                                "Bisa juga dimatikan dari notifikasi Beka.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(checked = enabled, onCheckedChange = app.proactiveSettings::setEnabled)
                }
            }

            val current = draft
            if (current == null) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Bikin rule", style = MaterialTheme.typography.titleMedium)
                            OutlinedTextField(
                                request, { request = it }, Modifier.fillMaxWidth(),
                                placeholder = { Text("Contoh: tiap gw buka Shopee dan lagi checkout, cek voucher") },
                                minLines = 2,
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Button(onClick = ::makeDraft, enabled = request.isNotBlank() && !drafting) { Text("Buat") }
                                if (drafting) {
                                    Spacer(Modifier.size(12.dp))
                                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                }
                            }
                            draftError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            } else {
                item {
                    RuleEditor(
                        draft = current,
                        permissions = permissions,
                        label = label,
                        resolve = { AgentDevice(app.media).resolveApp(it) },
                        onChange = { draft = it },
                        onCancel = { draft = null },
                        onSave = { rule ->
                            scope.launch {
                                for ((pkg, permission) in grantsFor(rule, permissions)) store.setPermission(pkg, permission)
                                store.save(rule)
                                draft = null
                                request = ""
                            }
                        },
                    )
                }
            }

            item { Text("Rule (${rules.size})", style = MaterialTheme.typography.titleMedium) }
            if (rules.isEmpty()) item { Text("Belum ada rule.", style = MaterialTheme.typography.bodyMedium) }
            items(rules, key = { it.id }) { rule ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(rule.name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                            Switch(checked = rule.enabled, onCheckedChange = { on -> scope.launch { store.save(rule.copy(enabled = on)) } })
                        }
                        Text(RuleText.summary(rule, label), style = MaterialTheme.typography.bodySmall)
                        if (rule.enabled) {
                            val (last, today) = usage[rule.id] ?: (null to 0)
                            Text(
                                readiness(rule, last, today, now, enabled),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        val blocked = rule.packages.filter { permissions[it] == null || permissions[it] == AppPermission.BLOCKED }
                        if (blocked.isNotEmpty()) {
                            Text(
                                "Tidak akan jalan: izin ${blocked.joinToString { label(it) }} belum diberikan.",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Row {
                            TextButton(onClick = { draft = draftOf(rule, isNew = false) }) { Text("Edit") }
                            TextButton(onClick = { scope.launch { store.delete(rule.id) } }) { Text("Hapus") }
                        }
                    }
                }
            }

            item { Text("Izin per app", style = MaterialTheme.typography.titleMedium) }
            item {
                Text(
                    "Rule hanya jalan di app yang diberi izin. Baca = cuma kabari, Saran = kabari + tombol Lakukan, Aksi = boleh bertindak sendiri.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (permissions.isEmpty()) item { Text("Belum ada app yang diizinkan.", style = MaterialTheme.typography.bodyMedium) }
            items(permissions.entries.sortedBy { label(it.key) }, key = { it.key }) { (pkg, permission) ->
                Column {
                    Text(label(pkg), style = MaterialTheme.typography.bodyMedium)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        AppPermission.entries.forEach { p ->
                            FilterChip(permission == p, { scope.launch { store.setPermission(pkg, p) } }, { Text(RuleText.permission(p)) })
                        }
                        TextButton(onClick = { scope.launch { store.setPermission(pkg, null) } }) { Text("Hapus") }
                    }
                }
            }

            item {
                val week = System.currentTimeMillis() - 7L * 24 * 3600 * 1000
                val recent = runs.filter { it.time >= week && it.counted }
                Column {
                    Text("Riwayat", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "7 hari: ${recent.size} jalan, ${recent.count { it.wrong }} ditandai salah trigger.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            items(runs, key = { it.id }) { run -> RunRow(run, onWrong = { scope.launch { store.markWrong(run.id, !run.wrong) } }) }
        }
    }
}

@Composable
private fun RuleEditor(
    draft: Draft,
    permissions: Map<String, AppPermission>,
    label: (String) -> String,
    resolve: (String) -> String?,
    onChange: (Draft) -> Unit,
    onCancel: () -> Unit,
    onSave: (Rule) -> Unit,
) {
    val rule = draft.rule
    val problems = rule?.problems().orEmpty()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (draft.isNew) "Rule baru — cek dulu" else "Edit rule", style = MaterialTheme.typography.titleMedium)
            if (rule != null) {
                Text(rule.name, style = MaterialTheme.typography.titleSmall)
                Text(RuleText.summary(rule, label), style = MaterialTheme.typography.bodyMedium)
                for ((pkg, permission) in grantsFor(rule, permissions)) {
                    Text(
                        "Izin ${label(pkg)}: ${RuleText.permission(permissions[pkg])} → ${RuleText.permission(permission)} (ikut diberikan saat disimpan)",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                rule.packages.filter { permissions[it] == AppPermission.BLOCKED }.forEach {
                    Text("${label(it)} diblokir: rule ini tidak akan jalan.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
            (listOfNotNull(draft.parseError) + problems).forEach {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            OutlinedTextField(
                value = draft.json,
                onValueChange = { text ->
                    val parsed = runCatching { Rule.fromJson(text) }
                    // The id is not the user's to change: editing it would save a copy.
                    onChange(Draft(draft.id, text, parsed.getOrNull()?.copy(id = draft.id)?.resolveApps(resolve), parsed.exceptionOrNull()?.message, draft.isNew))
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("JSON") },
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { rule?.let(onSave) }, enabled = rule != null && problems.isEmpty()) { Text("Simpan") }
                OutlinedButton(onClick = onCancel) { Text("Batal") }
            }
        }
    }
}

@Composable
private fun RunRow(run: RuleRun, onWrong: () -> Unit) {
    val time = remember(run.time) { SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(run.time)) }
    val outcome = when (run.outcome) {
        "notified" -> "dikabari"
        "suggested" -> "disarankan"
        "accepted" -> "saran dikerjakan"
        "nothing" -> "model memutuskan tidak perlu dikabari"
        "acting" -> "sedang bertindak"
        "acted" -> "bertindak"
        "running" -> "berjalan"
        "skipped" -> "dilewati"
        "error" -> "gagal"
        else -> run.outcome
    }
    Column(Modifier.fillMaxWidth()) {
        Text("$time · ${run.ruleName} · ${run.trigger}", style = MaterialTheme.typography.labelMedium)
        Text(
            outcome + if (run.text.isNotBlank()) ": ${run.text}" else "",
            style = MaterialTheme.typography.bodySmall,
            color = if (run.outcome == "error") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
        )
        if (run.counted) {
            TextButton(onClick = onWrong) { Text(if (run.wrong) "✗ Ditandai salah trigger (batalkan)" else "Salah trigger?") }
        }
    }
}

/** When [rule] can fire next, as the engine sees it: cooldown, daily budget, schedule, kill switch. */
private fun readiness(rule: Rule, last: LocalDateTime?, today: Int, now: LocalDateTime, proactiveOn: Boolean): String {
    if (!proactiveOn) return "Proaktif dimatikan"
    val lastText = last?.let { "Terakhir jalan ${at(it, now)} · " }.orEmpty()
    if (today >= rule.budget.maxRunsPerDay) return lastText + "budget hari ini habis (${today}/${rule.budget.maxRunsPerDay}), jalan lagi besok"
    val trigger = rule.trigger
    if (trigger is Trigger.Schedule) {
        val next = Schedules.next(trigger, now) ?: return lastText + "jam jadwal tidak valid"
        return lastText + "jadwal berikutnya ${at(next, now)}"
    }
    val ready = last?.let { RuleEngine.readyAt(rule, it) }
    return when {
        ready != null && ready.isAfter(now) -> lastText + "bisa jalan lagi mulai ${at(ready, now)}"
        last == null -> "Belum pernah jalan · siap"
        else -> lastText + "siap"
    }
}

/** HH:mm today, otherwise with the day: "Sen 07:00" within a week, else "12/10 07:00". */
private fun at(time: LocalDateTime, now: LocalDateTime): String {
    val clock = time.format(DateTimeFormatter.ofPattern("HH:mm"))
    val days = java.time.temporal.ChronoUnit.DAYS.between(now.toLocalDate(), time.toLocalDate())
    return when {
        days == 0L -> clock
        days == 1L -> "besok $clock"
        days == -1L -> "kemarin $clock"
        days in 2..6 -> listOf("Sen", "Sel", "Rab", "Kam", "Jum", "Sab", "Min")[time.dayOfWeek.ordinal] + " " + clock
        else -> time.format(DateTimeFormatter.ofPattern("dd/MM HH:mm"))
    }
}

/**
 * Permissions saving [rule] raises, so the rule can run as written: the trigger app gets what the
 * level needs, and apps the task works in get "act" (or "read" for note-only rules). Never lowers
 * a permission and never overrides "blocked".
 */
fun grantsFor(rule: Rule, current: Map<String, AppPermission>): List<Pair<String, AppPermission>> {
    val wanted = buildMap {
        rule.trigger.pkg?.takeIf { it.isNotBlank() }?.let { put(it, AppPermission.needed(rule.action.level)) }
        for (pkg in rule.action.apps) {
            val need = if (rule.action.level.acts) AppPermission.ACT else AppPermission.READ
            put(pkg, maxOf(need, get(pkg) ?: need))
        }
    }
    return wanted.mapNotNull { (pkg, need) ->
        val have = current[pkg]
        if (have == AppPermission.BLOCKED || (have != null && have >= need)) null else pkg to need
    }
}
