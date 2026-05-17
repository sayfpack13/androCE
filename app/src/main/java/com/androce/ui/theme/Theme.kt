package com.androce.ui.theme

import android.app.Activity
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

// Core palette — tuned for OLED / XOS: stronger text contrast, clearer surfaces
val Primary     = Color(0xFFA67FFF)
val PrimaryDim  = Color(0xFF7C4DFF)
val Secondary   = Color(0xFF26E8FF)
val Background  = Color(0xFF0A0A12)
val Surface     = Color(0xFF151522)
val SurfaceVariant = Color(0xFF1F1F34)
val SurfaceHigh = Color(0xFF2C2C48)
val OnBackground = Color(0xFFF4F2FF)
val OnSurface   = Color(0xFFC8C0E0)
val Error       = Color(0xFFFF5C75)
val Accent      = Color(0xFF26E8FF)
val AccentGreen = Color(0xFF72FF94)
val Warning     = Color(0xFFFFE066)

private val DarkColors = darkColorScheme(
    primary          = Primary,
    onPrimary        = Color(0xFF1A0050),
    primaryContainer = PrimaryDim,
    onPrimaryContainer = OnBackground,
    secondary        = Secondary,
    onSecondary      = Color(0xFF003333),
    background       = Background,
    onBackground     = OnBackground,
    surface          = Surface,
    onSurface        = OnSurface,
    surfaceVariant   = SurfaceVariant,
    onSurfaceVariant = OnSurface,
    surfaceContainer = SurfaceVariant,
    surfaceContainerHigh = SurfaceHigh,
    error            = Error,
    onError          = Color(0xFF1A0010),
    tertiary         = Accent,
    outline          = SurfaceHigh,
    outlineVariant   = SurfaceHigh.copy(alpha = 0.55f)
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
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = Background.toArgb()
            window.navigationBarColor = Background.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }

    MaterialTheme(
        colorScheme = DarkColors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content
    )
}
