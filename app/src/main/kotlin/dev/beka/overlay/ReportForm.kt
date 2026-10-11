package dev.beka.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import dev.beka.debug.DebugReporter
import dev.beka.ui.Beka

/** What the report form hands back: the two texts and which parts of the bundle to include. */
data class ReportRequest(val happened: String, val expected: String, val include: DebugReporter.Include)

/** The report form shown in place of the chat: two texts, what to attach, and a privacy warning. */
@Composable
fun ReportForm(modifier: Modifier = Modifier, onSubmit: (ReportRequest) -> Unit) {
    val colors = MaterialTheme.colorScheme
    var happened by remember { mutableStateOf("") }
    var expected by remember { mutableStateOf("") }
    var screenText by remember { mutableStateOf(false) }
    var screenshot by remember { mutableStateOf(false) }
    var logs by remember { mutableStateOf(true) }
    var steps by remember { mutableStateOf(true) }

    Column(
        modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Laporkan masalah", style = MaterialTheme.typography.titleMedium)
        ReportField("Apa yang terjadi?", happened, { happened = it }, "Wajib diisi")
        ReportField("Seharusnya bagaimana?", expected, { expected = it }, "Opsional")
        Text("Sertakan di bundel", style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
        ReportCheck("Teks layar", screenText) { screenText = it }
        ReportCheck("Screenshot", screenshot) { screenshot = it }
        ReportCheck("Log debug", logs) { logs = it }
        ReportCheck("Langkah terakhir", steps) { steps = it }
        Text(
            "Bundel bisa memuat teks obrolan, isi layar, nama aplikasi, dan teks notifikasi. " +
                "Periksa dulu sebelum melampirkannya ke issue GitHub yang publik. Tidak ada yang diunggah otomatis.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.error,
        )
        Text(
            "Masalah yang susah diulang, atau Beka sempat tertutup sendiri? Nyalakan Setelan → Lainnya → Rekam log debug, " +
                "ulangi sampai masalahnya muncul, lalu laporkan lagi.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )
        Button(
            onClick = { onSubmit(ReportRequest(happened.trim(), expected.trim(), DebugReporter.Include(screenText, screenshot, logs, steps))) },
            enabled = happened.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Simpan & buka GitHub") }
    }
}

@Composable
private fun ReportField(label: String, value: String, onChange: (String) -> Unit, hint: String) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
        Box(
            Modifier.fillMaxWidth().heightIn(min = 72.dp).clip(RoundedCornerShape(16.dp)).background(Beka.colors.field)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            if (value.isEmpty()) {
                Text(hint, style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
            }
            BasicTextField(
                value = value,
                onValueChange = onChange,
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
                cursorBrush = SolidColor(colors.primary),
            )
        }
    }
}

@Composable
private fun ReportCheck(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
