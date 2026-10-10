package dev.beka.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * Line icons drawn on a 24-unit grid, so the app needs no icon library. Paths are stroked unless
 * listed in `filled`; tint them with Icon's `tint` like any other icon.
 */
object BekaIcons {
    val Refresh = icon("refresh", "M21 12a9 9 0 1 1-3-6.7L21 8", "M21 3v5h-5")
    val More = icon("more", filled = listOf(dot(12f, 5f), dot(12f, 12f), dot(12f, 19f)))
    val NewChat = icon("new_chat", "M12 3H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7", "M18.4 2.6a2.1 2.1 0 0 1 3 3L12 15l-4 1 1-4z")
    val Warning = icon("warning", "M12 3 2 20h20z", "M12 10v4M12 17h.01")
    val Home = icon("home", "M3 11 12 4l9 7v9h-6v-6H9v6H3z")
    val Memory = icon("memory", "M12 3a6 6 0 0 0-4 10.5V17h8v-3.5A6 6 0 0 0 12 3z", "M9 21h6")
    val Settings = icon(
        "settings",
        "M15 12a3 3 0 1 1-6 0a3 3 0 1 1 6 0z",
        "M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z",
    )
    val Copy = icon("copy", "M9 9h11v11H9z", "M5 15H4V4h11v1")
    val Bell = icon("bell", "M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9", "M10.3 21a1.9 1.9 0 0 0 3.4 0")
    val Eye = icon("eye", "M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12z", "M15 12a3 3 0 1 1-6 0a3 3 0 1 1 6 0z")
    val Layers = icon("layers", "M12 3 2 8l10 5 10-5z", "M2 16l10 5 10-5")
    val Check = icon("check", "m5 12 5 5 9-10")
    val Sparkle = icon("sparkle", "M12 3v4M12 17v4M3 12h4M17 12h4M6 6l2.5 2.5M15.5 15.5 18 18M6 18l2.5-2.5M15.5 8.5 18 6")
    val Close = icon("close", "M6 6l12 12M18 6 6 18")
    val Camera = icon("camera", "M4 8h3l2-3h6l2 3h3v11H4z", "M15.5 13a3.5 3.5 0 1 1-7 0a3.5 3.5 0 1 1 7 0z")
    val Bolt = icon("bolt", "M13 2 4 14h7l-1 8 9-12h-7z")
    val ArrowUp = icon("arrow_up", "M12 19V5M5 12l7-7 7 7")
    val ChevronRight = icon("chevron_right", "m9 6 6 6-6 6")
    val ChevronDown = icon("chevron_down", "m6 9 6 6 6-6")
    val Stop = icon("stop", filled = listOf("M7 7h10v10H7z"))
    val Mic = icon("mic", "M15 12V6a3 3 0 0 0-6 0v6a3 3 0 0 0 6 0z", "M19 11a7 7 0 0 1-14 0M12 18v3")

    /** A filled circle of radius 1.8 as path data. */
    private fun dot(cx: Float, cy: Float) =
        "M${cx - 1.8f} ${cy}a1.8 1.8 0 1 0 3.6 0a1.8 1.8 0 1 0 -3.6 0z"

    private fun icon(name: String, vararg stroked: String, filled: List<String> = emptyList()): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            stroked.forEach {
                addPath(
                    pathData = PathParser().parsePathString(it).toNodes(),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
            filled.forEach {
                addPath(pathData = PathParser().parsePathString(it).toNodes(), fill = SolidColor(Color.Black))
            }
        }.build()
}
