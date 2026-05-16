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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.QuestionMark
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import com.androce.core.MemoryReader
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.androce.core.ScanProgress
import com.androce.model.ScanComparison
import com.androce.model.ValueType
import com.androce.model.ValueTypeCategory
import com.androce.ui.theme.Accent
import com.androce.ui.theme.AccentGreen
import com.androce.ui.theme.OnBackground
import com.androce.ui.theme.OnSurface
import com.androce.ui.theme.Primary
import com.androce.ui.theme.PrimaryDim
import com.androce.ui.theme.SurfaceHigh
import com.androce.ui.theme.SurfaceVariant
import com.androce.ui.theme.Warning
import com.androce.viewmodel.ScanState
import com.androce.viewmodel.ScanViewModel
import kotlinx.coroutines.launch

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
    val isPaused by viewModel.isPaused.collectAsState()
    val haptic = LocalHapticFeedback.current

    var searchInput by remember { mutableStateOf(viewModel.searchInput) }
    var selectedType by remember { mutableStateOf(viewModel.selectedValueType) }
    var xorKey by remember { mutableStateOf(viewModel.xorKey.toString()) }
    var rangeMin by remember { mutableStateOf(viewModel.rangeMin) }
    var rangeMax by remember { mutableStateOf(viewModel.rangeMax) }
    fun triggerScan() {
        if (searchInput.isBlank()) return
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        viewModel.searchInput = searchInput
        viewModel.selectedValueType = selectedType
        if (viewModel.regions.value.isEmpty()) {
            viewModel.loadRegions()
        }
        viewModel.firstScan()
    }

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
                actions = {
                    if (results.isNotEmpty()) {
                        Box(
                            Modifier.clip(RoundedCornerShape(8.dp))
                                .background(AccentGreen.copy(alpha = 0.2f))
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text("${results.size} found", color = AccentGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            ScanTab(
                scanState = scanState,
                results = results,
                regions = regions,
                selectedType = selectedType,
                searchInput = searchInput,
                rangeMin = rangeMin,
                rangeMax = rangeMax,
                xorKey = xorKey,
                isScanning = scanState is ScanState.Scanning,
                isPaused = isPaused,
                onSearchChange = { searchInput = it; viewModel.searchInput = it },
                onRangeMinChange = { rangeMin = it; viewModel.rangeMin = it },
                onRangeMaxChange = { rangeMax = it; viewModel.rangeMax = it },
                onTypeChange = { t ->
                    selectedType = t
                    viewModel.selectedValueType = t
                },
                onXorChange = { xorKey = it; viewModel.xorKey = it.toLongOrNull() ?: 0L },
                onFirstScan = { triggerScan() },
                onRefinedScan = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    viewModel.searchInput = searchInput
                    viewModel.selectedValueType = selectedType
                    viewModel.refinedScan()
                },
                onComparison = { op ->
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    viewModel.searchInput = searchInput
                    viewModel.rangeMin = rangeMin
                    viewModel.rangeMax = rangeMax
                    viewModel.selectedValueType = selectedType
                    viewModel.comparisonScan(op)
                },
                onUnknownInitial = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    viewModel.selectedValueType = selectedType
                    viewModel.unknownInitialScan()
                },
                onStop = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); viewModel.cancelScan() },
                onPauseResume = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); viewModel.togglePause() },
                onReset = { viewModel.resetScan() },
                onViewResults = onViewResults
            )
        }
    }
}

