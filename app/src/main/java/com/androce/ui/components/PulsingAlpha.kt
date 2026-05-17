package com.androce.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Alpha pulse driven by coroutine delay (works when system animations are disabled). */
@Composable
fun rememberPulsingAlpha(
    min: Float = 0.35f,
    max: Float = 1f,
    stepMs: Long = 40L,
): Float {
    var alpha by remember { mutableFloatStateOf(max) }
    var increasing by remember { mutableStateOf(false) }

    LaunchedEffect(min, max, stepMs) {
        while (isActive) {
            val delta = (max - min) * 0.06f
            if (increasing) {
                alpha = (alpha + delta).coerceAtMost(max)
                if (alpha >= max) increasing = false
            } else {
                alpha = (alpha - delta).coerceAtLeast(min)
                if (alpha <= min) increasing = true
            }
            delay(stepMs)
        }
    }
    return alpha
}
