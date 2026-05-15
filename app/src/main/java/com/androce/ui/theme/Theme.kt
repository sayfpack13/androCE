package com.androce.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape

// Core palette
val Primary     = Color(0xFF9D6FFF)
val PrimaryDim  = Color(0xFF7C4DFF)
val Secondary   = Color(0xFF00E5FF)
val Background  = Color(0xFF09090F)
val Surface     = Color(0xFF13131F)
val SurfaceVariant = Color(0xFF1E1E30)
val SurfaceHigh = Color(0xFF272740)
val OnBackground = Color(0xFFF0EEFF)
val OnSurface   = Color(0xFFB0AACC)
val Error       = Color(0xFFFF5370)
val Accent      = Color(0xFF00E5FF)
val AccentGreen = Color(0xFF69FF8A)
val Warning     = Color(0xFFFFD740)

private val DarkColors = darkColorScheme(
    primary          = Primary,
    onPrimary        = Color(0xFF1A0050),
    primaryContainer = PrimaryDim,
    secondary        = Secondary,
    onSecondary      = Color(0xFF003333),
    background       = Background,
    onBackground     = OnBackground,
    surface          = Surface,
    onSurface        = OnSurface,
    surfaceVariant   = SurfaceVariant,
    onSurfaceVariant = OnSurface,
    error            = Error,
    onError          = Color(0xFF1A0010),
    tertiary         = Accent,
    outline          = SurfaceHigh
)

val AppTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        letterSpacing = (-0.5).sp
    ),
    headlineMedium = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        letterSpacing = (-0.3).sp
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        letterSpacing = 0.1.sp
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp,
        letterSpacing = 0.2.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        letterSpacing = 1.sp
    )
)

val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small      = RoundedCornerShape(10.dp),
    medium     = RoundedCornerShape(14.dp),
    large      = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

@Composable
fun AndroCETheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        typography  = AppTypography,
        shapes      = AppShapes,
        content     = content
    )
}