@Composable
private fun ScanTab(
    scanState: ScanState,
    results: List<com.androce.model.ScanResult>,
    regions: List<com.androce.model.MemoryRegion>,
    selectedType: ValueType,
    searchInput: String,
    rangeMin: String,
    rangeMax: String,
    xorKey: String,
    isScanning: Boolean,
    isPaused: Boolean,
    onSearchChange: (String) -> Unit,
    onRangeMinChange: (String) -> Unit,
    onRangeMaxChange: (String) -> Unit,
    onTypeChange: (ValueType) -> Unit,
    onXorChange: (String) -> Unit,
    onFirstScan: () -> Unit,
    onRefinedScan: () -> Unit,
    onComparison: (ScanComparison) -> Unit,
    onUnknownInitial: () -> Unit,
    onStop: () -> Unit,
    onPauseResume: () -> Unit,
    onReset: () -> Unit,
    onViewResults: () -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Type selection + summary
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .background(SurfaceVariant).padding(14.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Value Type", color = OnSurface.copy(alpha = 0.6f), fontSize = 10.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold)
                    AnimatedContent(targetState = regions.size, transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) }, label = "region_count") { count ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(if (count == 0) "…" else "$count", color = Accent, fontSize = 14.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                            Text("regions", color = OnSurface.copy(alpha = 0.5f), fontSize = 10.sp)
                        }
                    }
                }
                ValueTypeDropdown(selected = selectedType, onSelect = onTypeChange)
            }
        }

        AnimatedVisibility(selectedType == ValueType.XOR4 || selectedType == ValueType.XOR8) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel("XOR Key")
                OutlinedTextField(
                    value = xorKey,
                    onValueChange = onXorChange,
                    label = { Text("XOR Key (decimal)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    trailingIcon = {
                        if (xorKey.isNotEmpty()) {
                            IconButton(onClick = { onXorChange("") }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear", tint = OnSurface.copy(alpha = 0.5f), modifier = Modifier.size(18.dp))
                            }
                        }
                    },
                    colors = inputColors()
                )
            }
        }

        // Unknown initial value — only meaningful when starting fresh
        if (results.isEmpty() && !selectedType.isVariableLength && !isScanning) {
            Button(
                onClick = onUnknownInitial,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = SurfaceVariant)
            ) {
                Icon(Icons.Default.QuestionMark, contentDescription = null, modifier = Modifier.size(16.dp), tint = Accent)
                Spacer(Modifier.width(8.dp))
                Text("Unknown Initial Value", color = Accent)
            }
            Text(
                "Snapshots all aligned slots — use comparison ops afterwards.",
                color = OnSurface.copy(alpha = 0.55f), fontSize = 11.sp
            )
        }

        // Comparison control row (only when we have previous results)
        if (results.isNotEmpty() && !isScanning) {
            ComparisonControlRow(
                selectedType = selectedType,
                rangeMin = rangeMin,
                rangeMax = rangeMax,
                onRangeMinChange = onRangeMinChange,
                onRangeMaxChange = onRangeMaxChange,
                onComparison = onComparison
            )
        }

        // Scan action buttons
        AnimatedContent(
            targetState = isScanning,
            transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(150)) },
            label = "scan_buttons"
        ) { scanning ->
            if (scanning) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = onPauseResume,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = if (isPaused) Accent else Primary)
                    ) {
                        Icon(if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (isPaused) "Resume" else "Pause")
                    }
                    Button(
                        onClick = onStop,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Stop")
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Search input always visible in Scan tab for easy value changes
                    val inputValid = searchInput.isBlank() || isInputValid(searchInput, selectedType)
                    OutlinedTextField(
                        value = searchInput,
                        onValueChange = onSearchChange,
                        label = { Text(if (results.isEmpty()) "Search value" else "New value to refine") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = selectedType != ValueType.STRING,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = keyboardTypeFor(selectedType),
                            imeAction = ImeAction.Search
                        ),
                        keyboardActions = KeyboardActions(onSearch = {
                            if (results.isEmpty()) onFirstScan() else onRefinedScan()
                        }),
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Primary) },
                        trailingIcon = {
                            if (searchInput.isNotEmpty()) {
                                IconButton(onClick = { onSearchChange("") }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Clear", tint = OnSurface.copy(alpha = 0.5f), modifier = Modifier.size(18.dp))
                                }
                            }
                        },
                        supportingText = {
                            if (searchInput.isNotBlank() && !inputValid) {
                                Text(inputHelperText(selectedType), color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
                            }
                        },
                        isError = searchInput.isNotBlank() && !inputValid,
                        colors = inputColors()
                    )

                    if (results.isEmpty()) {
                        // No results yet: only First Scan
                        Button(
                            onClick = onFirstScan,
                            enabled = searchInput.isNotBlank() && inputValid,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Primary)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("First Scan", fontWeight = FontWeight.Bold)
                        }
                    } else {
                        // Results exist: show refined scan + comparison + reset
                        Button(
                            onClick = onRefinedScan,
                            enabled = searchInput.isNotBlank() && inputValid,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Accent)
                        ) {
                            Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Refined Scan", fontWeight = FontWeight.Bold)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = onFirstScan,
                                enabled = searchInput.isNotBlank() && inputValid,
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = Primary)
                            ) {
                                Text("New First Scan", fontSize = 12.sp)
                            }
                            Button(
                                onClick = onReset,
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                            ) {
                                Text("Reset All", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }

        // Progress / results
        when (val s = scanState) {
            is ScanState.Scanning -> ScanProgressCard(s.progress)
            is ScanState.Done -> ResultsBadge(count = s.results.size, onClick = onViewResults)
            is ScanState.Error -> Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.error.copy(alpha = 0.1f)).padding(14.dp)
            ) {
                Text("Error: ${s.message}", color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            }
            else -> if (results.isNotEmpty()) ResultsBadge(count = results.size, onClick = onViewResults)
        }
    }
}

