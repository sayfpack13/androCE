package com.androce.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Primary = Color(0xFF7C4DFF)
val PrimaryVariant = Color(0xFF6200EE)
val Secondary = Color(0xFF03DAC6)
val Background = Color(0xFF0D0D1A)
val Surface = Color(0xFF1A1A2E)
val SurfaceVariant = Color(0xFF252545)
val OnBackground = Color(0xFFE0E0FF)
val OnSurface = Color(0xFFCCCCFF)
val Error = Color(0xFFCF6679)
val Accent = Color(0xFF00E5FF)

private val DarkColors = darkColorScheme(
    primary = Primary,
    secondary = Secondary,
    background = Background,
    surface = Surface,
    onBackground = OnBackground,
    onSurface = OnSurface,
    error = Error,
    tertiary = Accent
)

@Composable
fun AndroCETheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        content = content
    )
}
