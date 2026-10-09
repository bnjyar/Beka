package dev.beka.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.beka.BekaApp
import dev.beka.SettingsActions
import dev.beka.accessibility.BekaAccessibilityService
import dev.beka.overlay.OverlayService

private const val STEPS = 4
private const val DONE = STEPS + 1

private class StepAction(val label: String, val onClick: () -> Unit, val primary: Boolean = true)

private class Step(
    val icon: ImageVector,
    val title: String,
    val reason: String,
    val checks: List<String>,
    val howTo: List<String>,
    val actions: List<StepAction>,
    val restrictedHint: Boolean = false,
    val skipLabel: String? = "Nanti aja",
)

@Composable
fun Onboarding(app: BekaApp, resumeTick: Int, actions: SettingsActions) {
    val context = LocalContext.current
    var step by rememberSaveable { mutableIntStateOf(0) }
    val a11y by BekaAccessibilityService.connected.collectAsState()
    val providers by app.providers.providers.collectAsState()
    val overlayOk = remember(resumeTick) { actions.hasOverlayPermission() }
    val notifOk = remember(resumeTick) { actions.hasNotificationPermission() }
    val notifAccess = remember(resumeTick) { actions.hasNotificationAccess() }

    fun finish() = app.appearance.setOnboarded(true)
    fun next() { step = (step + 1).coerceAtMost(DONE) }

    // Move on by itself once the setting for the current step is granted.
    val stepDone = when (step) {
        1 -> overlayOk
        2 -> a11y
        3 -> notifOk && notifAccess
        4 -> providers.isNotEmpty()
        else -> false
    }
    LaunchedEffect(step, stepDone) { if (stepDone) next() }

    val steps = listOf(
        Step(
            BekaIcons.Layers, "Tampil di atas app lain",
            "Biar bubble Beka bisa melayang di atas app apa pun yang sedang kamu pakai.",
            listOf("Cuma buat menampilkan bubble dan panel obrolan", "Beka tidak merekam atau memotret layar lewat izin ini"),
            listOf("Tap tombol di bawah", "Cari Beka, lalu nyalakan \"Izinkan tampil di atas app lain\"", "Balik ke sini"),
            listOf(StepAction("Buka pengaturan", actions::requestOverlayPermission)),
        ),
        Step(
            BekaIcons.Eye, "Baca layar (Aksesibilitas)",
            "Beka pakai layanan Aksesibilitas buat lihat isi layar dan nge-tap tombol kalau kamu suruh.",
            listOf(
                "Layar cuma dibaca pas kamu buka panel atau rule kamu jalan",
                "Aplikasi bank, dompet digital & password nggak pernah disentuh",
                "Aksi berisiko selalu minta izin kamu dulu",
            ),
            listOf("Tap tombol di bawah", "Pilih \"Aplikasi terinstal\" → Beka", "Nyalakan, lalu balik ke sini"),
            listOf(StepAction("Buka Aksesibilitas", actions::openAccessibilitySettings)),
            restrictedHint = true,
        ),
        Step(
            BekaIcons.Bell, "Notifikasi",
            "Buat ngabarin kamu dari rule proaktif, dan tahu lagu atau video yang lagi diputar. Langkah ini boleh dilewati.",
            listOf(
                "Isi notifikasi cuma dibaca dari app yang punya rule notifikasi",
                "Tidak ada yang dikirim ke mana-mana kecuali kamu minta Beka bertanya ke model",
            ),
            emptyList(),
            buildList {
                if (!notifOk) add(StepAction("Izinkan notifikasi", actions::requestNotificationPermission))
                if (!notifAccess) add(StepAction("Buka akses notifikasi", actions::openNotificationAccessSettings, primary = notifOk))
            },
        ),
        Step(
            BekaIcons.Sparkle, "Model AI",
            "Beka butuh model AI buat mikir dan menjawab. Login OpenRouter paling cepat.",
            listOf(
                "Kunci API disimpan terenkripsi di HP ini",
                "Teks dan layar yang relevan dikirim ke model pilihanmu cuma pas kamu minta",
            ),
            listOf("Tap Login OpenRouter", "Setujui akses di browser", "Balik ke sini otomatis"),
            listOf(
                StepAction("Login OpenRouter", actions::loginOpenRouter),
                StepAction("Atur manual nanti di Setelan", ::next, primary = false),
            ),
            skipLabel = null,
        ),
    )

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        AnimatedContent(
            targetState = step,
            transitionSpec = {
                (slideInHorizontally { it / 4 } + fadeIn()) togetherWith (slideOutHorizontally { -it / 4 } + fadeOut())
            },
            label = "onboarding",
        ) { s ->
            when (s) {
                0 -> Welcome(onStart = { step = 1 }, onSkip = ::finish)
                DONE -> Finished(onStart = {
                    if (overlayOk) OverlayService.start(context)
                    finish()
                })
                else -> StepPage(s, steps[s - 1], onSkipStep = ::next, onSkipAll = ::finish, onOpenAppInfo = actions::openAppInfo, showHint = !a11y)
            }
        }
    }
}

