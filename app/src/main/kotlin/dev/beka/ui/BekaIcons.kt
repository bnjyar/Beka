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
    val Close = icon("close", "M6 6l12 12M18 6 6 18")
    val Camera = icon("camera", "M4 8h3l2-3h6l2 3h3v11H4z", "M15.5 13a3.5 3.5 0 1 1-7 0a3.5 3.5 0 1 1 7 0z")
    val Bolt = icon("bolt", "M13 2 4 14h7l-1 8 9-12h-7z")
    val ArrowUp = icon("arrow_up", "M12 19V5M5 12l7-7 7 7")
    val ChevronRight = icon("chevron_right", "m9 6 6 6-6 6")
    val ChevronDown = icon("chevron_down", "m6 9 6 6 6-6")
    val Stop = icon("stop", filled = listOf("M7 7h10v10H7z"))

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
