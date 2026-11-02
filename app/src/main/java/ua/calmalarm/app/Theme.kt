package ua.calmalarm.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

object Palette {
    val Green = Color(0xFF22B573)
    val Yellow = Color(0xFFF2B01E)
    val Orange = Color(0xFFF07A2A)
    val Red = Color(0xFFEF4444)
    val Night = Color(0xFF0A1330)
    val Blue = Color(0xFF3B6CF6)
    val Sky = Color(0xFF4F8CFF)
    val Violet = Color(0xFF7C5CFF)
    val Ice = Color(0xFF9DB7FF)

    /** Фірмовий градієнт кнопок і активних елементів. */
    val accentBrush: Brush get() = Brush.linearGradient(listOf(Sky, Violet))
}

fun levelColor(level: Level?): Color = if (level == Level.YELLOW) Palette.Yellow else Palette.Red

/** true - зараз темна тема (для власних кольорів поверх MaterialTheme). */
val LocalDark = staticCompositionLocalOf { false }

private val LightColors = lightColorScheme(
    primary = Color(0xFF3B6CF6),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDDE6FF),
    onPrimaryContainer = Color(0xFF0B2370),
    secondary = Color(0xFF7C5CFF),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFEDB8),
    onSecondaryContainer = Color(0xFF2B2000),
    background = Color(0xFFF2F4FA),
    onBackground = Color(0xFF111522),
    surface = Color(0xFFF2F4FA),
    onSurface = Color(0xFF111522),
    surfaceVariant = Color(0xFFE3E7F0),
    onSurfaceVariant = Color(0xFF5B6478),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFF0F2F8),
    surfaceContainerHigh = Color(0xFFE8EBF3),
    surfaceContainerHighest = Color(0xFFE0E4EE),
    outline = Color(0xFFC5CBD8),
    outlineVariant = Color(0xFFE1E5EE),
    error = Color(0xFFD32F2F),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8FB0FF),
    onPrimary = Color(0xFF071437),
    primaryContainer = Color(0xFF1E3170),
    onPrimaryContainer = Color(0xFFDDE5FF),
    secondary = Color(0xFFB4A3FF),
    onSecondary = Color(0xFF1B0E52),
    secondaryContainer = Color(0xFF4A3B00),
    onSecondaryContainer = Color(0xFFFFE9A8),
    background = Color(0xFF060A15),
    onBackground = Color(0xFFEAEEF7),
    surface = Color(0xFF060A15),
    onSurface = Color(0xFFEAEEF7),
    surfaceVariant = Color(0xFF222C3F),
    onSurfaceVariant = Color(0xFFA3ADC2),
    surfaceContainerLowest = Color(0xFF05080F),
    surfaceContainerLow = Color(0xFF111A2C),
    surfaceContainer = Color(0xFF151F33),
    surfaceContainerHigh = Color(0xFF1C273D),
    surfaceContainerHighest = Color(0xFF253148),
    outline = Color(0xFF39445A),
    outlineVariant = Color(0xFF232D41),
    error = Color(0xFFFF8A80),
)

@Composable
fun CalmAlarmTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalDark provides darkTheme) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            content = content,
        )
    }
}

/** Фон екранів: глибокий градієнт з м'яким «сяйвом» угорі. */
@Composable
fun AppBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val dark = LocalDark.current
    val base = MaterialTheme.colorScheme.background
    Box(
        modifier
            .fillMaxSize()
            .background(base)
            .drawBehind {
                val w = size.width
                val h = size.height
                drawRect(
                    Brush.radialGradient(
                        listOf(Palette.Sky.copy(alpha = if (dark) 0.28f else 0.18f), Color.Transparent),
                        center = Offset(w * 0.1f, -h * 0.02f),
                        radius = w * 0.95f,
                    ),
                )
                drawRect(
                    Brush.radialGradient(
                        listOf(Palette.Violet.copy(alpha = if (dark) 0.22f else 0.12f), Color.Transparent),
                        center = Offset(w * 1.0f, h * 0.18f),
                        radius = w * 0.8f,
                    ),
                )
                if (dark) {
                    drawRect(
                        Brush.radialGradient(
                            listOf(Color(0xFF0E7C86).copy(alpha = 0.12f), Color.Transparent),
                            center = Offset(w * 0.2f, h * 1.0f),
                            radius = w * 0.9f,
                        ),
                    )
                }
            },
        content = content,
    )
}

/** Напівпрозора картка з тонкою світлою рамкою. [tint] - колір підсвітки (стан тривоги тощо). */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    tint: Color? = null,
    shape: Shape = RoundedCornerShape(26.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val dark = LocalDark.current
    val base = if (dark) Color(0xFF121B2E).copy(alpha = 0.78f) else Color.White.copy(alpha = 0.86f)
    val border = BorderStroke(
        1.dp,
        Brush.verticalGradient(
            if (dark) listOf(Color.White.copy(alpha = 0.12f), Color.White.copy(alpha = 0.03f))
            else listOf(Color.White, Color(0xFFDDE2EC)),
        ),
    )
    val inner: @Composable () -> Unit = {
        Column(
            Modifier.background(
                if (tint != null) {
                    Brush.linearGradient(listOf(tint.copy(alpha = if (dark) 0.22f else 0.16f), tint.copy(alpha = 0.04f)))
                } else {
                    Brush.linearGradient(listOf(Color.Transparent, Color.Transparent))
                },
            ),
            content = content,
        )
    }
    if (onClick != null) {
        Surface(onClick = onClick, modifier = modifier, shape = shape, color = base, border = border, content = inner)
    } else {
        Surface(modifier = modifier, shape = shape, color = base, border = border, content = inner)
    }
}

/** Кругла кнопка з фірмовим градієнтом. */
@Composable
fun GradientCircle(size: Dp, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier
            .background(Palette.accentBrush, RoundedCornerShape(size / 2)),
        contentAlignment = androidx.compose.ui.Alignment.Center,
        content = content,
    )
}
