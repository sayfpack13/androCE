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
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.QuestionMark
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults.SecondaryIndicator
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.androce.model.RegionFilter
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
    val regionFilter by viewModel.regionFilter.collectAsState()
    val isPaused by viewModel.isPaused.collectAsState()
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    var searchInput by remember { mutableStateOf(viewModel.searchInput) }
    var selectedType by remember { mutableStateOf(viewModel.selectedValueType) }
    var xorKey by remember { mutableStateOf(viewModel.xorKey.toString()) }
    var rangeMin by remember { mutableStateOf(viewModel.rangeMin) }
    var rangeMax by remember { mutableStateOf(viewModel.rangeMax) }
    var selectedTab by remember { mutableStateOf(0) }

    // When scan starts, jump to Scan tab
    LaunchedEffect(scanState is ScanState.Scanning) {
        if (scanState is ScanState.Scanning) selectedTab = 1
    }

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

    val tabs = listOf(
        Icons.Default.Tune to "Configure",
        Icons.Default.PlayArrow to "Scan"
    )

    Scaffold(
        topBar = {
            Column {
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
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = Primary,
                    indicator = { tabPositions ->
                        SecondaryIndicator(
                            modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                            color = Accent
                        )
                    },
                    divider = {}
                ) {
                    tabs.forEachIndexed { index, (icon, label) ->
                        val selected = selectedTab == index
                        Tab(
                            selected = selected,
                            onClick = { selectedTab = index },
                            selectedContentColor = Primary,
                            unselectedContentColor = OnSurface.copy(alpha = 0.5f)
                        ) {
                            Row(
                                Modifier.padding(vertical = 12.dp, horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
                                Text(label, fontSize = 13.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                                // Badge on Scan tab showing result count
                                if (index == 1 && results.isNotEmpty()) {
                                    Box(
                                        Modifier.clip(RoundedCornerShape(8.dp))
                                            .background(AccentGreen.copy(alpha = 0.2f))
                                            .padding(horizontal = 5.dp, vertical = 1.dp)
                                    ) {
                                        Text("${results.size}", color = AccentGreen, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            when (selectedTab) {
                0 -> ConfigureTab(
                    selectedType = selectedType,
                    searchInput = searchInput,
                    xorKey = xorKey,
                    regionFilter = regionFilter,
                    resultsEmpty = results.isEmpty(),
                    onRegionFilterChange = { viewModel.setRegionFilter(it) },
                    onTypeChange = { t ->
                        selectedType = t
                        viewModel.selectedValueType = t
                        searchInput = ""
                        viewModel.searchInput = ""
                    },
                    onSearchChange = { searchInput = it; viewModel.searchInput = it },
                    onXorChange = { xorKey = it; viewModel.xorKey = it.toLongOrNull() ?: 0L },
                    onScanReady = {
                        selectedTab = 1
                        triggerScan()
                    },
                    onUnknownInitial = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.selectedValueType = selectedType
                        viewModel.unknownInitialScan()
                        selectedTab = 1
                    }
                )
                1 -> ScanTab(
                    scanState = scanState,
                    results = results,
                    regions = regions,
                    selectedType = selectedType,
                    searchInput = searchInput,
                    rangeMin = rangeMin,
                    rangeMax = rangeMax,
                    isScanning = scanState is ScanState.Scanning,
                    isPaused = isPaused,
                    onRangeMinChange = { rangeMin = it; viewModel.rangeMin = it },
                    onRangeMaxChange = { rangeMax = it; viewModel.rangeMax = it },
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
                    onStop = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); viewModel.cancelScan() },
                    onPauseResume = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); viewModel.togglePause() },
                    onReset = { viewModel.resetScan() },
                    onViewResults = onViewResults,
                    onGoToConfigure = { selectedTab = 0 }
                )
            }
        }
    }
}

@Composable
private fun ConfigureTab(
    selectedType: ValueType,
    searchInput: String,
    xorKey: String,
    regionFilter: RegionFilter,
    resultsEmpty: Boolean,
    onRegionFilterChange: (RegionFilter) -> Unit,
    onTypeChange: (ValueType) -> Unit,
    onSearchChange: (String) -> Unit,
    onXorChange: (String) -> Unit,
    onScanReady: () -> Unit,
    onUnknownInitial: () -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SectionLabel("Memory Scope")
        RegionScopeChips(selected = regionFilter, onSelect = onRegionFilterChange)

        SectionLabel("Value Type")
        ValueTypeGrid(selected = selectedType, onSelect = onTypeChange)

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

        SectionLabel("Search Value")
        OutlinedTextField(
            value = searchInput,
            onValueChange = onSearchChange,
            label = { Text(searchHint(selectedType)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = selectedType != ValueType.STRING_UTF8 && selectedType != ValueType.STRING_UTF16,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardTypeFor(selectedType), imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { if (searchInput.isNotBlank()) onScanReady() }),
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Primary) },
            trailingIcon = {
                if (searchInput.isNotEmpty()) {
                    IconButton(onClick = { onSearchChange("") }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear", tint = OnSurface.copy(alpha = 0.5f), modifier = Modifier.size(18.dp))
                    }
                }
            },
            colors = inputColors()
        )

        // "Go to Scan" button at the bottom of configure tab
        Button(
            onClick = onScanReady,
            enabled = searchInput.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Primary)
        ) {
            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Start Scan", fontWeight = FontWeight.Bold)
        }

        // Unknown initial value — only meaningful when starting fresh
        if (resultsEmpty && !selectedType.isVariableLength) {
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
    }
}

@Composable
private fun RegionScopeChips(selected: RegionFilter, onSelect: (RegionFilter) -> Unit) {
    val options = listOf(RegionFilter.HEAP_STACK_ANON, RegionFilter.ALL)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(options.size) { i ->
            val isSel = selected::class == options[i]::class
            val color = if (isSel) Primary else OnSurface.copy(alpha = 0.5f)
            Box(
                Modifier.clip(RoundedCornerShape(20.dp))
                    .background(if (isSel) Primary.copy(alpha = 0.15f) else SurfaceVariant)
                    .border(1.dp, color.copy(alpha = if (isSel) 0.8f else 0.2f), RoundedCornerShape(20.dp))
                    .clickable { onSelect(options[i]) }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text(options[i].label, color = color, fontSize = 12.sp, fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium)
            }
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
    isScanning: Boolean,
    isPaused: Boolean,
    onRangeMinChange: (String) -> Unit,
    onRangeMaxChange: (String) -> Unit,
    onFirstScan: () -> Unit,
    onRefinedScan: () -> Unit,
    onComparison: (ScanComparison) -> Unit,
    onStop: () -> Unit,
    onPauseResume: () -> Unit,
    onReset: () -> Unit,
    onViewResults: () -> Unit,
    onGoToConfigure: () -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Summary card showing what will be scanned
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .background(SurfaceVariant).padding(14.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Target", color = OnSurface.copy(alpha = 0.6f), fontSize = 10.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        val typeColor = categoryColorFor(selectedType.category)
                        Box(Modifier.clip(RoundedCornerShape(6.dp)).background(typeColor.copy(alpha = 0.15f))
                            .border(1.dp, typeColor.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp)) {
                            Text(selectedType.label, color = typeColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        if (searchInput.isNotBlank()) {
                            Text("= $searchInput", color = OnBackground, fontSize = 13.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        } else {
                            Text("No value set", color = OnSurface.copy(alpha = 0.4f), fontSize = 12.sp)
                        }
                    }
                }
                AnimatedContent(targetState = regions.size, transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) }, label = "region_count") { count ->
                    Column(horizontalAlignment = Alignment.End) {
                        Text(if (count == 0) "…" else "$count", color = Accent, fontSize = 18.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        Text("regions", color = OnSurface.copy(alpha = 0.5f), fontSize = 10.sp)
                    }
                }
            }
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
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = onFirstScan,
                        enabled = searchInput.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Primary)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("First Scan", fontWeight = FontWeight.Bold)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = onRefinedScan,
                            enabled = results.isNotEmpty() && searchInput.isNotBlank(),
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = SurfaceVariant)
                        ) {
                            Text("Refined Scan")
                        }
                        Button(
                            onClick = onReset,
                            enabled = results.isNotEmpty() || scanState is ScanState.Done,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text("Reset")
                        }
                    }
                    if (searchInput.isBlank()) {
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                                .background(Warning.copy(alpha = 0.1f)).border(1.dp, Warning.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                                .clickable(onClick = onGoToConfigure).padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.Tune, contentDescription = null, tint = Warning, modifier = Modifier.size(16.dp))
                            Text("Set a value in Configure tab first", color = Warning, fontSize = 12.sp)
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

@Composable
private fun ValueTypeGrid(selected: ValueType, onSelect: (ValueType) -> Unit) {
    val haptic = LocalHapticFeedback.current
    val grouped = ValueType.entries.groupBy { it.category }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ValueTypeCategory.entries.forEach { category ->
            val categoryColor = when (category) {
                ValueTypeCategory.INTEGER -> Primary
                ValueTypeCategory.FLOAT   -> Accent
                ValueTypeCategory.TEXT    -> AccentGreen
                ValueTypeCategory.SPECIAL -> Warning
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(bottom = 2.dp)
            ) {
                Box(Modifier.size(3.dp, 14.dp).clip(RoundedCornerShape(2.dp)).background(categoryColor))
                Text(text = category.name.lowercase(), color = Primary, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
            }
            (grouped[category] ?: emptyList()).chunked(2).forEach { rowTypes ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rowTypes.forEach { type ->
                        ValueTypeCard(
                            type = type,
                            isSelected = type == selected,
                            accentColor = categoryColor,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onSelect(type)
                            }
                        )
                    }
                    // fill empty slot if odd number
                    if (rowTypes.size == 1) Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

@Composable
private fun ValueTypeCard(
    type: ValueType,
    isSelected: Boolean,
    accentColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val bgColor by animateColorAsState(
        if (isSelected) accentColor.copy(alpha = 0.18f) else SurfaceVariant,
        tween(150), label = "card_bg"
    )
    val borderColor by animateColorAsState(
        if (isSelected) accentColor else Color.Transparent,
        tween(150), label = "card_border"
    )

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .border(1.5.dp, borderColor, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = type.label,
                    color = if (isSelected) accentColor else OnBackground,
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                )
                Text(
                    text = type.description,
                    color = OnSurface.copy(alpha = if (isSelected) 0.9f else 0.6f),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 13.sp
                )
            }
            if (isSelected) {
                Spacer(Modifier.width(6.dp))
                Box(
                    Modifier.size(18.dp).clip(RoundedCornerShape(9.dp))
                        .background(Brush.linearGradient(listOf(accentColor, accentColor.copy(alpha = 0.6f)))),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(11.dp))
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
    ValueType.STRING_UTF8 -> "UTF-8 string"
    ValueType.STRING_UTF16 -> "UTF-16 string"
    ValueType.BYTE_ARRAY -> "Hex bytes e.g. FF 4A ?? 00"
    ValueType.XOR4 -> "Int (XOR with key)"
    ValueType.XOR8 -> "Long (XOR with key)"
    ValueType.ALL -> "All numeric types"
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
