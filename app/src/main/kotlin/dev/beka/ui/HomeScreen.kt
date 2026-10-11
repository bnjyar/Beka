package dev.beka.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.beka.BekaApp
import dev.beka.BuildConfig
import dev.beka.R
import dev.beka.SettingsActions
import dev.beka.accessibility.BekaAccessibilityService
import dev.beka.memory.MemoryItem
import dev.beka.memory.live
import dev.beka.overlay.OverlayService
import dev.beka.update.UpdateChannel
import dev.beka.update.UpdateState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The first permission that is still missing, shown as a single card on Beranda. */
private class Missing(
    val icon: ImageVector,
    val title: String,
    val body: String?,
    val action: String,
    val onClick: () -> Unit,
    val restrictedHint: Boolean = false,
)

@Composable
fun HomeScreen(app: BekaApp, resumeTick: Int, actions: SettingsActions, onOpenTab: (Int) -> Unit) {
    val context = LocalContext.current
    val running by OverlayService.running.collectAsState()
    val channel by app.updateSettings.channel.collectAsState()
    val a11y by BekaAccessibilityService.connected.collectAsState()
    val providers by app.providers.providers.collectAsState()
    val activeId by app.providers.activeId.collectAsState()
    val proactiveOn by app.proactiveSettings.enabled.collectAsState()
    val rules by app.rulesStore.rules.collectAsState()
    val runsChanged by app.rulesStore.runsChanged.collectAsState()
    val memoryChanges by app.memoryStore.changes.collectAsState()
    val update by app.updateSettings.available.collectAsState()
    val updateChannel by app.updateSettings.channel.collectAsState()
    val updateState by app.updater.state.collectAsState()

    val overlayOk = remember(resumeTick) { actions.hasOverlayPermission() }
    val notifOk = remember(resumeTick) { actions.hasNotificationPermission() }
    val notifAccess = remember(resumeTick) { actions.hasNotificationAccess() }
    val batteryOk = remember(resumeTick) { actions.hasBatteryExemption() }

    var runsToday by remember { mutableIntStateOf(0) }
    LaunchedEffect(rules, runsChanged) {
        runsToday = withContext(Dispatchers.IO) { rules.sumOf { app.rulesStore.runsToday(it.id) } }
    }
    var memoryItems by remember { mutableStateOf<List<MemoryItem>>(emptyList()) }
    LaunchedEffect(memoryChanges) { memoryItems = app.memoryStore.all() }

    val missing: Missing? = when {
        !overlayOk -> Missing(
            BekaIcons.Layers, stringResource(R.string.home_missing_overlay), null, stringResource(R.string.allow), actions::requestOverlayPermission,
        )
        !a11y -> Missing(
            BekaIcons.Eye, stringResource(R.string.home_missing_a11y), null, stringResource(R.string.turn_on), actions::openAccessibilitySettings,
            restrictedHint = true,
        )
        !notifOk -> Missing(
            BekaIcons.Bell, stringResource(R.string.home_missing_notif), null, stringResource(R.string.allow), actions::requestNotificationPermission,
        )
        !notifAccess -> Missing(
            BekaIcons.Bell, stringResource(R.string.home_missing_notif_access), stringResource(R.string.home_missing_notif_access_desc),
            stringResource(R.string.turn_on), actions::openNotificationAccessSettings, restrictedHint = true,
        )
        // Last: Beka works without it, it only stays alive longer.
        !batteryOk -> Missing(
            BekaIcons.Bolt, stringResource(R.string.home_missing_battery),
            Oem.current()?.hint?.let { stringResource(it) } ?: stringResource(R.string.home_missing_battery_desc),
            stringResource(R.string.allow), actions::requestBatteryExemption,
        )
        else -> null
    }
    val active = providers.firstOrNull { it.id == activeId }
    val live = memoryItems.live()
    val pending = memoryItems.count { it.pending }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Which build is installed, at a glance (after an update, or while writing a report).
            Row(verticalAlignment = Alignment.Bottom) {
                Text("Beka", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(
                    "v${BuildConfig.VERSION_NAME} · ${if (channel == UpdateChannel.NIGHTLY) "Nightly" else stringResource(R.string.channel_stable)}",
                    modifier = Modifier.padding(start = 8.dp, bottom = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Status
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(28.dp))
                    .background(MaterialTheme.colorScheme.primary)
                    .padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(
                    Modifier.size(48.dp).background(MaterialTheme.colorScheme.onPrimary, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "B",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        if (running) stringResource(R.string.home_running) else stringResource(R.string.home_stopped),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Text(
                        if (running) stringResource(R.string.home_running_desc) else stringResource(R.string.home_stopped_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f),
                    )
                }
                Button(
                    onClick = { if (running) OverlayService.stop(context) else OverlayService.start(context) },
                    enabled = running || overlayOk,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.onPrimary,
                        contentColor = MaterialTheme.colorScheme.primary,
                    ),
                ) { Text(if (running) stringResource(R.string.stop) else stringResource(R.string.start)) }
            }

            if (missing != null) {
                PanelCard(Modifier.fillMaxWidth(), padding = 16.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(missing.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f)) {
                            Text(missing.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                            missing.body?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Button(onClick = missing.onClick) { Text(missing.action) }
                    }
                    if (missing.restrictedHint) {
                        Text(
                            stringResource(R.string.restricted_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        TextButton(onClick = actions::openAppInfo) { Text(stringResource(R.string.open_app_info)) }
                    }
                }
            }

            update?.let { found ->
                val working = updateState is UpdateState.Downloading || updateState is UpdateState.Installing
                PanelCard(Modifier.fillMaxWidth(), padding = 16.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.update_card_title, found.versionName),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            val detail = when (val s = updateState) {
                                is UpdateState.Downloading -> if (s.percent >= 0) stringResource(R.string.update_downloading_pct, s.percent) else stringResource(R.string.update_downloading)
                                UpdateState.Installing -> stringResource(R.string.update_confirm_install)
                                is UpdateState.Error -> s.text
                                else -> if (updateChannel == UpdateChannel.NIGHTLY) stringResource(R.string.update_channel_nightly) else stringResource(R.string.update_channel_stable)
                            }
                            Text(
                                detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (updateState is UpdateState.Error) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Button(onClick = app.updater::install, enabled = !working) { Text(stringResource(R.string.install)) }
                    }
                }
            }

            // Model, Proaktif and Memori share one row shape: label, main line, detail, chevron.
            if (active == null) {
                // Same look as the missing-permission card: without a model Beka cannot answer at all.
                PanelCard(Modifier.fillMaxWidth(), padding = 16.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(BekaIcons.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.home_no_model), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                            Text(
                                stringResource(R.string.home_no_model_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Button(onClick = { onOpenTab(MainTab.SETTINGS) }) { Text(stringResource(R.string.set_up)) }
                    }
                }
            } else {
                InfoCard(
                    label = stringResource(R.string.home_label_model),
                    main = active.name,
                    detail = active.model.ifBlank { stringResource(R.string.home_no_model_picked) },
                    onClick = { onOpenTab(MainTab.SETTINGS) },
                )
            }
            InfoCard(
                label = stringResource(R.string.home_label_proactive),
                main = rules.count { it.enabled }.let { pluralStringResource(R.plurals.home_rules_active, it, it) },
                detail = if (proactiveOn) pluralStringResource(R.plurals.home_runs_today, runsToday, runsToday) else stringResource(R.string.home_rules_off),
                onClick = { onOpenTab(MainTab.RULES) },
            )
            InfoCard(
                label = stringResource(R.string.home_label_memory),
                main = pluralStringResource(R.plurals.home_memory_count, live.size, live.size),
                detail = if (pending > 0) pluralStringResource(R.plurals.home_memory_pending, pending, pending) else stringResource(R.string.home_memory_all_checked),
                onClick = { onOpenTab(MainTab.MEMORY) },
            )
        }
    }
}

@Composable
private fun InfoCard(label: String, main: String, detail: String, onClick: () -> Unit) {
    PanelCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    letterSpacing = 1.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(main, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(
                BekaIcons.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
