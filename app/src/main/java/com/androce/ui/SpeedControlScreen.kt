package com.androce.ui

import androidx.compose.animation.animateColorAsState
import com.androce.ui.components.rememberAnimatedFloat
import com.androce.ui.components.rememberSystemAnimationsEnabled
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import com.androce.ui.components.SpinningLoader
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.androce.core.SpeedControl
import com.androce.core.SpeedHackState
import com.androce.ui.components.AppButton
import com.androce.ui.components.AppCard
import com.androce.ui.components.AppChip
import com.androce.ui.components.AppIconButton
import com.androce.ui.components.ButtonVariant
import com.androce.ui.components.ScreenScaffold
import com.androce.ui.components.StatusBadge
import com.androce.model.ProcessInfo
import com.androce.ui.theme.Accent
import com.androce.ui.theme.AccentGreen
import com.androce.ui.theme.Background
import com.androce.ui.theme.Error
import com.androce.ui.theme.OnBackground
import com.androce.ui.theme.OnSurface
import com.androce.ui.theme.Primary
import com.androce.ui.theme.Surface
import com.androce.ui.theme.SurfaceHigh
import com.androce.ui.theme.SurfaceVariant
import com.androce.ui.theme.Warning
import com.androce.viewmodel.ScanViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeedControlScreen(
    viewModel: ScanViewModel,
    selectedProcess: ProcessInfo?
) {
    val haptic = LocalHapticFeedback.current
    val speedState by SpeedControl.state.collectAsState()

    var sliderValue by remember { mutableFloatStateOf(speedState.speedMultiplier) }
    
    // Sync slider value when speed state changes externally (presets, reset, etc.)
    LaunchedEffect(speedState.speedMultiplier) {
        if (kotlin.math.abs(sliderValue - speedState.speedMultiplier) > 0.01f) {
            sliderValue = speedState.speedMultiplier
        }
    }

    // Detect game restart / hook loss while UI shows "active"
    LaunchedEffect(speedState.state, selectedProcess?.pid) {
        if (speedState.state != SpeedHackState.ACTIVE) return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(2000)
            viewModel.refreshSpeedHackHealth()
            if (SpeedControl.state.value.state != SpeedHackState.ACTIVE) break
        }
    }
    
    ScreenScaffold(
        title = "Speed Hack",
        selectedProcess = selectedProcess,
        showProcessContext = true,
        containerColor = Background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Status Card
            StatusCard(speedState.state, speedState.errorMessage)
            
            // Speed Display
            SpeedDisplayCard(
                speed = speedState.speedMultiplier,
                isActive = speedState.state == SpeedHackState.ACTIVE
            )
            
            // Speed Slider - updates in real-time when speed hack is active
            SpeedSliderCard(
                value = sliderValue,
                onValueChange = { 
                    sliderValue = it 
                    SpeedControl.updateSpeed(it)
                    // Apply immediately for real-time effect when active
                    if (speedState.state == SpeedHackState.ACTIVE) {
                        viewModel.updateSpeedHack(it)
                    }
                },
                onValueChangeFinished = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    viewModel.updateSpeedHack(sliderValue)
                },
                enabled = speedState.state == SpeedHackState.ACTIVE || speedState.state == SpeedHackState.IDLE
            )
            
            // Preset Buttons
            PresetButtons(
                onPresetSelected = { preset ->
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    sliderValue = preset
                    SpeedControl.updateSpeed(preset)
                    viewModel.updateSpeedHack(preset)
                },
                enabled = speedState.state == SpeedHackState.ACTIVE || speedState.state == SpeedHackState.IDLE
            )
            
            // Control Buttons
            ControlButtons(
                state = speedState.state,
                hasProcess = selectedProcess != null,
                onActivate = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    viewModel.activateSpeedHack()
                },
                onDeactivate = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    viewModel.deactivateSpeedHack()
                },
                onReset = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    sliderValue = 1.0f
                    SpeedControl.updateSpeed(1.0f)
                    viewModel.updateSpeedHack(1.0f)
                }
            )
            
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun StatusCard(state: SpeedHackState, errorMessage: String?) {
    val (icon, text, color) = when (state) {
        SpeedHackState.IDLE -> Triple(Icons.Default.Settings, "Ready to activate", OnSurface)
        SpeedHackState.INJECTING -> Triple(null, "Injecting...", Accent)
        SpeedHackState.ACTIVE -> Triple(Icons.Default.PlayArrow, "Speed hack active", AccentGreen)
        SpeedHackState.FAILED -> Triple(Icons.Default.Stop, errorMessage ?: "Failed", Error)
    }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Surface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (state == SpeedHackState.INJECTING) {
                SpinningLoader(
                    size = 24.dp,
                    color = color,
                    strokeWidth = 2.dp
                )
            } else if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(24.dp)
                )
            }
            
            Text(
                text = text,
                color = color,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun SpeedDisplayCard(speed: Float, isActive: Boolean) {
    val animatedSpeed = rememberAnimatedFloat(speed, label = "speed")
    val animationsEnabled = rememberSystemAnimationsEnabled()
    val targetBg = if (isActive && speed != 1.0f) {
        if (speed > 1.0f) AccentGreen.copy(alpha = 0.15f)
        else Warning.copy(alpha = 0.15f)
    } else {
        Surface
    }
    val bgColor by animateColorAsState(targetBg, label = "bgColor")
    val cardBg = if (animationsEnabled) bgColor else targetBg
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = cardBg),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "${String.format("%.1f", animatedSpeed)}x",
                fontSize = 48.sp,
                fontWeight = FontWeight.Bold,
                color = if (isActive && speed != 1.0f) {
                    if (speed > 1.0f) AccentGreen else Warning
                } else OnBackground
            )
            
            Text(
                text = when {
                    speed == 1.0f -> "Normal Speed"
                    speed < 1.0f -> "Slower"
                    speed > 1.0f -> "Faster"
                    else -> ""
                },
                fontSize = 14.sp,
                color = OnSurface,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun SpeedSliderCard(
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    enabled: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Surface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = "Speed Multiplier",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = OnBackground
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("0.1x", fontSize = 12.sp, color = OnSurface)
                Text("10x", fontSize = 12.sp, color = OnSurface)
            }
            
            Slider(
                value = value,
                onValueChange = onValueChange,
                onValueChangeFinished = onValueChangeFinished,
                valueRange = 0.1f..10f,
                steps = 98, // 0.1 to 10.0 in 0.1 increments
                enabled = enabled,
                colors = SliderDefaults.colors(
                    thumbColor = Primary,
                    activeTrackColor = Primary,
                    inactiveTrackColor = SurfaceVariant
                )
            )
        }
    }
}

