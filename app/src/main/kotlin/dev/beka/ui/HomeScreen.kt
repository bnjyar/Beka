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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.beka.BekaApp
import dev.beka.SettingsActions
import dev.beka.accessibility.BekaAccessibilityService
import dev.beka.memory.MemoryItem
import dev.beka.memory.live
import dev.beka.overlay.OverlayService
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
    val a11y by BekaAccessibilityService.connected.collectAsState()
    val providers by app.providers.providers.collectAsState()
    val activeId by app.providers.activeId.collectAsState()
    val proactiveOn by app.proactiveSettings.enabled.collectAsState()
    val rules by app.rulesStore.rules.collectAsState()
    val runsChanged by app.rulesStore.runsChanged.collectAsState()
    val memoryChanges by app.memoryStore.changes.collectAsState()

    val overlayOk = remember(resumeTick) { actions.hasOverlayPermission() }
    val notifOk = remember(resumeTick) { actions.hasNotificationPermission() }
    val notifAccess = remember(resumeTick) { actions.hasNotificationAccess() }

    var runsToday by remember { mutableIntStateOf(0) }
    LaunchedEffect(rules, runsChanged) {
        runsToday = withContext(Dispatchers.IO) { rules.sumOf { app.rulesStore.runsToday(it.id) } }
    }
    var memoryItems by remember { mutableStateOf<List<MemoryItem>>(emptyList()) }
    LaunchedEffect(memoryChanges) { memoryItems = app.memoryStore.all() }

    val missing: Missing? = when {
        !overlayOk -> Missing(
            BekaIcons.Layers, "Izin tampil di atas app belum ada", null, "Izinkan", actions::requestOverlayPermission,
        )
        !a11y -> Missing(
            BekaIcons.Eye, "Aksesibilitas Beka belum nyala", null, "Nyalakan", actions::openAccessibilitySettings,
            restrictedHint = true,
        )
        !notifOk -> Missing(
            BekaIcons.Bell, "Notifikasi belum diizinkan", null, "Izinkan", actions::requestNotificationPermission,
        )
        !notifAccess -> Missing(
            BekaIcons.Bell, "Akses notifikasi belum nyala", "Dibutuhkan buat rule notifikasi & info musik",
            "Nyalakan", actions::openNotificationAccessSettings,
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
            Text("Beka", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)

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
                        if (running) "Beka aktif" else "Beka mati",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Text(
                        if (running) "Bubble tampil di atas semua app" else "Tap Mulai untuk memunculkan bubble",
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
                ) { Text(if (running) "Hentikan" else "Mulai") }
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
                            "Kalau tidak bisa dinyalakan: App info → ⋮ → Izinkan setelan terbatas, lalu coba lagi.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        TextButton(onClick = actions::openAppInfo) { Text("Buka App info") }
                    }
                }
            }

            // Model
            PanelCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "MODEL",
                            style = MaterialTheme.typography.labelSmall,
                            letterSpacing = 1.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            if (active != null) "${active.name} · ${active.model.ifBlank { "model belum dipilih" }}" else "Belum ada model",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    TextButton(onClick = { onOpenTab(MainTab.SETTINGS) }) { Text("Ganti") }
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                StatCard(
                    label = "PROAKTIF",
                    number = rules.count { it.enabled },
                    line = if (proactiveOn) "rule aktif · $runsToday jalan hari ini" else "semua rule dimatikan",
                    onClick = { onOpenTab(MainTab.RULES) },
                    modifier = Modifier.weight(1f),
                )
                StatCard(
                    label = "MEMORI",
                    number = live.size,
                    line = "hal diingat" + if (pending > 0) " · $pending perlu dicek" else "",
                    onClick = { onOpenTab(MainTab.MEMORY) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun StatCard(label: String, number: Int, line: String, onClick: () -> Unit, modifier: Modifier) {
    PanelCard(modifier, onClick = onClick) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            letterSpacing = 1.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            number.toString(),
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
