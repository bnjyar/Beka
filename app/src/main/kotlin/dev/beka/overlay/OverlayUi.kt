package dev.beka.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.beka.chat.ChatSession
import dev.beka.llm.Role
import kotlinx.coroutines.flow.StateFlow

@Composable
fun Bubble(busy: StateFlow<Boolean>, onTap: () -> Unit, onDrag: (Float, Float) -> Unit) {
    val isBusy by busy.collectAsState()
    Box(
        modifier = Modifier
            .padding(6.dp)
            .size(52.dp)
            .shadow(6.dp, CircleShape)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
            .pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) }
            .pointerInput(Unit) {
                detectDragGestures { change, amount ->
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

@Composable
fun ChatPanel(chat: ChatSession, onClose: () -> Unit, onRefresh: () -> Unit, onReport: (String) -> Unit) {
    val lines by chat.lines.collectAsState()
    val busy by chat.busy.collectAsState()
    val screen by chat.screen.collectAsState()
    val withShot by chat.includeScreenshot.collectAsState()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(lines.size, lines.lastOrNull()?.text?.length) {
        if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        tonalElevation = 6.dp,
        shadowElevation = 12.dp,
    ) {
        Column(Modifier.fillMaxSize().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Beka", style = MaterialTheme.typography.titleMedium)
                    Text(
                        screen?.let { "${it.appLabel} · ${it.nodeCount} elemen" + if (it.screenshotJpeg != null) " · screenshot" else "" }
                            ?: "Layar tidak terbaca (cek Accessibility)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TextButton(onClick = onRefresh) { Text("Baca ulang") }
                TextButton(onClick = onClose) { Text("Tutup") }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(
                    selected = withShot,
                    onClick = { chat.includeScreenshot.value = !withShot },
                    label = { Text("Screenshot") },
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { chat.clear() }) { Text("Baru") }
                TextButton(onClick = { onReport(input) }) { Text("Laporkan") }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(lines) { _, line -> ChatBubble(line.role, line.text, line.error) }
            }

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Tanya soal layar ini…") },
                    maxLines = 4,
                )
                Spacer(Modifier.size(8.dp))
                if (busy) {
                    OutlinedButton(onClick = { chat.stop() }) { Text("Stop") }
                } else {
                    Button(
                        onClick = {
                            chat.send(input.trim())
                            input = ""
                        },
                        enabled = input.isNotBlank(),
                    ) { Text("Kirim") }
                }
            }
        }
    }
}

@Composable
private fun ChatBubble(role: Role, text: String, error: Boolean) {
    val mine = role == Role.USER
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Surface(
            color = when {
                error -> colors.errorContainer
                mine -> colors.primaryContainer
                else -> colors.surfaceVariant
            },
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            SelectionContainer {
                Text(
                    text.ifEmpty { "…" },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
