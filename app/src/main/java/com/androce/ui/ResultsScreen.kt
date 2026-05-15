package com.androce.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Edit
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
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.androce.model.ScanResult
import com.androce.ui.theme.Accent
import com.androce.ui.theme.Primary
import com.androce.ui.theme.SurfaceVariant
import com.androce.viewmodel.ScanViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultsScreen(
    viewModel: ScanViewModel,
    onBack: () -> Unit
) {
    val results by viewModel.results.collectAsState()
    val selectedCount = results.count { it.selected }
    val allSelected = results.isNotEmpty() && results.all { it.selected }

    var showBulkWriteDialog by rememberSaveable { mutableStateOf(false) }
    var bulkWriteValue by rememberSaveable { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "Results",
                            color = Primary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                        Text(
                            "${results.size} address${if (results.size != 1) "es" else ""}",
                            color = Accent,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Primary)
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refreshValues() }) {
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
            if (selectedCount > 0) {
                ExtendedFloatingActionButton(
                    onClick = { showBulkWriteDialog = true },
                    containerColor = Primary,
                    icon = { Icon(Icons.Default.Edit, contentDescription = null) },
                    text = { Text("Write ($selectedCount)") }
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        if (results.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No results found.", color = MaterialTheme.colorScheme.onSurface)
            }
        } else {
            LazyColumn(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                items(results, key = { it.address }) { result ->
                    ResultRow(
                        result = result,
                        onToggleSelect = { viewModel.toggleSelected(result.address) },
                        onToggleFreeze = { viewModel.toggleFreeze(result) },
                        onWrite = { newVal -> viewModel.writeAddress(result.address, newVal) }
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
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                OutlinedTextField(
                    value = bulkWriteValue,
                    onValueChange = { bulkWriteValue = it },
                    label = { Text("New value") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Primary,
                        unfocusedBorderColor = SurfaceVariant,
                        focusedTextColor = MaterialTheme.colorScheme.onBackground,
                        unfocusedTextColor = MaterialTheme.colorScheme.onBackground,
                        cursorColor = Primary,
                        focusedLabelColor = Primary
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val addrs = results.filter { it.selected }.map { it.address }
                    viewModel.writeBulk(addrs, bulkWriteValue)
                    showBulkWriteDialog = false
                    bulkWriteValue = ""
                }) {
                    Text("Write", color = Primary)
                }
            },
            dismissButton = {
                TextButton(onClick = { showBulkWriteDialog = false }) {
                    Text("Cancel", color = MaterialTheme.colorScheme.onSurface)
                }
            }
        )
    }
}

@Composable
private fun ResultRow(
    result: ScanResult,
    onToggleSelect: () -> Unit,
    onToggleFreeze: () -> Unit,
    onWrite: (String) -> Unit
) {
    var isEditing by remember { mutableStateOf(false) }
    var editValue by remember { mutableStateOf(result.displayValue()) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(SurfaceVariant)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = result.selected,
            onCheckedChange = { onToggleSelect() },
            colors = CheckboxDefaults.colors(
                checkedColor = Primary,
                uncheckedColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            ),
            modifier = Modifier.size(24.dp)
        )
        Spacer(Modifier.width(8.dp))

        Column(Modifier.weight(1f)) {
            Text(
                text = result.addressHex,
                color = Accent,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
            if (isEditing) {
                OutlinedTextField(
                    value = editValue,
                    onValueChange = { editValue = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Primary,
                        unfocusedBorderColor = SurfaceVariant,
                        focusedTextColor = MaterialTheme.colorScheme.onBackground,
                        unfocusedTextColor = MaterialTheme.colorScheme.onBackground,
                        cursorColor = Primary
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        onWrite(editValue)
                        isEditing = false
                    })
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

        Spacer(Modifier.width(6.dp))

        IconButton(
            onClick = {
                if (isEditing) {
                    onWrite(editValue)
                    isEditing = false
                } else {
                    editValue = result.displayValue()
                    isEditing = true
                }
            },
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                imageVector = if (isEditing) Icons.Default.Check else Icons.Default.Edit,
                contentDescription = "Edit",
                tint = if (isEditing) Accent else Primary,
                modifier = Modifier.size(18.dp)
            )
        }

        Switch(
            checked = result.frozen,
            onCheckedChange = { onToggleFreeze() },
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
