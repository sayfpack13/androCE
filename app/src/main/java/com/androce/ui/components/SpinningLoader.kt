package com.androce.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.androce.ui.theme.Primary
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Indeterminate loader that spins via coroutine ticks, independent of the system
 * animator-duration scale (often 0 on OEM skins such as Infinix XOS).
 */
@Composable
fun SpinningLoader(
    modifier: Modifier = Modifier,
    color: Color = Primary,
    trackColor: Color = color.copy(alpha = 0.18f),
    strokeWidth: Dp = 4.dp,
    arcSweep: Float = 270f,
) {
    var rotation by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        while (isActive) {
            rotation = (rotation + 10f) % 360f
            delay(16L)
        }
    }

    Canvas(modifier = modifier) {
        val stroke = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round)
        val diameter = size.minDimension
        val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
        val arcSize = Size(diameter, diameter)
        drawArc(
            color = trackColor,
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = stroke
        )
        drawArc(
            color = color,
            startAngle = rotation,
            sweepAngle = arcSweep,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = stroke
        )
    }
}

@Composable
fun SpinningLoader(
    size: Dp,
    modifier: Modifier = Modifier,
    color: Color = Primary,
    trackColor: Color = color.copy(alpha = 0.18f),
    strokeWidth: Dp = 4.dp,
) {
    SpinningLoader(
        modifier = modifier.then(Modifier.size(size)),
        color = color,
        trackColor = trackColor,
        strokeWidth = strokeWidth
    )
}
