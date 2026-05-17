package com.androce.ui.components

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

fun Context.areSystemAnimationsEnabled(): Boolean =
    try {
        Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    } catch (_: Exception) {
        true
    }

@Composable
fun rememberSystemAnimationsEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) { context.areSystemAnimationsEnabled() }
}

@Composable
fun rememberAnimatedFloat(
    target: Float,
    animationSpec: AnimationSpec<Float>? = null,
    label: String = "animatedFloat",
): Float {
    val enabled = rememberSystemAnimationsEnabled()
    val animated by if (animationSpec != null) {
        animateFloatAsState(target, animationSpec, label = label)
    } else {
        animateFloatAsState(target, label = label)
    }
    return if (enabled) animated else target
}