private fun categoryColorFor(cat: ValueTypeCategory): Color = when (cat) {
    ValueTypeCategory.INTEGER -> Primary
    ValueTypeCategory.FLOAT   -> Accent
    ValueTypeCategory.TEXT    -> AccentGreen
    ValueTypeCategory.SPECIAL -> Warning
}

@Composable
private fun SectionLabel(text: String) {
    Text(text = text.uppercase(), color = Primary, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ValueTypeDropdown(selected: ValueType, onSelect: (ValueType) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val grouped = ValueType.entries.groupBy { it.category }
    val categoryOrder = listOf(ValueTypeCategory.INTEGER, ValueTypeCategory.FLOAT, ValueTypeCategory.TEXT, ValueTypeCategory.SPECIAL)

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        OutlinedTextField(
            value = selected.label,
            onValueChange = {},
            readOnly = true,
            label = { Text("Type") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
            colors = inputColors()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            categoryOrder.forEach { category ->
                val types = grouped[category] ?: emptyList()
                if (types.isNotEmpty()) {
                    // Category header
                    val categoryColor = when (category) {
                        ValueTypeCategory.INTEGER -> Primary
                        ValueTypeCategory.FLOAT -> Accent
                        ValueTypeCategory.TEXT -> AccentGreen
                        ValueTypeCategory.SPECIAL -> Warning
                    }
                    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                        Text(
                            category.label.uppercase(),
                            color = categoryColor,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.5.sp
                        )
                    }
                    types.forEach { type ->
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Box(
                                        Modifier.size(8.dp).clip(RoundedCornerShape(4.dp))
                                            .background(categoryColor.copy(alpha = if (type == selected) 1f else 0.3f))
                                    )
                                    Column {
                                        Text(type.label, fontWeight = if (type == selected) FontWeight.Bold else FontWeight.Normal)
                                        Text(type.description, fontSize = 11.sp, color = OnSurface.copy(alpha = 0.6f))
                                    }
                                }
                            },
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onSelect(type)
                                expanded = false
                            },
                            trailingIcon = {
                                if (type == selected) {
                                    Icon(Icons.Default.Check, contentDescription = null, tint = Primary, modifier = Modifier.size(18.dp))
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ComparisonControlRow(
    selectedType: ValueType,
    rangeMin: String,
    rangeMax: String,
    onRangeMinChange: (String) -> Unit,
    onRangeMaxChange: (String) -> Unit,
    onComparison: (ScanComparison) -> Unit
) {
    var selectedOp by remember { mutableStateOf<ScanComparison?>(null) }
    val haptic = LocalHapticFeedback.current

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel("Compare against previous")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(ScanComparison.withoutValue.size) { i ->
                val isSel = selectedOp == ScanComparison.withoutValue[i]
                Box(
                    Modifier.clip(RoundedCornerShape(18.dp))
                        .background(if (isSel) Primary.copy(alpha = 0.2f) else SurfaceVariant)
                        .border(1.dp, if (isSel) Primary else SurfaceHigh, RoundedCornerShape(18.dp))
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            selectedOp = ScanComparison.withoutValue[i]
                            onComparison(ScanComparison.withoutValue[i])
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(ScanComparison.withoutValue[i].symbol, color = if (isSel) Primary else OnSurface, fontSize = 13.sp, fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium)
                }
            }
        }
        // Value-require ops: EXACT, INCREASED_BY, DECREASED_BY, BETWEEN
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val valueOps = ScanComparison.entries.filter { it !in ScanComparison.withoutValue }
            items(valueOps.size) { i ->
                val isSel = selectedOp == valueOps[i]
                Box(
                    Modifier.clip(RoundedCornerShape(18.dp))
                        .background(if (isSel) Accent.copy(alpha = 0.2f) else SurfaceVariant)
                        .border(1.dp, if (isSel) Accent else SurfaceHigh, RoundedCornerShape(18.dp))
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            selectedOp = valueOps[i]
                            if (valueOps[i] != ScanComparison.BETWEEN) {
                                onComparison(valueOps[i])
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(valueOps[i].label, color = if (isSel) Accent else OnSurface, fontSize = 11.sp, fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium)
                }
            }
        }
        // Range inputs for BETWEEN
        if (selectedOp == ScanComparison.BETWEEN) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = rangeMin,
                    onValueChange = onRangeMinChange,
                    label = { Text("Min") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    colors = inputColors(),
                    keyboardOptions = KeyboardOptions(keyboardType = keyboardTypeFor(selectedType))
                )
                OutlinedTextField(
                    value = rangeMax,
                    onValueChange = onRangeMaxChange,
                    label = { Text("Max") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    colors = inputColors(),
                    keyboardOptions = KeyboardOptions(keyboardType = keyboardTypeFor(selectedType))
                )
            }
            Button(
                onClick = { onComparison(ScanComparison.BETWEEN) },
                enabled = rangeMin.isNotBlank() && rangeMax.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Accent)
            ) {
                Text("Run Range Scan", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ScanProgressCard(progress: ScanProgress) {
    val animFraction by animateFloatAsState(if (progress.totalRegions > 0) progress.scannedRegions.toFloat() / progress.totalRegions else 0f, tween(300), label = "progress")
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
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text("Found so far: ${progress.foundCount}", color = AccentGreen, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            if (progress.capped) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = Warning, modifier = Modifier.size(12.dp))
                    Text("Capped at ${MemoryReader.MAX_RESULTS}", color = Warning, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
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
    ValueType.STRING -> "Any text"
    ValueType.BYTE_ARRAY -> "Hex bytes e.g. FF 4A ?? 00"
    ValueType.XOR4 -> "Int (XOR with key)"
    ValueType.XOR8 -> "Long (XOR with key)"
    ValueType.ALL -> "All numeric types"
}

private fun keyboardTypeFor(type: ValueType): KeyboardType = when (type) {
    ValueType.STRING, ValueType.BYTE_ARRAY -> KeyboardType.Text
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

private fun isInputValid(input: String, type: ValueType): Boolean {
    if (input.isBlank()) return false
    return try {
        when (type) {
            ValueType.BYTE1 -> input.toInt() in Byte.MIN_VALUE..Byte.MAX_VALUE
            ValueType.BYTE2 -> input.toInt() in Short.MIN_VALUE..Short.MAX_VALUE
            ValueType.BYTE4 -> input.toLong() in Int.MIN_VALUE..Int.MAX_VALUE
            ValueType.BYTE8 -> input.toLongOrNull() != null
            ValueType.FLOAT -> input.toFloatOrNull() != null
            ValueType.DOUBLE -> input.toDoubleOrNull() != null
            ValueType.STRING -> true
            ValueType.BYTE_ARRAY -> input.matches(Regex("^([0-9A-Fa-f]{2} )*[0-9A-Fa-f]{2}$"))
            ValueType.XOR4 -> input.toLongOrNull() != null
            ValueType.XOR8 -> input.toLongOrNull() != null
            ValueType.ALL -> input.toLongOrNull() != null || input.toDoubleOrNull() != null
        }
    } catch (_: NumberFormatException) { false }
}

private fun inputHelperText(type: ValueType): String = when (type) {
    ValueType.BYTE1 -> "Valid range: -128 to 127"
    ValueType.BYTE2 -> "Valid range: -32768 to 32767"
    ValueType.BYTE4 -> "Valid range: -2147483648 to 2147483647"
    ValueType.BYTE8 -> "Any whole number"
    ValueType.FLOAT -> "Decimal number (e.g., 3.14)"
    ValueType.DOUBLE -> "Decimal number"
    ValueType.STRING -> "Any text"
    ValueType.BYTE_ARRAY -> "Hex pairs separated by spaces (e.g., A1 B2 C3)"
    ValueType.XOR4 -> "Integer value"
    ValueType.XOR8 -> "Integer value"
    ValueType.ALL -> "Enter a number"
}
