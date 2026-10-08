package dev.beka.memory

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import dev.beka.BekaApp
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class Tab { FACT, EPISODE, PROCEDURE, REVIEW }

@Composable
fun MemoryScreen(app: BekaApp, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val store = app.memoryStore
    val scope = rememberCoroutineScope()
    val enabled by app.memorySettings.enabled.collectAsState()
    val autoExtract by app.memorySettings.autoExtract.collectAsState()
    val changes by store.changes.collectAsState()
    var all by remember { mutableStateOf<List<MemoryItem>>(emptyList()) }
    var tab by remember { mutableStateOf(Tab.FACT) }
    var editingId by remember { mutableStateOf<Long?>(null) }
    var deleting by remember { mutableStateOf<MemoryItem?>(null) }
    var newFact by remember { mutableStateOf("") }

    LaunchedEffect(changes) { all = store.all() }

    val live = remember(all) { all.live() }
    val review = remember(all) { all.filter { it.pending }.sortedByDescending { it.createdAt } }
    // Fall back to the first tab once the review queue has been emptied.
    val current = if (tab == Tab.REVIEW && review.isEmpty()) Tab.FACT else tab
    val shown = when (current) {
        Tab.FACT -> live.filter { it.kind == MemoryKind.FACT }
        Tab.EPISODE -> live.filter { it.kind == MemoryKind.EPISODE }
        Tab.PROCEDURE -> live.filter { it.kind == MemoryKind.PROCEDURE }
        Tab.REVIEW -> review
    }.sortedByDescending { it.createdAt }

    Surface(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.safeDrawingPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onBack) { Text("‹ Kembali") }
                    Text("Memory", style = MaterialTheme.typography.headlineMedium)
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Memory aktif")
                            Text("Semua memory disimpan di HP ini saja.", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = enabled, onCheckedChange = app.memorySettings::setEnabled)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Ingat otomatis dari percakapan")
                            Text(
                                "Di akhir percakapan, Beka mencatat hal yang layak diingat.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(checked = autoExtract, onCheckedChange = app.memorySettings::setAutoExtract)
                    }
                }
            }
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(current == Tab.FACT, { tab = Tab.FACT; editingId = null }, { Text("Fakta") })
                    FilterChip(current == Tab.EPISODE, { tab = Tab.EPISODE; editingId = null }, { Text("Episode") })
                    FilterChip(current == Tab.PROCEDURE, { tab = Tab.PROCEDURE; editingId = null }, { Text("Prosedur") })
                    if (review.isNotEmpty()) {
                        FilterChip(current == Tab.REVIEW, { tab = Tab.REVIEW; editingId = null }, { Text("Perlu review (${review.size})") })
                    }
                }
            }
            if (current == Tab.FACT) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            newFact, { newFact = it }, Modifier.weight(1f),
                            label = { Text("Tambah fakta") },
                        )
                        Button(
                            onClick = {
                                val text = newFact.trim()
                                newFact = ""
                                scope.launch {
                                    store.add(
                                        MemoryItem(
                                            kind = MemoryKind.FACT,
                                            content = text,
                                            source = MemorySource.USER,
                                            createdAt = System.currentTimeMillis(),
                                        ),
                                    )
                                }
                            },
                            enabled = newFact.isNotBlank(),
                        ) { Text("Tambah") }
                    }
                }
            }
            if (shown.isEmpty()) {
                item { Text(emptyText(current), style = MaterialTheme.typography.bodyMedium) }
            }
            items(shown, key = { it.id }) { item ->
                MemoryCard(
                    item = item,
                    editing = editingId == item.id,
                    onEdit = { editingId = item.id },
                    onCancelEdit = { editingId = null },
                    onSaveEdit = { text ->
                        editingId = null
                        scope.launch {
                            store.update(
                                item.copy(content = text.trim(), source = MemorySource.USER, confidence = 1.0, pending = false),
                            )
                        }
                    },
                    onPin = { scope.launch { store.update(item.copy(pinned = !item.pinned)) } },
                    onApprove = { scope.launch { store.update(item.copy(pending = false)) } },
                    onDelete = {
                        if (item.pending) {
                            scope.launch { store.delete(listOf(item.id)) }
                        } else {
                            deleting = item
                        }
                    },
                )
            }
        }
    }

    deleting?.let { target ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Hapus memory ini?") },
            text = { Text(target.content) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    scope.launch { store.delete(all.withHistory(target.id)) }
                }) { Text("Hapus") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Batal") } },
        )
    }
}

@Composable
private fun MemoryCard(
    item: MemoryItem,
    editing: Boolean,
    onEdit: () -> Unit,
    onCancelEdit: () -> Unit,
    onSaveEdit: (String) -> Unit,
    onPin: () -> Unit,
    onApprove: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (editing) {
                var text by remember { mutableStateOf(item.content) }
                OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onSaveEdit(text) }, enabled = text.isNotBlank()) { Text("Simpan") }
                    TextButton(onClick = onCancelEdit) { Text("Batal") }
                }
            } else {
                Text(item.content)
                Text(meta(item), style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (item.pending) {
                        TextButton(onClick = onApprove) { Text("Simpan") }
                        TextButton(onClick = onEdit) { Text("Edit") }
                        TextButton(onClick = onDelete) { Text("Buang") }
                    } else {
                        if (item.kind == MemoryKind.FACT) {
                            TextButton(onClick = onPin) { Text(if (item.pinned) "Lepas pin" else "Pin") }
                        }
                        TextButton(onClick = onEdit) { Text("Edit") }
                        TextButton(onClick = onDelete) { Text("Hapus") }
                    }
                }
            }
        }
    }
}

private fun meta(item: MemoryItem): String {
    val date = SimpleDateFormat("d MMM yyyy", Locale("id")).format(Date(item.createdAt))
    val source = when (item.source) {
        MemorySource.USER -> "kamu"
        MemorySource.EXTRACTED -> "dari percakapan"
        MemorySource.AGENT -> "dicatat Beka"
    }
    return listOfNotNull(
        date,
        source,
        item.appPackage,
        if (item.useCount > 0) "dipakai ${item.useCount}×" else null,
    ).joinToString(" · ")
}

private fun emptyText(tab: Tab) = when (tab) {
    Tab.FACT -> "Belum ada fakta. Beka akan mengingat hal tentang kamu dari percakapan, atau tambah sendiri di atas."
    Tab.EPISODE -> "Belum ada episode. Ringkasan hal yang pernah kamu minta ke Beka akan muncul di sini."
    Tab.PROCEDURE -> "Belum ada prosedur. Cara Beka mengoperasikan sebuah app akan muncul di sini setelah ia belajar."
    Tab.REVIEW -> "Tidak ada yang perlu direview."
}
