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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.beka.BekaApp
import android.app.AlarmManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import dev.beka.R
import dev.beka.accessibility.AgentDevice
import dev.beka.chat.errorSummary
import dev.beka.chat.friendlyError
import java.time.format.TextStyle
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
    val context = LocalContext.current
    var exactHint by remember { mutableStateOf(false) }

    /** After a schedule rule is on: schedules are late on Android 12+ unless exact alarms are allowed. Asked once. */
    fun checkExactAlarms(rule: Rule) {
        if (!rule.enabled || rule.trigger !is Trigger.Schedule || app.proactiveSettings.exactAlarmHintShown) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()) return
        app.proactiveSettings.exactAlarmHintShown = true
        exactHint = true
    }

    LaunchedEffect(runsChanged) { runs = store.recentRuns() }
    /** Per rule: when it last ran and how often today, for the "ready again at" line. */
    var usage by remember { mutableStateOf<Map<String, Pair<LocalDateTime?, Int>>>(emptyMap()) }
    LaunchedEffect(runsChanged, rules) {
        usage = withContext(Dispatchers.IO) { rules.associate { it.id to (store.lastRun(it.id) to store.runsToday(it.id)) } }
    }
    // Ticks so "can run again from …" turns into "ready" without leaving the screen.
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = LocalDateTime.now()
        }
    }

    fun makeDraft() {
        val config = app.providers.active() ?: run { draftError = context.getString(R.string.rules_no_model); return }
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
                Log.e("BekaRules", "draft failed: ${errorSummary(e)}")
                draftError = friendlyError(e)
            } finally {
                drafting = false
            }
        }
    }

    if (exactHint) {
        AlertDialog(
            onDismissRequest = { exactHint = false },
            title = { Text(stringResource(R.string.rules_exact_alarm_title)) },
            text = {
                Text(stringResource(R.string.rules_exact_alarm_desc))
            },
            confirmButton = {
                TextButton(onClick = {
                    exactHint = false
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")),
                        )
                    }
                }) { Text(stringResource(R.string.open_settings)) }
            },
            dismissButton = { TextButton(onClick = { exactHint = false }) { Text(stringResource(R.string.not_now)) } },
        )
    }

    Surface(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.safeDrawingPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (onBack != null) TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
                    Text(stringResource(R.string.rules_title), style = MaterialTheme.typography.headlineMedium)
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.rules_enabled))
                        Text(
                            stringResource(R.string.rules_calls_today, app.proactiveSettings.callsToday(), app.proactiveSettings.dailyCalls),
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
                            Text(stringResource(R.string.rules_create_title), style = MaterialTheme.typography.titleMedium)
                            OutlinedTextField(
                                request, { request = it }, Modifier.fillMaxWidth(),
                                placeholder = { Text(stringResource(R.string.rules_create_hint)) },
                                minLines = 2,
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Button(onClick = ::makeDraft, enabled = request.isNotBlank() && !drafting) { Text(stringResource(R.string.rules_create)) }
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
                                store.saveWithGrants(rule)
                                checkExactAlarms(rule)
                                draft = null
                                request = ""
                            }
                        },
                    )
                }
            }

            item { Text(stringResource(R.string.rules_count, rules.size), style = MaterialTheme.typography.titleMedium) }
            if (rules.isEmpty()) item { Text(stringResource(R.string.rules_none), style = MaterialTheme.typography.bodyMedium) }
            items(rules, key = { it.id }) { rule ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(rule.name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                            Switch(checked = rule.enabled, onCheckedChange = { on ->
                                scope.launch {
                                    store.save(rule.copy(enabled = on))
                                    checkExactAlarms(rule.copy(enabled = on))
                                }
                            })
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
                                stringResource(R.string.rules_blocked, blocked.joinToString { label(it) }),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Row {
                            TextButton(onClick = { draft = draftOf(rule, isNew = false) }) { Text(stringResource(R.string.edit)) }
                            TextButton(onClick = { scope.launch { store.delete(rule.id) } }) { Text(stringResource(R.string.delete)) }
                        }
                    }
                }
            }

            item { Text(stringResource(R.string.rules_app_permissions), style = MaterialTheme.typography.titleMedium) }
            item {
                Text(
                    stringResource(R.string.rules_app_permissions_desc),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (permissions.isEmpty()) item { Text(stringResource(R.string.rules_no_apps), style = MaterialTheme.typography.bodyMedium) }
            items(permissions.entries.sortedBy { label(it.key) }, key = { it.key }) { (pkg, permission) ->
                Column {
                    Text(label(pkg), style = MaterialTheme.typography.bodyMedium)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        AppPermission.entries.forEach { p ->
                            FilterChip(permission == p, { scope.launch { store.setPermission(pkg, p) } }, { Text(RuleText.permission(p)) })
                        }
                        TextButton(onClick = { scope.launch { store.setPermission(pkg, null) } }) { Text(stringResource(R.string.delete)) }
                    }
                }
            }

            item {
                val week = System.currentTimeMillis() - 7L * 24 * 3600 * 1000
                val recent = runs.filter { it.time >= week && it.counted }
                Column {
                    Text(stringResource(R.string.rules_history), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.rules_history_summary, recent.size, recent.count { it.wrong }),
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
            Text(if (draft.isNew) stringResource(R.string.rules_draft_new) else stringResource(R.string.rules_draft_edit), style = MaterialTheme.typography.titleMedium)
            if (rule != null) {
                Text(rule.name, style = MaterialTheme.typography.titleSmall)
                Text(RuleText.summary(rule, label), style = MaterialTheme.typography.bodyMedium)
                for ((pkg, permission) in grantsFor(rule, permissions)) {
                    Text(
                        stringResource(R.string.rules_grant_line, label(pkg), RuleText.permission(permissions[pkg]), RuleText.permission(permission)),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                rule.packages.filter { permissions[it] == AppPermission.BLOCKED }.forEach {
                    Text(stringResource(R.string.rules_app_blocked, label(it)), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
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
                Button(onClick = { rule?.let(onSave) }, enabled = rule != null && problems.isEmpty()) { Text(stringResource(R.string.save)) }
                OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
            }
        }
    }
}

@Composable
private fun RunRow(run: RuleRun, onWrong: () -> Unit) {
    val time = remember(run.time) { SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(run.time)) }
    val outcome = when (run.outcome) {
        "notified" -> stringResource(R.string.rules_out_notified)
        "suggested" -> stringResource(R.string.rules_out_suggested)
        "accepted" -> stringResource(R.string.rules_out_accepted)
        "nothing" -> stringResource(R.string.rules_out_nothing)
        "acting" -> stringResource(R.string.rules_out_acting)
        "acted" -> stringResource(R.string.rules_out_acted)
        "running" -> stringResource(R.string.rules_out_running)
        "skipped" -> stringResource(R.string.rules_out_skipped)
        "error" -> stringResource(R.string.rules_out_error)
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
            TextButton(onClick = onWrong) { Text(if (run.wrong) stringResource(R.string.rules_marked_wrong) else stringResource(R.string.rules_wrong_q)) }
        }
    }
}

/** When [rule] can fire next, as the engine sees it: cooldown, daily budget, schedule, kill switch. */
@Composable
private fun readiness(rule: Rule, last: LocalDateTime?, today: Int, now: LocalDateTime, proactiveOn: Boolean): String {
    if (!proactiveOn) return stringResource(R.string.rules_proactive_off)
    val lastText = last?.let { stringResource(R.string.rules_last_run, at(it, now)) + " · " }.orEmpty()
    if (today >= rule.budget.maxRunsPerDay) return lastText + stringResource(R.string.rules_budget_used, today, rule.budget.maxRunsPerDay)
    val trigger = rule.trigger
    if (trigger is Trigger.Schedule) {
        val next = Schedules.next(trigger, now) ?: return lastText + stringResource(R.string.rules_bad_schedule)
        return lastText + stringResource(R.string.rules_next_schedule, at(next, now))
    }
    val ready = last?.let { RuleEngine.readyAt(rule, it) }
    return when {
        ready != null && ready.isAfter(now) -> lastText + stringResource(R.string.rules_ready_at, at(ready, now))
        last == null -> stringResource(R.string.rules_never_ran)
        else -> lastText + stringResource(R.string.rules_ready)
    }
}

/** HH:mm today, otherwise with the day: "Mon 07:00" within a week, else "12/10 07:00". */
@Composable
private fun at(time: LocalDateTime, now: LocalDateTime): String {
    val clock = time.format(DateTimeFormatter.ofPattern("HH:mm"))
    val days = java.time.temporal.ChronoUnit.DAYS.between(now.toLocalDate(), time.toLocalDate())
    return when {
        days == 0L -> clock
        days == 1L -> stringResource(R.string.at_tomorrow, clock)
        days == -1L -> stringResource(R.string.at_yesterday, clock)
        days in 2..6 -> time.dayOfWeek.getDisplayName(TextStyle.SHORT, LocalConfiguration.current.locales[0]) + " " + clock
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

/** Saves [rule] after giving its apps the permissions it needs ([grantsFor]); the Proactive controller reschedules on its own. */
suspend fun RulesStore.saveWithGrants(rule: Rule) {
    for ((pkg, permission) in grantsFor(rule, permissions.value)) setPermission(pkg, permission)
    save(rule)
}
