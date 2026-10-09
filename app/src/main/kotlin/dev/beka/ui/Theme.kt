package dev.beka.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Beka's palette: neutral greys and one indigo accent, used only where the eye should go (the bubble,
 * the main button, the step number). Light or dark follows the system; the wallpaper does not.
 */
private val Light = lightColorScheme(
    primary = Color(0xFF3949AB),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEDEDF0),
    onPrimaryContainer = Color(0xFF18181B),
    inversePrimary = Color(0xFFA8B4FF),
    secondary = Color(0xFF5C5C66),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEDEDF0),
    onSecondaryContainer = Color(0xFF18181B),
    tertiary = Color(0xFF5C5C66),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFEDEDF0),
    onTertiaryContainer = Color(0xFF18181B),
    background = Color(0xFFF7F7F8),
    onBackground = Color(0xFF18181B),
    surface = Color(0xFFF7F7F8),
    onSurface = Color(0xFF18181B),
    surfaceVariant = Color(0xFFEDEDF0),
    onSurfaceVariant = Color(0xFF5C5C66),
    surfaceTint = Color(0xFFF7F7F8),
    inverseSurface = Color(0xFF18181B),
    inverseOnSurface = Color.White,
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    outline = Color(0xFF8E8E96),
    outlineVariant = Color(0xFFE2E2E6),
    surfaceBright = Color.White,
    surfaceDim = Color(0xFFE2E2E6),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color(0xFFF1F1F3),
    surfaceContainerHigh = Color(0xFFEDEDF0),
    surfaceContainerHighest = Color(0xFFE6E6EA),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFA8B4FF),
    onPrimary = Color(0xFF0E1A5C),
    primaryContainer = Color(0xFF2A2A2F),
    onPrimaryContainer = Color(0xFFEDEDEF),
    inversePrimary = Color(0xFF3949AB),
    secondary = Color(0xFFA1A1AA),
    onSecondary = Color(0xFF18181B),
    secondaryContainer = Color(0xFF2A2A2F),
    onSecondaryContainer = Color(0xFFEDEDEF),
    tertiary = Color(0xFFA1A1AA),
    onTertiary = Color(0xFF18181B),
    tertiaryContainer = Color(0xFF2A2A2F),
    onTertiaryContainer = Color(0xFFEDEDEF),
    background = Color(0xFF0E0E10),
    onBackground = Color(0xFFEDEDEF),
    surface = Color(0xFF0E0E10),
    onSurface = Color(0xFFEDEDEF),
    surfaceVariant = Color(0xFF2A2A2F),
    onSurfaceVariant = Color(0xFFA1A1AA),
    surfaceTint = Color(0xFF0E0E10),
    inverseSurface = Color(0xFFEDEDEF),
    inverseOnSurface = Color(0xFF18181B),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
    outline = Color(0xFF6B6B73),
    outlineVariant = Color(0xFF2A2A2F),
    surfaceBright = Color(0xFF2A2A2F),
    surfaceDim = Color(0xFF0E0E10),
    surfaceContainerLowest = Color(0xFF0B0B0D),
    surfaceContainerLow = Color(0xFF141416),
    surfaceContainer = Color(0xFF18181B),
    surfaceContainerHigh = Color(0xFF232327),
    surfaceContainerHighest = Color(0xFF2A2A2F),
)

/** Roles the Material scheme has no name for. */
@Immutable
data class BekaColors(
    /** Floating cards over other apps: the chat panel, rule notes. */
    val panel: Color,
    /** The message field and other inset areas inside a panel. */
    val field: Color,
    /** The user's own chat bubble, switched-on toggles, step chips. */
    val chip: Color,
    val shadow: Color,
)

private val LightExtra = BekaColors(
    panel = Color.White,
    field = Color(0xFFF1F1F3),
    chip = Color(0xFFEDEDF0),
    shadow = Color(0x38141420),
)

private val DarkExtra = BekaColors(
    panel = Color(0xFF18181B),
    field = Color(0xFF232327),
    chip = Color(0xFF2A2A2F),
    shadow = Color(0x8C000000),
)

val LocalBekaColors = staticCompositionLocalOf { LightExtra }

object Beka {
    val colors: BekaColors
        @Composable get() = LocalBekaColors.current
}

@Composable
fun BekaTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    CompositionLocalProvider(LocalBekaColors provides if (dark) DarkExtra else LightExtra) {
        MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
    }
}