@Composable
private fun BigB(size: Int) {
    Box(
        Modifier.size(size.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text("B", color = MaterialTheme.colorScheme.onPrimary, fontSize = (size * 0.45f).sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Welcome(onStart: () -> Unit, onSkip: () -> Unit) {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            BigB(112)
            Spacer(Modifier.height(28.dp))
            Text("Halo, aku Beka", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Text(
                "Asisten yang melayang di atas semua app. Aku bisa baca layar, jawab pertanyaan soal yang kamu lihat, dan ngerjain tugas kecil buat kamu.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        Button(onClick = onStart, Modifier.fillMaxWidth().height(52.dp)) { Text("Mulai setup") }
        TextButton(onClick = onSkip) { Text("Lewati") }
    }
}

@Composable
private fun Finished(onStart: () -> Unit) {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            BigB(96)
            Spacer(Modifier.height(28.dp))
            Text("Siap!", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Text(
                "Tap bubble B di pinggir layar kapan aja buat ngobrol atau nyuruh Beka.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        Button(onClick = onStart, Modifier.fillMaxWidth().height(52.dp)) { Text("Mulai Beka") }
    }
}

@Composable
private fun StepPage(
    index: Int,
    step: Step,
    onSkipStep: () -> Unit,
    onSkipAll: () -> Unit,
    onOpenAppInfo: () -> Unit,
    showHint: Boolean,
) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(STEPS) { i ->
                    Box(
                        Modifier
                            .weight(1f)
                            .height(4.dp)
                            .background(
                                if (i < index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                RoundedCornerShape(2.dp),
                            ),
                    )
                }
            }
            Text(
                "$index dari $STEPS",
                Modifier.padding(start = 12.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                Modifier.size(64.dp).background(Beka.colors.chip, RoundedCornerShape(20.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(step.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp))
            }
            Text(step.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(step.reason, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            PanelCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    step.checks.forEach { line ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(
                                BekaIcons.Check, contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp),
                            )
                            Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
            if (step.howTo.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Caranya", style = MaterialTheme.typography.titleSmall)
                    step.howTo.forEachIndexed { i, line ->
                        Text(
                            "${i + 1}. $line",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (step.restrictedHint && showHint) {
                Text(
                    "Kalau tidak bisa dinyalakan: App info → ⋮ → Izinkan setelan terbatas, lalu coba lagi.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onOpenAppInfo) { Text("Buka App info") }
            }
        }
        Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            step.actions.forEach { a ->
                val mod = Modifier.fillMaxWidth().height(52.dp)
                if (a.primary) Button(onClick = a.onClick, mod) { Text(a.label) }
                else OutlinedButton(onClick = a.onClick, mod) { Text(a.label) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                if (step.skipLabel != null) TextButton(onClick = onSkipStep) { Text(step.skipLabel) } else Spacer(Modifier.size(1.dp))
                TextButton(onClick = onSkipAll) { Text("Lewati") }
            }
        }
    }
}