@Composable
private fun PresetButtons(
    onPresetSelected: (Float) -> Unit,
    enabled: Boolean
) {
    val presets = listOf(
        0.25f to "0.25x",
        0.5f to "0.5x",
        1.0f to "1x",
        2.0f to "2x",
        5.0f to "5x",
        10.0f to "10x"
    )
    
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Presets",
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = OnBackground,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            presets.forEach { (speed, label) ->
                val containerColor = when {
                    speed == 1.0f -> Primary.copy(alpha = 0.2f)
                    speed < 1.0f -> Warning.copy(alpha = 0.2f)
                    else -> AccentGreen.copy(alpha = 0.2f)
                }
                
                val contentColor = when {
                    speed == 1.0f -> Primary
                    speed < 1.0f -> Warning
                    else -> AccentGreen
                }
                
                TextButton(
                    onClick = { onPresetSelected(speed) },
                    enabled = enabled,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(containerColor),
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = contentColor,
                        disabledContentColor = OnSurface.copy(alpha = 0.5f)
                    )
                ) {
                    Text(
                        label,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}


@Composable
private fun ControlButtons(
    state: SpeedHackState,
    hasProcess: Boolean,
    onActivate: () -> Unit,
    onDeactivate: () -> Unit,
    onReset: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        when (state) {
            SpeedHackState.IDLE, SpeedHackState.FAILED -> {
                AppButton(
                    label = if (hasProcess) "Activate" else "Select Process First",
                    onClick = onActivate,
                    modifier = Modifier.weight(1f),
                    enabled = hasProcess,
                    variant = ButtonVariant.Primary,
                    icon = Icons.Default.PlayArrow
                )
            }
            
            SpeedHackState.INJECTING -> {
                AppButton(
                    label = "Injecting...",
                    onClick = { },
                    modifier = Modifier.weight(1f),
                    enabled = false,
                    variant = ButtonVariant.Secondary
                )
            }
            
            SpeedHackState.ACTIVE -> {
                AppButton(
                    label = "Deactivate",
                    onClick = onDeactivate,
                    modifier = Modifier.weight(1f),
                    variant = ButtonVariant.Danger,
                    icon = Icons.Default.Stop
                )
                
                AppButton(
                    label = "Reset",
                    onClick = onReset,
                    modifier = Modifier.weight(1f),
                    variant = ButtonVariant.Secondary,
                    icon = Icons.Default.Refresh
                )
            }
        }
    }
}

