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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.beka.BekaApp
import dev.beka.R
import dev.beka.SettingsActions
import dev.beka.accessibility.BekaAccessibilityService
import dev.beka.overlay.OverlayService

private const val STEPS = 6
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
    val skippable: Boolean = true,
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
    val batteryOk = remember(resumeTick) { actions.hasBatteryExemption() }
    val micOk = remember(resumeTick) { actions.hasMicPermission() }
    val oem = remember { Oem.current() }

    fun finish() = app.appearance.setOnboarded(true)
    fun next() { step = (step + 1).coerceAtMost(DONE) }

    // Move on by itself once the setting for the current step is granted.
    val stepDone = when (step) {
        1 -> overlayOk
        2 -> a11y
        3 -> notifOk && notifAccess
        4 -> batteryOk
        5 -> micOk
        6 -> providers.isNotEmpty()
        else -> false
    }
    LaunchedEffect(step, stepDone) { if (stepDone) next() }

    val steps = listOf(
        Step(
            BekaIcons.Layers, stringResource(R.string.onb_overlay_title),
            stringResource(R.string.onb_overlay_reason),
            listOf(stringResource(R.string.onb_overlay_check1), stringResource(R.string.onb_overlay_check2)),
            listOf(stringResource(R.string.onb_tap_button), stringResource(R.string.onb_overlay_how2), stringResource(R.string.onb_come_back)),
            listOf(StepAction(stringResource(R.string.onb_open_settings), actions::requestOverlayPermission)),
        ),
        Step(
            BekaIcons.Eye, stringResource(R.string.onb_a11y_title),
            stringResource(R.string.onb_a11y_reason),
            listOf(
                stringResource(R.string.onb_a11y_check1),
                stringResource(R.string.onb_a11y_check2),
                stringResource(R.string.onb_a11y_check3),
            ),
            listOf(stringResource(R.string.onb_tap_button), stringResource(R.string.onb_a11y_how2), stringResource(R.string.onb_a11y_how3)),
            listOf(StepAction(stringResource(R.string.onb_open_a11y), actions::openAccessibilitySettings)),
            restrictedHint = true,
        ),
        Step(
            BekaIcons.Bell, stringResource(R.string.onb_notif_title),
            stringResource(R.string.onb_notif_reason),
            listOf(
                stringResource(R.string.onb_notif_check1),
                stringResource(R.string.onb_notif_check2),
            ),
            emptyList(),
            buildList {
                if (!notifOk) add(StepAction(stringResource(R.string.onb_allow_notif), actions::requestNotificationPermission))
                if (!notifAccess) add(StepAction(stringResource(R.string.onb_open_notif_access), actions::openNotificationAccessSettings, primary = notifOk))
            },
            restrictedHint = true,
        ),
        Step(
            BekaIcons.Bolt, stringResource(R.string.onb_battery_title),
            stringResource(R.string.onb_battery_reason) + (oem?.let { "\n\n" + stringResource(it.hint) } ?: ""),
            emptyList(),
            emptyList(),
            buildList {
                add(StepAction(stringResource(R.string.allow), actions::requestBatteryExemption))
                if (OemHints.showsSettingsButton(oem)) add(StepAction(stringResource(R.string.onb_open_phone_settings), actions::openOemSettings, primary = false))
            },
        ),
        Step(
            BekaIcons.Mic, stringResource(R.string.onb_mic_title),
            stringResource(R.string.onb_mic_reason),
            listOf(
                stringResource(R.string.onb_mic_check1),
                stringResource(R.string.onb_mic_check2),
            ),
            emptyList(),
            listOf(StepAction(stringResource(R.string.onb_allow_mic), actions::requestMicPermission)),
        ),
        Step(
            BekaIcons.Sparkle, stringResource(R.string.onb_model_title),
            stringResource(R.string.onb_model_reason),
            listOf(
                stringResource(R.string.onb_model_check1),
                stringResource(R.string.onb_model_check2),
            ),
            listOf(stringResource(R.string.onb_model_how1), stringResource(R.string.onb_model_how2), stringResource(R.string.onb_model_how3)),
            listOf(
                StepAction(stringResource(R.string.login_openrouter), actions::loginOpenRouter),
                StepAction(stringResource(R.string.onb_model_later), ::next, primary = false),
            ),
            skippable = false,
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
                DONE -> Finished(hasProvider = providers.isNotEmpty(), onStart = {
                    if (overlayOk) OverlayService.start(context)
                    finish()
                })
                else -> StepPage(s, steps[s - 1], onSkipStep = ::next, onSkipAll = ::finish, onOpenAppInfo = actions::openAppInfo, showHint = when (s) { 2 -> !a11y; 3 -> !notifAccess; else -> true })
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
            Text(stringResource(R.string.onb_hello), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.onb_intro),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        Button(onClick = onStart, Modifier.fillMaxWidth().height(52.dp)) { Text(stringResource(R.string.onb_start_setup)) }
        TextButton(onClick = onSkip) { Text(stringResource(R.string.skip)) }
    }
}

@Composable
private fun Finished(hasProvider: Boolean, onStart: () -> Unit) {
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
            Text(stringResource(R.string.onb_ready), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.onb_ready_desc),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (!hasProvider) {
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.onb_ready_no_model),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }
        }
        Button(onClick = onStart, Modifier.fillMaxWidth().height(52.dp)) { Text(stringResource(R.string.onb_start_beka)) }
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
                stringResource(R.string.onb_step_of, index, STEPS),
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
                    Text(stringResource(R.string.onb_how), style = MaterialTheme.typography.titleSmall)
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
                    stringResource(R.string.restricted_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onOpenAppInfo) { Text(stringResource(R.string.open_app_info)) }
            }
        }
        Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            step.actions.forEach { a ->
                val mod = Modifier.fillMaxWidth().height(52.dp)
                if (a.primary) Button(onClick = a.onClick, mod) { Text(a.label) }
                else OutlinedButton(onClick = a.onClick, mod) { Text(a.label) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                if (step.skippable) TextButton(onClick = onSkipStep) { Text(stringResource(R.string.onb_later)) } else Spacer(Modifier.size(1.dp))
                TextButton(onClick = onSkipAll) { Text(stringResource(R.string.skip)) }
            }
        }
    }
}
