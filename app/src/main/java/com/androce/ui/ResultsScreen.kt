package com.androce.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.androce.model.ScanResult
import com.androce.ui.theme.Accent
import com.androce.ui.theme.AccentGreen
import com.androce.ui.theme.Primary
import com.androce.ui.theme.SurfaceHigh
import com.androce.ui.theme.SurfaceVariant
import com.androce.viewmodel.ScanViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultsScreen(
    viewModel: ScanViewModel,
    onBack: () -> Unit
) {
    val results by viewModel.results.collectAsState()
    val selectedCount = results.count { it.selected }
    val allSelected = results.isNotEmpty() && results.all { it.selected }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val haptic = LocalHapticFeedback.current

    var showBulkWriteDialog by rememberSaveable { mutableStateOf(false) }
    var bulkWriteValue by rememberSaveable { mutableStateOf("") }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Results", color = Primary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        AnimatedContent(
                            targetState = results.size,
                            transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) },
                            label = "count"
                        ) { count ->
                            Text(
                                "$count address${if (count != 1) "es" else ""}",
                                color = Accent, fontSize = 12.sp, fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Primary)
                    }
                },
                actions = {
                    IconButton(onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); viewModel.refreshValues() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = Primary)
                    }
                    IconButton(onClick = { viewModel.selectAll(!allSelected) }) {
                        Icon(Icons.Default.SelectAll, contentDescription = "Select all", tint = Primary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        floatingActionButton = {
            AnimatedVisibility(
                visible = selectedCount > 0,
                enter = scaleIn(tween(200)) + fadeIn(tween(200)),
                exit = scaleOut(tween(150)) + fadeOut(tween(150))
            ) {
                ExtendedFloatingActionButton(
                    onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); showBulkWriteDialog = true },
                    containerColor = Primary,
                    icon = { Icon(Icons.Default.Edit, contentDescription = null) },
                    text = { Text("Write ($selectedCount)") }
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        if (results.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(
                        Modifier.size(72.dp).clip(RoundedCornerShape(18.dp))
                            .background(SurfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Memory, contentDescription = null, tint = SurfaceHigh, modifier = Modifier.size(36.dp))
                    }
                    Text("No results found", color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Text("Try adjusting your search value\nor scan type", color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
                }
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                items(results, key = { it.address }) { result ->
                    SwipeToDismissResultRow(
                        result = result,
                        onToggleSelect = { viewModel.toggleSelected(result.address) },
                        onToggleFreeze = { viewModel.toggleFreeze(result) },
                        onWrite = { newVal -> viewModel.writeAddress(result.address, newVal) },
                        onDismiss = { viewModel.removeResult(result.address) },
                        onCopyAddress = {
                            clipboard.setText(AnnotatedString(result.addressHex))
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            scope.launch { snackbarHostState.showSnackbar("Address copied: ${result.addressHex}") }
                        }
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
                TextButton(onClick = {
                    val addrs = results.filter { it.selected }.map { it.address }
                    viewModel.writeBulk(addrs, bulkWriteValue)
                    showBulkWriteDialog = false
                    bulkWriteValue = ""
                }) { Text("Write", color = Primary) }
            },
            dismissButton = {
                TextButton(onClick = { showBulkWriteDialog = false }) {
                    Text("Cancel", color = MaterialTheme.colorScheme.onSurface)
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun SwipeToDismissResultRow(
    result: ScanResult,
    onToggleSelect: () -> Unit,
    onToggleFreeze: () -> Unit,
    onWrite: (String) -> Unit,
    onDismiss: () -> Unit,
    onCopyAddress: () -> Unit
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { it == SwipeToDismissBoxValue.EndToStart }
    )

    LaunchedEffect(dismissState.currentValue) {
        if (dismissState.currentValue == SwipeToDismissBoxValue.EndToStart) {
            onDismiss()
        }
    }

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.error),
                contentAlignment = Alignment.CenterEnd
            ) {
                Icon(Icons.Default.Delete, contentDescription = "Remove", tint = Color.White, modifier = Modifier.padding(end = 20.dp))
            }
        }
    ) {
        ResultRow(
            result = result,
            onToggleSelect = onToggleSelect,
            onToggleFreeze = onToggleFreeze,
            onWrite = onWrite,
            onCopyAddress = onCopyAddress
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ResultRow(
    result: ScanResult,
    onToggleSelect: () -> Unit,
    onToggleFreeze: () -> Unit,
    onWrite: (String) -> Unit,
    onCopyAddress: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    var isEditing by remember { mutableStateOf(false) }
    var editValue by remember { mutableStateOf(result.displayValue()) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(SurfaceVariant)
            .border(
                width = if (result.frozen) 1.dp else 0.dp,
                color = if (result.frozen) Accent.copy(alpha = 0.5f) else Color.Transparent,
                shape = RoundedCornerShape(10.dp)
            )
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .animateContentSize(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = result.selected,
            onCheckedChange = { onToggleSelect() },
            colors = CheckboxDefaults.colors(checkedColor = Primary, uncheckedColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)),
            modifier = Modifier.size(24.dp)
        )
        Spacer(Modifier.width(8.dp))

        Column(Modifier.weight(1f)) {
            Text(
                text = result.addressHex,
                color = Accent,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.combinedClickable(
                    onClick = {},
                    onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onCopyAddress() }
                )
            )
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
                Text(
                    text = result.displayValue(),
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        Spacer(Modifier.width(4.dp))

        IconButton(
            onClick = {
                if (isEditing) { onWrite(editValue); isEditing = false }
                else { editValue = result.displayValue(); isEditing = true }
            },
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                imageVector = if (isEditing) Icons.Default.Check else Icons.Default.Edit,
                contentDescription = "Edit",
                tint = if (isEditing) AccentGreen else Primary,
                modifier = Modifier.size(18.dp)
            )
        }

        Switch(
            checked = result.frozen,
            onCheckedChange = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onToggleFreeze() },
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Accent,
                uncheckedTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
            ),
            thumbContent = if (result.frozen) {
                { Icon(Icons.Default.AcUnit, contentDescription = null, modifier = Modifier.size(12.dp), tint = Accent) }
            } else null
        )
    }
}
