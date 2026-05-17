package com.androce.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.androce.model.ProcessInfo
import com.androce.model.ScanResult
import com.androce.ui.components.AppDimensions
import com.androce.ui.components.ProcessTopBarSubtitle
import com.androce.ui.components.StatusBadge
import com.androce.ui.components.TabScreenBody
import com.androce.ui.theme.Accent
import com.androce.core.AppPrefs
import com.androce.ui.theme.AccentGreen
import com.androce.ui.theme.Background
import com.androce.ui.theme.Error
import com.androce.ui.theme.Primary
import com.androce.ui.theme.SurfaceHigh
import com.androce.ui.theme.SurfaceVariant
import com.androce.ui.theme.Warning
import com.androce.viewmodel.ScanState
import com.androce.viewmodel.ScanViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ResultsScreen(
    viewModel: ScanViewModel,
    onBack: (() -> Unit)? = null
) {
    val results by viewModel.results.collectAsState()
    val scanState by viewModel.scanState.collectAsState()
    val selectedProcess by viewModel.selectedProcess.collectAsState()
    val selectedCount by remember { derivedStateOf { results.count { it.selected } } }
    val allSelected by remember { derivedStateOf { results.isNotEmpty() && results.all { it.selected } } }
    val selectedAddresses by remember { derivedStateOf { results.filter { it.selected }.map { it.address } } }
    val snackBarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val haptic = LocalHapticFeedback.current

    var showBulkWriteDialog by rememberSaveable { mutableStateOf(false) }
    var bulkWriteValue by rememberSaveable { mutableStateOf("") }
    var showMenu by remember { mutableStateOf(false) }
    var showSaveDialog by rememberSaveable { mutableStateOf(false) }
    var saveName by rememberSaveable { mutableStateOf("") }
    var saveSelectedOnly by rememberSaveable { mutableStateOf(false) }
    var showSavedTables by rememberSaveable { mutableStateOf(false) }
    // Auto-refresh timer: always enabled when results exist and no scan is running
    val isScanning = scanState is ScanState.Scanning
    val refreshInterval = AppPrefs.autoRefreshIntervalMs
    LaunchedEffect(selectedProcess?.pid, results.isEmpty(), isScanning, refreshInterval) {
        if (selectedProcess == null || results.isEmpty() || isScanning || refreshInterval <= 0L) {
            return@LaunchedEffect
        }
        val pid = selectedProcess!!.pid
        while (true) {
            delay(refreshInterval)
            if (viewModel.selectedProcess.value?.pid != pid ||
                results.isEmpty() ||
                scanState is ScanState.Scanning
            ) break
            viewModel.refreshValues()
        }
    }

    LaunchedEffect(selectedProcess?.pid) {
        showBulkWriteDialog = false
        showSaveDialog = false
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackBarHostState) },
        floatingActionButton = {
            AnimatedVisibility(
                visible = selectedCount > 0,
                enter = scaleIn(tween(200)) + fadeIn(tween(200)),
                exit = scaleOut(tween(150)) + fadeOut(tween(150))
            ) {
                ExtendedFloatingActionButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        val selected = results.filter { it.selected }
                        val values = selected.map { it.displayValue() }.distinct()
                        bulkWriteValue = if (values.size == 1) values.first() else ""
                        showBulkWriteDialog = true
                    },
                    containerColor = Primary,
                    icon = { Icon(Icons.Default.Edit, contentDescription = null) },
                    text = { Text("Write ($selectedCount)") }
                )
            }
        },
        containerColor = Background,
        topBar = {
            TopAppBar(
                modifier = Modifier.heightIn(max = 52.dp),
                windowInsets = WindowInsets(0),
                title = {
                    Column {
                        Text(
                            "Results",
                            color = Primary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                        ProcessTopBarSubtitle(selectedProcess)
                    }
                },
                actions = {
                    ResultsTopBarActions(
                        resultsCount = results.size,
                        selectedCount = selectedCount,
                        allSelected = allSelected,
                        showMenu = showMenu,
                        onShowMenuChange = { showMenu = it },
                        onSelectAll = { viewModel.selectAll(!allSelected) },
                        onSave = {
                            saveSelectedOnly = selectedCount > 0
                            saveName = ""
                            showSaveDialog = true
                        },
                        onShowSaved = { showSavedTables = true },
                        onFreezeSelected = { viewModel.bulkFreezeSelected(true) },
                        onUnfreezeSelected = { viewModel.bulkFreezeSelected(false) },
                        onDeleteSelected = { viewModel.removeSelected() }
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Background)
            )
        }
    ) { padding ->
        if (results.isEmpty()) {
            TabScreenBody(padding = padding) {
                Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            Modifier.size(72.dp).clip(RoundedCornerShape(18.dp))
                                .background(SurfaceVariant),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Memory, contentDescription = null, tint = SurfaceHigh, modifier = Modifier.size(36.dp))
                        }
                        Text(
                            if (selectedProcess == null) "No process attached" else "No results yet",
                            color = MaterialTheme.colorScheme.onBackground,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp
                        )
                        Text(
                            if (selectedProcess == null) {
                                "Select a target on the Process tab,\nthen run a scan from Scanner"
                            } else {
                                "Run a scan on the Scanner tab for\n${selectedProcess!!.displayName()}"
                            },
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(
                    horizontal = AppDimensions.paddingXLarge,
                    vertical = 2.dp
                ),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                items(
                    items = results,
                    key = { it.address }
                ) { result ->
                    ResultRow(
                        result = result,
                        onToggleSelect = { viewModel.toggleSelected(result.address) },
                        onToggleFreeze = { viewModel.toggleFreeze(result) },
                        onWrite = { newVal -> viewModel.writeAddress(result.address, newVal) },
                        onCopyAddress = {
                            clipboard.setText(AnnotatedString(result.addressHex))
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            scope.launch { snackBarHostState.showSnackbar("Address copied: ${result.addressHex}") }
                        },
                        onDelete = { viewModel.removeResult(result.address) }
                    )
                }
            }
        }
    }

    if (showBulkWriteDialog) {
        AlertDialog(
            onDismissRequest = { showBulkWriteDialog = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = {
                Text(
                    "Write to $selectedCount address${if (selectedCount != 1) "es" else ""}",
                    color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.Bold
                )
            },
            text = {
                OutlinedTextField(
                    value = bulkWriteValue,
                    onValueChange = { bulkWriteValue = it },
                    label = { Text("New value") },
                    singleLine = true,
                    trailingIcon = {
                        if (bulkWriteValue.isNotEmpty()) {
                            IconButton(onClick = { bulkWriteValue = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear", tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), modifier = Modifier.size(18.dp))
                            }
                        }
                    },
                    colors = inputColors()
                )
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = {
                        viewModel.writeBulkAndFreeze(selectedAddresses, bulkWriteValue)
                        showBulkWriteDialog = false
                        bulkWriteValue = ""
                    }) {
                        Icon(Icons.Default.AcUnit, contentDescription = null, tint = Accent, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Write & Freeze", color = Accent)
                    }
                    TextButton(onClick = {
                        viewModel.writeBulk(selectedAddresses, bulkWriteValue)
                        showBulkWriteDialog = false
                        bulkWriteValue = ""
                    }) { Text("Write", color = Primary) }
                }
            },
            dismissButton = {
                TextButton(onClick = { showBulkWriteDialog = false }) {
                    Text("Cancel", color = MaterialTheme.colorScheme.onSurface)
                }
            }
        )
    }

    if (showSaveDialog) {
        val saveCount = if (saveSelectedOnly) selectedCount else results.size
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = {
                Text(
                    if (saveSelectedOnly) "Save Selected Addresses" else "Save All Addresses",
                    color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = saveName,
                        onValueChange = { saveName = it },
                        label = { Text("Table name") },
                        singleLine = true,
                        colors = inputColors()
                    )
                    Text(
                        "Saving $saveCount address${if (saveCount != 1) "es" else ""}",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), fontSize = 12.sp
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (saveName.isNotBlank()) {
                        val success = if (saveSelectedOnly) {
                            viewModel.saveCheatTable(saveName, selectedAddresses)
                        } else {
                            viewModel.saveCheatTable(saveName)
                        }
                        if (success) {
                            scope.launch { snackBarHostState.showSnackbar("Table saved") }
                        } else {
                            scope.launch { snackBarHostState.showSnackbar("Failed to save table") }
                        }
                        showSaveDialog = false
                    }
                }) { Text("Save", color = Primary) }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false }) {
                    Text("Cancel", color = MaterialTheme.colorScheme.onSurface)
                }
            }
        )
    }

    BackHandler(enabled = showSavedTables) {
        showSavedTables = false
    }

    if (showSavedTables) {
        SavedTablesScreen(
            viewModel = viewModel,
            onBack = { showSavedTables = false },
            onTableLoaded = { count ->
                scope.launch {
                    snackBarHostState.showSnackbar("Loaded $count address${if (count != 1) "es" else ""}")
                }
            }
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ResultRow(
    result: ScanResult,
    onToggleSelect: () -> Unit,
    onToggleFreeze: () -> Unit,
    onWrite: (String) -> Unit,
    onCopyAddress: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    var isEditing by remember(result.address) { mutableStateOf(false) }
    var editValue by remember(result.address, result.currentBytes) { mutableStateOf(result.displayValue()) }

    // Cache color calculations
    val bgColor = remember(result.selected) {
        if (result.selected) Primary.copy(alpha = 0.08f) else SurfaceVariant
    }
    val borderColor = remember(result.frozen, result.selected) {
        when {
            result.frozen -> Accent.copy(alpha = 0.6f)
            result.selected -> Primary.copy(alpha = 0.4f)
            else -> Color.Transparent
        }
    }
    val typeColor = remember(result.valueType.category) {
        when (result.valueType.category) {
            com.androce.model.ValueTypeCategory.INTEGER -> Primary
            com.androce.model.ValueTypeCategory.FLOAT -> Accent
            com.androce.model.ValueTypeCategory.TEXT -> AccentGreen
            com.androce.model.ValueTypeCategory.SPECIAL -> Warning
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp) // Fixed minimum height for better recycling
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .border(
                width = if (result.frozen || result.selected) 1.5.dp else 0.dp,
                color = borderColor,
                shape = RoundedCornerShape(12.dp)
            )
            .combinedClickable(
                onClick = { onToggleSelect() },
                onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onCopyAddress() }
            )
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        // Top row: Address + Type chip + Action buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Selection indicator dot
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(if (result.selected) Primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f))
                )
                Text(
                    text = result.addressHex,
                    color = Accent,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                // Type label chip
                Box(
                    Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(typeColor.copy(alpha = 0.12f))
                        .border(0.5.dp, typeColor.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 1.dp)
                ) {
                    Text(
                        result.valueType.label,
                        color = typeColor,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        lineHeight = 12.sp
                    )
                }
            }

            // Action buttons row
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                // Freeze toggle (small icon button)
                IconButton(
                    onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onToggleFreeze() },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.AcUnit,
                        contentDescription = if (result.frozen) "Frozen" else "Freeze",
                        tint = if (result.frozen) Accent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                        modifier = Modifier.size(16.dp)
                    )
                }
                // Edit button
                IconButton(
                    onClick = {
                        if (isEditing) { onWrite(editValue); isEditing = false }
                        else { editValue = result.displayValue(); isEditing = true }
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = if (isEditing) Icons.Default.Check else Icons.Default.Edit,
                        contentDescription = if (isEditing) "Confirm" else "Edit",
                        tint = if (isEditing) AccentGreen else Primary,
                        modifier = Modifier.size(16.dp)
                    )
                }
                // Delete button
                IconButton(
                    onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onDelete() },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete",
                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(4.dp))

        // Bottom: Value display or edit field
        if (isEditing) {
            OutlinedTextField(
                value = editValue,
                onValueChange = { editValue = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = inputColors(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onWrite(editValue); isEditing = false })
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = result.displayValue(),
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 15.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold
                )
                // Change direction badge
                if (result.changeDirection != com.androce.model.ChangeDirection.NONE) {
                    val badgeColor = when (result.changeDirection) {
                        com.androce.model.ChangeDirection.UP -> AccentGreen
                        com.androce.model.ChangeDirection.DOWN -> MaterialTheme.colorScheme.error
                        else -> Color.Transparent
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(badgeColor.copy(alpha = 0.12f))
                            .border(0.5.dp, badgeColor.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    ) {
                        Box(Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(badgeColor))
                        Text(
                            text = result.deltaDisplay,
                            color = badgeColor,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultsTopBarActions(
    resultsCount: Int,
    selectedCount: Int,
    allSelected: Boolean,
    showMenu: Boolean,
    onShowMenuChange: (Boolean) -> Unit,
    onSelectAll: () -> Unit,
    onSave: () -> Unit,
    onShowSaved: () -> Unit,
    onFreezeSelected: () -> Unit,
    onUnfreezeSelected: () -> Unit,
    onDeleteSelected: () -> Unit
) {
    if (resultsCount > 0) {
        StatusBadge(
            text = "$resultsCount results",
            isActive = true
        )
    }
    IconButton(
        onClick = onSelectAll,
        enabled = resultsCount > 0
    ) {
        Icon(
            if (allSelected) Icons.Default.Clear else Icons.Default.SelectAll,
            contentDescription = if (allSelected) "Deselect all" else "Select all",
            tint = Primary,
            modifier = Modifier.size(22.dp)
        )
    }
    if (resultsCount > 0) {
        IconButton(onClick = onSave) {
            Icon(
                Icons.Default.Save,
                contentDescription = if (selectedCount > 0) "Save selected" else "Save all results",
                tint = Primary,
                modifier = Modifier.size(22.dp)
            )
        }
    }
    IconButton(onClick = onShowSaved) {
        Icon(
            Icons.Default.FolderOpen,
            contentDescription = "Saved lists",
            tint = Accent,
            modifier = Modifier.size(22.dp)
        )
    }
    if (selectedCount > 0) {
        Box {
            IconButton(onClick = { onShowMenuChange(true) }) {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = "Selection actions",
                    tint = Primary,
                    modifier = Modifier.size(22.dp)
                )
            }
            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { onShowMenuChange(false) }
            ) {
                DropdownMenuItem(
                    text = { Text("Freeze Selected ($selectedCount)") },
                    leadingIcon = { Icon(Icons.Default.AcUnit, contentDescription = null, tint = Accent) },
                    onClick = {
                        onShowMenuChange(false)
                        onFreezeSelected()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Unfreeze Selected ($selectedCount)") },
                    leadingIcon = { Icon(Icons.Default.AcUnit, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)) },
                    onClick = {
                        onShowMenuChange(false)
                        onUnfreezeSelected()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Delete Selected ($selectedCount)") },
                    leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                    onClick = {
                        onShowMenuChange(false)
                        onDeleteSelected()
                    }
                )
            }
        }
    }
}
