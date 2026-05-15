package com.androce.ui

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.androce.core.ScanProgress
import com.androce.model.ValueType
import com.androce.ui.theme.Accent
import com.androce.ui.theme.Primary
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

    var searchInput by remember { mutableStateOf(viewModel.searchInput) }
    var selectedType by remember { mutableStateOf(viewModel.selectedValueType) }
    var xorKey by remember { mutableStateOf(viewModel.xorKey.toString()) }

    LaunchedEffect(Unit) { viewModel.loadRegions() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "Memory Search",
                            color = Primary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                        Text(
                            viewModel.selectedProcess?.let { "${it.name}  [PID ${it.pid}]" } ?: "",
                            color = Accent,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Primary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
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
            }

            if (selectedType == ValueType.XOR4 || selectedType == ValueType.XOR8) {
                SectionLabel("XOR Key")
                OutlinedTextField(
                    value = xorKey,
                    onValueChange = {
                        xorKey = it
                        viewModel.xorKey = it.toLongOrNull() ?: 0L
                    },
                    label = { Text("XOR Key (decimal)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = inputColors()
                )
            }

            SectionLabel("Search Value")
            OutlinedTextField(
                value = searchInput,
                onValueChange = {
                    searchInput = it
                    viewModel.searchInput = it
                },
                label = { Text(searchHint(selectedType)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = selectedType != ValueType.STRING_UTF8 && selectedType != ValueType.STRING_UTF16,
                colors = inputColors(),
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Primary) }
            )

            SectionLabel("Memory Regions")
            Text(
                text = if (regions.isEmpty()) "Loading regions…"
                else "${regions.size} readable regions",
                color = Accent,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace
            )

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = {
                        viewModel.searchInput = searchInput
                        viewModel.selectedValueType = selectedType
                        viewModel.firstScan()
                    },
                    enabled = scanState !is ScanState.Scanning,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Primary)
                ) {
                    Text("First Scan")
                }
                Button(
                    onClick = {
                        viewModel.searchInput = searchInput
                        viewModel.selectedValueType = selectedType
                        viewModel.refinedScan()
                    },
                    enabled = results.isNotEmpty() && scanState !is ScanState.Scanning,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = SurfaceVariant)
                ) {
                    Text("Refined Scan")
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
                is ScanState.Done -> {
                    ResultsBadge(count = s.results.size, onClick = onViewResults)
                }
                is ScanState.Error -> {
                    Text(
                        "Error: ${s.message}",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 13.sp
                    )
                }
                else -> {}
            }

            if (results.isNotEmpty() && scanState !is ScanState.Scanning) {
                ResultsBadge(count = results.size, onClick = onViewResults)
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        color = Primary,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.5.sp
    )
}

@Composable
private fun ValueTypePicker(selected: ValueType, onSelect: (ValueType) -> Unit) {
    val scroll = rememberScrollState()
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(scroll),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ValueType.entries.forEach { type ->
            val isSelected = type == selected
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isSelected) Primary else SurfaceVariant)
                    .border(
                        1.dp,
                        if (isSelected) Primary else Color.Transparent,
                        RoundedCornerShape(8.dp)
                    )
                    .clickable { onSelect(type) }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
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
    val fraction = if (progress.totalRegions > 0)
        progress.scannedRegions.toFloat() / progress.totalRegions.toFloat()
    else 0f

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceVariant)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = Primary,
                strokeWidth = 2.dp
            )
            Spacer(Modifier.width(10.dp))
            Text(
                "Scanning… ${progress.scannedRegions}/${progress.totalRegions} regions",
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 13.sp
            )
        }
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth(),
            color = Primary
        )
        Text(
            "Found so far: ${progress.foundCount}",
            color = Accent,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun ResultsBadge(count: Int, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceVariant)
            .clickable(onClick = onClick)
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Check, contentDescription = null, tint = Accent)
            Spacer(Modifier.width(10.dp))
            Text(
                "$count result${if (count != 1) "s" else ""} found — tap to view",
                color = Accent,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

private fun searchHint(type: ValueType): String = when (type) {
    ValueType.BYTE1 -> "Enter byte value (-128 to 127)"
    ValueType.BYTE2 -> "Enter short value"
    ValueType.BYTE4 -> "Enter int value"
    ValueType.BYTE8 -> "Enter long value"
    ValueType.FLOAT -> "Enter float (e.g. 3.14)"
    ValueType.DOUBLE -> "Enter double"
    ValueType.STRING_UTF8 -> "Enter UTF-8 string"
    ValueType.STRING_UTF16 -> "Enter UTF-16 string"
    ValueType.BYTE_ARRAY -> "Hex bytes, space-separated, ?? for wildcard"
    ValueType.XOR4 -> "Enter int (will be XOR'd with key)"
    ValueType.XOR8 -> "Enter long (will be XOR'd with key)"
}

@Composable
private fun inputColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Primary,
    unfocusedBorderColor = SurfaceVariant,
    focusedTextColor = MaterialTheme.colorScheme.onBackground,
    unfocusedTextColor = MaterialTheme.colorScheme.onBackground,
    cursorColor = Primary,
    focusedLabelColor = Primary
)
