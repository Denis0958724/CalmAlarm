package ua.vidbij.alarm

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object Palette {
    val Green = Color(0xFF2E9E5B)
    val Yellow = Color(0xFFE3A008)
    val Orange = Color(0xFFE8711C)
    val Red = Color(0xFFD93A3A)
    val Night = Color(0xFF14264F)
    val Blue = Color(0xFF2F5BD3)
}

fun levelColor(level: Level?): Color = if (level == Level.YELLOW) Palette.Yellow else Palette.Red

private val LightColors = lightColorScheme(
    primary = Color(0xFF2F5BD3),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCE4FF),
    onPrimaryContainer = Color(0xFF0B2370),
    secondary = Color(0xFF8A6A00),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFE9A8),
    onSecondaryContainer = Color(0xFF2B2000),
    background = Color(0xFFF5F6FA),
    onBackground = Color(0xFF181C24),
    surface = Color(0xFFF5F6FA),
    onSurface = Color(0xFF181C24),
    surfaceVariant = Color(0xFFE3E7F0),
    onSurfaceVariant = Color(0xFF5A6273),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFEEF1F7),
    surfaceContainerHigh = Color(0xFFE7EAF2),
    surfaceContainerHighest = Color(0xFFE0E4EE),
    outline = Color(0xFFC5CBD8),
    outlineVariant = Color(0xFFDDE1EA),
    error = Color(0xFFC62828),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9DB7FF),
    onPrimary = Color(0xFF0B1A44),
    primaryContainer = Color(0xFF233A7A),
    onPrimaryContainer = Color(0xFFDDE5FF),
    secondary = Color(0xFFFFCF4D),
    onSecondary = Color(0xFF2B2000),
    secondaryContainer = Color(0xFF4A3B00),
    onSecondaryContainer = Color(0xFFFFE9A8),
    background = Color(0xFF0E131D),
    onBackground = Color(0xFFE6E9F0),
    surface = Color(0xFF0E131D),
    onSurface = Color(0xFFE6E9F0),
    surfaceVariant = Color(0xFF253041),
    onSurfaceVariant = Color(0xFFA9B2C3),
    surfaceContainerLowest = Color(0xFF0A0E16),
    surfaceContainerLow = Color(0xFF161D2A),
    surfaceContainer = Color(0xFF18202D),
    surfaceContainerHigh = Color(0xFF1F2836),
    surfaceContainerHighest = Color(0xFF283243),
    outline = Color(0xFF3A4558),
    outlineVariant = Color(0xFF2A3344),
    error = Color(0xFFFF8A80),
)

@Composable
fun VidbijTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
