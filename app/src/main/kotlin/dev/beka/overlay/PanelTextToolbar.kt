package dev.beka.overlay

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.offset
import dev.beka.R
import dev.beka.ui.Beka

/**
 * Compose's own toolbar opens a floating ActionMode, which a window added by a Service can't show.
 * This one only records what to show; [Menu] draws it inside the panel's composition.
 */
class PanelTextToolbar : TextToolbar {
    private class Request(
        val rect: Rect,
        val onCopy: (() -> Unit)?,
        val onPaste: (() -> Unit)?,
        val onCut: (() -> Unit)?,
        val onSelectAll: (() -> Unit)?,
    )

    private var request by mutableStateOf<Request?>(null)

    override val status: TextToolbarStatus
        get() = if (request != null) TextToolbarStatus.Shown else TextToolbarStatus.Hidden

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
    ) {
        request = Request(rect, onCopyRequested, onPasteRequested, onCutRequested, onSelectAllRequested)
    }

    override fun hide() {
        request = null
    }

    /**
     * Draws the menu above the selection (below it when there is no room), clamped to the container.
     * [origin] is the container's position in the root, since the rect is in root coordinates.
     */
    @Composable
    fun Menu(origin: Offset, container: IntSize) {
        val r = request ?: return
        val items = listOfNotNull(
            r.onCut?.let { stringResource(R.string.action_cut) to it },
            r.onCopy?.let { stringResource(R.string.action_copy) to it },
            r.onPaste?.let { stringResource(R.string.action_paste) to it },
            r.onSelectAll?.let { stringResource(R.string.action_select_all) to it },
        )
        if (items.isEmpty()) return
        var size by remember { mutableStateOf(IntSize.Zero) }
        val gap = with(androidx.compose.ui.platform.LocalDensity.current) { 8.dp.toPx() }
        Surface(
            modifier = Modifier
                .offset {
                    val maxX = (container.width - size.width).coerceAtLeast(0)
                    val maxY = (container.height - size.height).coerceAtLeast(0)
                    val x = (r.rect.left - origin.x + (r.rect.width - size.width) / 2f).toInt().coerceIn(0, maxX)
                    val above = r.rect.top - origin.y - size.height - gap
                    val y = (if (above >= 0f) above else r.rect.bottom - origin.y + gap).toInt().coerceIn(0, maxY)
                    IntOffset(x, y)
                }
                .onSizeChanged { size = it },
            shape = RoundedCornerShape(14.dp),
            color = Beka.colors.panel,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shadowElevation = 8.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Row {
                items.forEach { (label, action) ->
                    Box(
                        Modifier
                            .clickable {
                                action()
                                hide()
                            }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                    ) {
                        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}
