package com.androce.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.androce.core.ScanProgress
import com.androce.model.ValueType
import com.androce.ui.theme.Accent
import com.androce.ui.theme.AccentGreen
import com.androce.ui.theme.Primary
import com.androce.ui.theme.SurfaceHigh
import com.androce.ui.theme.SurfaceVariant
import com.androce.viewmodel.ScanState
import com.androce.viewmodel.ScanViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    viewModel: ScanViewModel,
    onBack: () -> Unit,
    onViewResults: () -> Unit
) {
    val scanState by viewModel.scanState.collectAsState()
    val results by viewModel.results.collectAsState()
    val regions by viewModel.regions.collectAsState()
    val haptic = LocalHapticFeedback.current

    var searchInput by remember { mutableStateOf(viewModel.searchInput) }
    var selectedType by remember { mutableStateOf(viewModel.selectedValueType) }
    var xorKey by remember { mutableStateOf(viewModel.xorKey.toString()) }

    val isScanning = scanState is ScanState.Scanning

    fun triggerScan() {
        if (searchInput.isBlank()) return
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        viewModel.searchInput = searchInput
        viewModel.selectedValueType = selectedType
        viewModel.firstScan()
    }

    LaunchedEffect(Unit) { viewModel.loadRegions() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Memory Search", color = Primary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text(
                            viewModel.selectedProcess?.let { "${it.name}  [PID ${it.pid}]" } ?: "",
                            color = Accent, fontSize = 12.sp, fontFamily = FontFamily.Monospace
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Primary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SectionLabel("Value Type")
            ValueTypePicker(selected = selectedType) { t ->
                selectedType = t
                viewModel.selectedValueType = t
                searchInput = ""
                viewModel.searchInput = ""
            }

            AnimatedVisibility(selectedType == ValueType.XOR4 || selectedType == ValueType.XOR8) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    SectionLabel("XOR Key")
                    OutlinedTextField(
                        value = xorKey,
                        onValueChange = { xorKey = it; viewModel.xorKey = it.toLongOrNull() ?: 0L },
                        label = { Text("XOR Key (decimal)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        trailingIcon = {
                            if (xorKey.isNotEmpty()) {
                                IconButton(onClick = { xorKey = ""; viewModel.xorKey = 0L }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Clear", tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), modifier = Modifier.size(18.dp))
                                }
                            }
                        },
                        colors = inputColors()
                    )
                }
            }

            SectionLabel("Search Value")
            OutlinedTextField(
                value = searchInput,
                onValueChange = { searchInput = it; viewModel.searchInput = it },
                label = { Text(searchHint(selectedType)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = selectedType != ValueType.STRING_UTF8 && selectedType != ValueType.STRING_UTF16,
                keyboardOptions = KeyboardOptions(
                    keyboardType = keyboardTypeFor(selectedType),
                    imeAction = ImeAction.Search
                ),
                keyboardActions = KeyboardActions(onSearch = { triggerScan() }),
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Primary) },
                trailingIcon = {
                    if (searchInput.isNotEmpty()) {
                        IconButton(onClick = { searchInput = ""; viewModel.searchInput = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear input", tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), modifier = Modifier.size(18.dp))
                        }
                    }
                },
                colors = inputColors()
            )

            SectionLabel("Memory Regions")
            AnimatedContent(
                targetState = regions.size,
                transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) },
                label = "regions"
            ) { count ->
                Text(
                    text = if (count == 0) "Loading regions…" else "$count readable regions",
                    color = Accent, fontSize = 13.sp, fontFamily = FontFamily.Monospace
                )
            }

            AnimatedContent(
                targetState = isScanning,
                transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(150)) },
                label = "scan_buttons"
            ) { scanning ->
                if (scanning) {
                    Button(
                        onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); viewModel.cancelScan() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Stop Scanning")
                    }
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = { triggerScan() },
                            enabled = searchInput.isNotBlank(),
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Primary)
                        ) {
                            Text("First Scan")
                        }
                        Button(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                viewModel.searchInput = searchInput
                                viewModel.selectedValueType = selectedType
                                viewModel.refinedScan()
                            },
                            enabled = results.isNotEmpty() && searchInput.isNotBlank(),
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = SurfaceVariant)
                        ) {
                            Text("Refined Scan")
                        }
                    }
                }
            }

            Button(
                onClick = { viewModel.resetScan() },
                enabled = results.isNotEmpty() || scanState is ScanState.Done,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text("Reset / New Scan")
            }

            when (val s = scanState) {
                is ScanState.Scanning -> ScanProgressCard(s.progress)
                is ScanState.Error -> Text("Error: ${s.message}", color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                else -> {}
            }

            if (results.isNotEmpty() && !isScanning) {
                ResultsBadge(count = results.size, onClick = onViewResults)
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text = text.uppercase(), color = Primary, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
}

@Composable
private fun ValueTypePicker(selected: ValueType, onSelect: (ValueType) -> Unit) {
    val haptic = LocalHapticFeedback.current
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ValueType.entries.forEach { type ->
            val isSelected = type == selected
            val bgColor by animateColorAsState(
                if (isSelected) Primary else SurfaceVariant, tween(150), label = "chip_bg"
            )
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(bgColor)
                    .border(1.dp, if (isSelected) Primary else Color.Transparent, RoundedCornerShape(8.dp))
                    .clickable { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onSelect(type) }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (isSelected) {
                    Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
                }
                Text(
                    text = type.label,
                    color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface,
                    fontSize = 12.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}

@Composable
private fun ScanProgressCard(progress: ScanProgress) {
    val fraction = if (progress.totalRegions > 0) progress.scannedRegions.toFloat() / progress.totalRegions else 0f
    val animFraction by animateFloatAsState(fraction, tween(300), label = "progress")
    val percent = (animFraction * 100).toInt()

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(SurfaceVariant).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Primary, strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("${progress.scannedRegions}/${progress.totalRegions} regions", color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
            }
            Text("$percent%", color = Primary, fontSize = 13.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        }
        LinearProgressIndicator(progress = { animFraction }, modifier = Modifier.fillMaxWidth(), color = Primary, trackColor = SurfaceHigh)
        Text("Found so far: ${progress.foundCount}", color = AccentGreen, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun ResultsBadge(count: Int, onClick: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(AccentGreen.copy(alpha = 0.12f))
            .border(1.dp, AccentGreen.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick).padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Check, contentDescription = null, tint = AccentGreen, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Column {
                Text("$count result${if (count != 1) "s" else ""} found", color = AccentGreen, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text("Tap to view addresses", color = AccentGreen.copy(alpha = 0.7f), fontSize = 11.sp)
            }
        }
    }
}

private fun searchHint(type: ValueType): String = when (type) {
    ValueType.BYTE1 -> "Byte (-128 to 127)"
    ValueType.BYTE2 -> "Short value"
    ValueType.BYTE4 -> "Int value"
    ValueType.BYTE8 -> "Long value"
    ValueType.FLOAT -> "Float (e.g. 3.14)"
    ValueType.DOUBLE -> "Double"
    ValueType.STRING_UTF8 -> "UTF-8 string"
    ValueType.STRING_UTF16 -> "UTF-16 string"
    ValueType.BYTE_ARRAY -> "Hex bytes e.g. FF 4A ?? 00"
    ValueType.XOR4 -> "Int (XOR'd with key)"
    ValueType.XOR8 -> "Long (XOR'd with key)"
}

private fun keyboardTypeFor(type: ValueType): KeyboardType = when (type) {
    ValueType.STRING_UTF8, ValueType.STRING_UTF16, ValueType.BYTE_ARRAY -> KeyboardType.Text
    ValueType.FLOAT, ValueType.DOUBLE -> KeyboardType.Decimal
    else -> KeyboardType.Number
}

@Composable
fun inputColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Primary,
    unfocusedBorderColor = SurfaceHigh,
    focusedContainerColor = SurfaceVariant,
    unfocusedContainerColor = SurfaceVariant,
    focusedTextColor = MaterialTheme.colorScheme.onBackground,
    unfocusedTextColor = MaterialTheme.colorScheme.onBackground,
    cursorColor = Primary,
    focusedLabelColor = Primary
)
