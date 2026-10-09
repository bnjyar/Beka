package dev.beka.overlay

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.beka.chat.AgentStatus
import dev.beka.chat.ChatLine
import dev.beka.chat.ChatSession
import dev.beka.chat.LineKind
import dev.beka.llm.Role
import dev.beka.rules.Callout
import dev.beka.rules.Level
import dev.beka.ui.Beka
import dev.beka.ui.BekaIcons
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.roundToInt

/** Seconds without a touch before the bubble fades and tucks into the screen edge. */
private const val BUBBLE_IDLE_MS = 3_000L
private const val BUBBLE_IDLE_ALPHA = 0.4f

/** How far an idle bubble slides past the screen edge; the window clips the rest. */
private val BUBBLE_PEEK = 26.dp

/**
 * The floating "B". After a few quiet seconds it fades and half hides at the edge it is docked to;
 * it comes back fully while Beka is busy, while a panel/strip/note is showing, and on any touch.
 */
@Composable
fun Bubble(
    busy: StateFlow<Boolean>,
    active: StateFlow<Boolean>,
    dockedRight: StateFlow<Boolean>,
    onTap: () -> Unit,
    onDrag: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    val isBusy by busy.collectAsState()
    val isActive by active.collectAsState()
    val right by dockedRight.collectAsState()
    var touchedAt by remember { mutableLongStateOf(0L) }
    var pressed by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    var idle by remember { mutableStateOf(false) }

    LaunchedEffect(isBusy, isActive, pressed, dragging, touchedAt) {
        idle = false
        if (isBusy || isActive || pressed || dragging) return@LaunchedEffect
        delay(BUBBLE_IDLE_MS)
        idle = true
    }

    val alpha by animateFloatAsState(if (idle) BUBBLE_IDLE_ALPHA else 1f, tween(if (idle) 600 else 150), label = "alpha")
    val peek = with(LocalDensity.current) { BUBBLE_PEEK.toPx() }
    val shift by animateFloatAsState(
        targetValue = if (idle) (if (right) peek else -peek) else 0f,
        animationSpec = spring(dampingRatio = 0.9f, stiffness = 380f),
        label = "peek",
    )
    val scale by animateFloatAsState(if (pressed) 0.9f else 1f, spring(dampingRatio = 0.6f, stiffness = 800f), label = "press")

    Box(
        modifier = Modifier
            .offset { IntOffset(shift.roundToInt(), 0) }
            .graphicsLayer { this.alpha = alpha }
            .padding(6.dp)
            .size(52.dp)
            .scale(scale)
            .shadow(6.dp, CircleShape)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        tryAwaitRelease()
                        pressed = false
                        touchedAt = System.currentTimeMillis()
                    },
                    onTap = { onTap() },
                )
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { dragging = true },
                    onDragEnd = {
                        dragging = false
                        touchedAt = System.currentTimeMillis()
                        onDragEnd()
                    },
                    onDragCancel = {
                        dragging = false
                        onDragEnd()
                    },
                ) { change, amount ->
                    change.consume()
                    onDrag(amount.x, amount.y)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (isBusy) {
            CircularProgressIndicator(
                modifier = Modifier.size(28.dp),
                color = MaterialTheme.colorScheme.onPrimary,
                strokeWidth = 3.dp,
            )
        } else {
            Text("B", color = MaterialTheme.colorScheme.onPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** The panel rises from the bottom edge with a soft spring and drops away quickly. */
val PanelEnter: EnterTransition =
    slideInVertically(spring(dampingRatio = 0.9f, stiffness = 700f, visibilityThreshold = IntOffset(1, 1))) { it / 4 } +
        fadeIn(spring(stiffness = 1600f)) +
        scaleIn(spring(dampingRatio = 0.9f, stiffness = 700f), initialScale = 0.96f, transformOrigin = TransformOrigin(0.5f, 1f))

val PanelExit: ExitTransition =
    slideOutVertically(tween(160, easing = FastOutLinearInEasing)) { it / 4 } +
        fadeOut(tween(140)) +
        scaleOut(tween(160, easing = FastOutLinearInEasing), targetScale = 0.96f, transformOrigin = TransformOrigin(0.5f, 1f))

/** The strip drops in from the top edge. */
val StripEnter: EnterTransition =
    slideInVertically(spring(dampingRatio = 0.85f, stiffness = 700f, visibilityThreshold = IntOffset(1, 1))) { -it } +
        fadeIn(spring(stiffness = 1600f))

/** What the chat list shows: messages, plus each run of action lines folded into one row. */
private sealed interface ChatItem {
    val key: Int

    data class Message(override val key: Int, val line: ChatLine) : ChatItem
    data class Steps(override val key: Int, val lines: List<ChatLine>) : ChatItem
    data class Note(override val key: Int, val line: ChatLine) : ChatItem
}

private fun group(lines: List<ChatLine>): List<ChatItem> {
    val items = ArrayList<ChatItem>()
    var steps: MutableList<ChatLine>? = null
    var start = 0
    lines.forEachIndexed { i, line ->
        if (line.kind == LineKind.ACTION) {
            if (steps == null) {
                steps = ArrayList()
                start = i
            }
            steps!!.add(line)
            return@forEachIndexed
        }
        steps?.let { items.add(ChatItem.Steps(start, it)) }
        steps = null
        items.add(if (line.kind == LineKind.MEMORY) ChatItem.Note(i, line) else ChatItem.Message(i, line))
    }
    steps?.let { items.add(ChatItem.Steps(start, it)) }
    return items
}

private fun ago(at: Long, now: Long): String {
    val s = ((now - at) / 1000).coerceAtLeast(0)
    return when {
        s < 5 -> "baru dibaca"
        s < 60 -> "$s dtk lalu"
        else -> "${s / 60} mnt lalu"
    }
}

@Composable
fun ChatPanel(chat: ChatSession, heightPx: Int, onClose: () -> Unit, onRefresh: () -> Unit, onReport: (String) -> Unit) {
    val lines by chat.lines.collectAsState()
    val busy by chat.busy.collectAsState()
    val screen by chat.screen.collectAsState()
    val withShot by chat.includeScreenshot.collectAsState()
    val actions by chat.actionsEnabled.collectAsState()
    var input by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var typing by remember { mutableStateOf(false) }
    val expanded = remember { mutableStateMapOf<Int, Boolean>() }
    val listState = rememberLazyListState()
    val focus = LocalFocusManager.current
    val colors = MaterialTheme.colorScheme
    val beka = Beka.colors
    val height = with(LocalDensity.current) { heightPx.toDp() }
    val chatItems = remember(lines) { group(lines) }
    // The header lights up for a moment after each screen read, so "Baca ulang" visibly did something.
    var fresh by remember { mutableStateOf(false) }
    LaunchedEffect(screen?.capturedAt) {
        fresh = true
        delay(1_500)
        fresh = false
    }
    val headerColor by animateColorAsState(if (fresh) colors.primary else colors.onSurfaceVariant, tween(300), label = "header")
    // Step groups are keyed by line index, which a new chat reuses.
    LaunchedEffect(lines.isEmpty()) { if (lines.isEmpty()) expanded.clear() }
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(1_000)
            value = System.currentTimeMillis()
        }
    }
    // Follow new text unless the user scrolled up to read; reaching the bottom again resumes following.
    var follow by remember { mutableStateOf(true) }
    val send = {
        chat.send(input.trim())
        input = ""
        follow = true
        // Over Settings the keyboard covers the navigation buttons, so it never stays up after sending.
        focus.clearFocus()
    }

    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }.collect { (scrolling, more) ->
            if (!more) follow = true else if (scrolling && listState.lastScrolledBackward) follow = false
        }
    }
    LaunchedEffect(chatItems.size, lines.lastOrNull()?.text?.length, busy) {
        if (follow && chatItems.isNotEmpty()) {
            listState.scrollToItem(listState.layoutInfo.totalItemsCount.coerceAtLeast(1) - 1)
            listState.scrollBy(100_000f)
        }
    }

    Box(Modifier.fillMaxWidth().padding(8.dp)) {
        Surface(
            // Shrinks to fit above the keyboard: the window wraps this and is capped by its frame.
            modifier = Modifier.fillMaxWidth().height(height),
            shape = RoundedCornerShape(28.dp),
            color = beka.panel,
            contentColor = colors.onSurface,
            shadowElevation = 12.dp,
        ) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(36.dp, 4.dp).clip(CircleShape).background(colors.outlineVariant))
                }
                Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(if (screen != null) colors.primary else colors.error))
                    Spacer(Modifier.size(8.dp))
                    Text(
                        screen?.let { "${it.appLabel} · ${ago(it.capturedAt, now)}" } ?: "Layar tidak terbaca (cek Aksesibilitas)",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = headerColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    PanelIcon(BekaIcons.NewChat, "Chat baru") { chat.clear() }
                    PanelIcon(BekaIcons.Refresh, "Baca ulang layar", onRefresh)
                    PanelIcon(BekaIcons.More, "Menu") { menu = !menu }
                    PanelIcon(BekaIcons.Close, "Tutup", onClose)
                }
                AnimatedVisibility(menu, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { onReport(input); menu = false }) { Text("Laporkan") }
                    }
                }

                if (chatItems.isEmpty() && !busy) {
                    Box(Modifier.weight(1f).fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text(
                            if (actions) "Tanya soal layar ini, atau suruh Beka ngerjain sesuatu di sini."
                            else "Tanya apa aja soal layar ini.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(chatItems, key = { it.key }) { item ->
                            when (item) {
                                is ChatItem.Message -> ChatMessage(item.line)
                                is ChatItem.Note -> NoteLine(item.line)
                                is ChatItem.Steps -> StepsRow(
                                    lines = item.lines,
                                    running = busy && item === chatItems.last(),
                                    open = expanded[item.key] == true,
                                    onToggle = { expanded[item.key] = expanded[item.key] != true },
                                )
                            }
                        }
                        val last = chatItems.lastOrNull()
                        if (busy && !(last is ChatItem.Message && last.line.role == Role.ASSISTANT)) {
                            item(key = -1) { Thinking() }
                        }
                    }
                }

                Row(
                    Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (typing) {
                        PanelIcon(BekaIcons.ChevronDown, "Tutup keyboard") { focus.clearFocus() }
                    } else {
                        ToggleIcon(BekaIcons.Camera, "Screenshot", withShot) { chat.includeScreenshot.value = !withShot }
                        ToggleIcon(BekaIcons.Bolt, "Aksi", actions) { chat.actionsEnabled.value = !actions }
                    }
                    Row(
                        Modifier.weight(1f).heightIn(min = 46.dp).clip(RoundedCornerShape(23.dp)).background(beka.field)
                            .padding(start = 16.dp, end = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.weight(1f).padding(vertical = 12.dp)) {
                            if (input.isEmpty()) {
                                Text(
                                    if (actions) "Tanya atau suruh Beka…" else "Tanya soal layar ini…",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = colors.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                            BasicTextField(
                                value = input,
                                onValueChange = { input = it },
                                modifier = Modifier.fillMaxWidth().onFocusChanged { typing = it.isFocused },
                                textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
                                cursorBrush = SolidColor(colors.primary),
                                maxLines = 4,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                keyboardActions = KeyboardActions(onSend = { if (input.isNotBlank() && !busy) send() }),
                            )
                        }
                        if (busy) {
                            RoundAction(BekaIcons.Stop, "Stop", enabled = true, onClick = chat::stop)
                        } else {
                            RoundAction(BekaIcons.ArrowUp, "Kirim", enabled = input.isNotBlank(), onClick = send)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PanelIcon(icon: ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A round on/off switch: filled while on. */
@Composable
private fun ToggleIcon(icon: ImageVector, label: String, on: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier.size(40.dp).clip(CircleShape)
            .background(if (on) Beka.colors.chip else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClickLabel = "$label ${if (on) "matikan" else "nyalakan"}", onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = "$label ${if (on) "nyala" else "mati"}",
            modifier = Modifier.size(19.dp),
            tint = if (on) colors.onSurface else colors.outline,
        )
    }
}

@Composable
private fun RoundAction(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier.size(36.dp).clip(CircleShape)
            .background(if (enabled) colors.primary else Beka.colors.chip)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(18.dp), tint = if (enabled) colors.onPrimary else colors.outline)
    }
}

@Composable
private fun ChatMessage(line: ChatLine) {
    val colors = MaterialTheme.colorScheme
    if (line.role == Role.USER) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(
                color = Beka.colors.chip,
                contentColor = colors.onSurface,
                shape = RoundedCornerShape(18.dp, 18.dp, 6.dp, 18.dp),
                modifier = Modifier.widthIn(max = 300.dp),
            ) {
                SelectionContainer {
                    Text(
                        line.text,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
    } else {
        SelectionContainer {
            Text(
                line.text.ifEmpty { "…" },
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodyLarge,
                color = if (line.error) colors.error else colors.onSurface,
            )
        }
    }
}

/** A run of agent actions as one row ("3 langkah"); tap to see each step. */
@Composable
private fun StepsRow(lines: List<ChatLine>, running: Boolean, open: Boolean, onToggle: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val failed = lines.any { it.error }
    val label = buildString {
        append(lines.size).append(" langkah")
        when {
            running -> append("…")
            failed -> append(" · ada yang gagal")
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier.clip(RoundedCornerShape(16.dp)).background(Beka.colors.field).clickable(onClick = onToggle)
                .padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (open) BekaIcons.ChevronDown else BekaIcons.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = colors.onSurfaceVariant,
            )
            Spacer(Modifier.size(4.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, color = if (failed) colors.error else colors.onSurfaceVariant)
        }
        AnimatedVisibility(open, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                lines.forEach { NoteLine(it) }
            }
        }
    }
}

@Composable
private fun NoteLine(line: ChatLine) {
    Text(
        line.text,
        modifier = Modifier.fillMaxWidth(),
        style = MaterialTheme.typography.bodySmall,
        color = if (line.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Thinking() {
    val pulse by rememberInfiniteTransition(label = "thinking").animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "pulse",
    )
    Text(
        "Beka lagi mikir…",
        modifier = Modifier.graphicsLayer { alpha = pulse },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * The agent's status at the top of the screen. While it works: a small dark pill (step, what it is
 * doing, Stop) that leaves the app visible. It widens into a card to ask for permission or to show
 * the result.
 */
@Composable
fun AgentStrip(
    status: StateFlow<AgentStatus?>,
    onStop: () -> Unit,
    onAnswer: (Boolean) -> Unit,
    onOpenChat: () -> Unit,
    onDismiss: () -> Unit,
) {
    val current by status.collectAsState()
    val s = current ?: return
    val colors = MaterialTheme.colorScheme
    val beka = Beka.colors
    val confirm = s.confirm
    val pill = confirm == null && !s.done
    Box(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), contentAlignment = Alignment.TopCenter) {
        Surface(
            modifier = (if (pill) Modifier.widthIn(max = 340.dp) else Modifier.fillMaxWidth()).animateContentSize(spring(dampingRatio = 0.9f, stiffness = 700f)),
            shape = RoundedCornerShape(if (pill) 22.dp else 24.dp),
            color = colors.inverseSurface,
            contentColor = colors.inverseOnSurface,
            shadowElevation = 8.dp,
        ) {
            when {
                pill -> Row(
                    Modifier.height(44.dp).padding(start = 14.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(16.dp), color = colors.inversePrimary, strokeWidth = 2.dp)
                    Text("${s.step}", style = MaterialTheme.typography.labelMedium, color = colors.inverseOnSurface.copy(alpha = 0.6f))
                    Text(
                        s.text.removePrefix("… "),
                        modifier = Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Box(
                        Modifier.size(36.dp).clip(CircleShape).background(beka.inverseRaised).clickable(onClickLabel = "Stop", onClick = onStop),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(BekaIcons.Stop, contentDescription = "Stop", modifier = Modifier.size(18.dp), tint = colors.inverseOnSurface)
                    }
                }
                confirm != null -> Column(Modifier.padding(start = 16.dp, end = 14.dp, top = 14.dp, bottom = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(BekaIcons.Warning, contentDescription = null, modifier = Modifier.size(18.dp), tint = colors.inversePrimary)
                        Spacer(Modifier.size(8.dp))
                        Text("Izinkan Beka?", style = MaterialTheme.typography.labelLarge, color = colors.inversePrimary)
                    }
                    Spacer(Modifier.size(6.dp))
                    Text(
                        confirm.description + if (confirm.appLabel.isNotEmpty()) " di ${confirm.appLabel}" else "",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onStop) { Text("Stop", color = colors.inverseOnSurface.copy(alpha = 0.7f)) }
                        Spacer(Modifier.weight(1f))
                        OutlinedButton(
                            onClick = { onAnswer(false) },
                            border = androidx.compose.foundation.BorderStroke(1.dp, beka.inverseRaised),
                        ) { Text("Tolak", color = colors.inverseOnSurface) }
                        Spacer(Modifier.size(8.dp))
                        Button(
                            onClick = { onAnswer(true) },
                            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                containerColor = colors.inversePrimary,
                                contentColor = beka.onInversePrimary,
                            ),
                        ) { Text("Izinkan") }
                    }
                }
                else -> Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 6.dp)) {
                    SelectionContainer {
                        Text(s.text, style = MaterialTheme.typography.bodyLarge, maxLines = 6, overflow = TextOverflow.Ellipsis)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onOpenChat) { Text("Buka chat", color = colors.inversePrimary) }
                        TextButton(onClick = onDismiss) { Text("Tutup", color = colors.inverseOnSurface.copy(alpha = 0.7f)) }
                    }
                }
            }
        }
    }
}

/** Width of a rule note's card; the window adds the tail and room for the shadow. */
val CALLOUT_WIDTH = 280.dp
val CALLOUT_TAIL = 7.dp
val CALLOUT_PAD = 8.dp

/** How far the tail's centre sits from the card's top (or bottom) edge. */
val CALLOUT_TAIL_INSET = 28.dp

/**
 * A proactive rule's note beside the bubble, with a small tail pointing at it. [tailLeft] = the
 * bubble is on the left; [tailTop] = the tail sits near the top edge (bubble in the upper half).
 */
@Composable
fun RuleCallout(callout: Callout, tailLeft: Boolean, tailTop: Boolean, onAccept: () -> Unit, onClose: () -> Unit, onWrong: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val panel = Beka.colors.panel
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val appear by animateFloatAsState(if (shown) 1f else 0f, spring(dampingRatio = 0.8f, stiffness = 700f), label = "callout")
    Box(
        Modifier.padding(CALLOUT_PAD).graphicsLayer {
            alpha = appear
            scaleX = 0.9f + 0.1f * appear
            scaleY = 0.9f + 0.1f * appear
            transformOrigin = TransformOrigin(if (tailLeft) 0f else 1f, if (tailTop) 0f else 1f)
        },
    ) {
        Surface(
            modifier = Modifier.padding(start = if (tailLeft) CALLOUT_TAIL else 0.dp, end = if (tailLeft) 0.dp else CALLOUT_TAIL)
                .width(CALLOUT_WIDTH),
            shape = RoundedCornerShape(20.dp),
            color = panel,
            contentColor = colors.onSurface,
            shadowElevation = 8.dp,
        ) {
            Column(Modifier.padding(start = 16.dp, end = 6.dp, top = 8.dp, bottom = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(BekaIcons.Bolt, contentDescription = null, modifier = Modifier.size(16.dp), tint = colors.primary)
                    Spacer(Modifier.size(8.dp))
                    Text(
                        callout.ruleName,
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    PanelIcon(BekaIcons.Close, "Tutup", onClose)
                }
                Text(
                    callout.text,
                    modifier = Modifier.padding(end = 10.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onWrong) {
                        Text("Salah trigger", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                    }
                    Spacer(Modifier.weight(1f))
                    if (callout.level == Level.SUGGEST) {
                        TextButton(onClick = onClose) { Text("Nanti") }
                        Button(onClick = onAccept) { Text("Lakukan") }
                    } else {
                        Button(onClick = onClose) { Text("OK") }
                    }
                }
            }
        }
        // The tail: a square turned 45°, half of it tucked under the card's edge.
        Box(
            Modifier
                .align(
                    when {
                        tailLeft && tailTop -> Alignment.TopStart
                        tailLeft -> Alignment.BottomStart
                        tailTop -> Alignment.TopEnd
                        else -> Alignment.BottomEnd
                    },
                )
                .offset(y = if (tailTop) CALLOUT_TAIL_INSET - 7.dp else -(CALLOUT_TAIL_INSET - 7.dp))
                .size(14.dp)
                .graphicsLayer { rotationZ = 45f }
                .background(panel),
        )
    }
}
