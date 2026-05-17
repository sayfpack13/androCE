package com.androce.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.androce.viewmodel.ScanViewModel
import com.androce.viewmodel.CheatTableMeta
import com.androce.ui.theme.Accent
import com.androce.ui.theme.Primary
import com.androce.ui.theme.SurfaceVariant
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedTablesScreen(
    viewModel: ScanViewModel,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var tableNames by remember { mutableStateOf(viewModel.listSavedTables()) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf("") }
    var renameValue by remember { mutableStateOf("") }
    var showSaveCurrentDialog by remember { mutableStateOf(false) }
    var saveName by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("Saved Lists", color = Primary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Primary)
                    }
                },
                actions = {
                    TextButton(onClick = { showSaveCurrentDialog = true }) {
                        Icon(Icons.Default.Save, contentDescription = null, tint = Accent, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Save Current", color = Accent, fontSize = 12.sp)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            if (tableNames.isEmpty()) {
                Spacer(Modifier.height(32.dp))
                Text(
                    "No saved lists yet.\nSave results from the Results tab.",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    fontSize = 14.sp,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(tableNames, key = { it }) { name ->
                        val info = remember(name) { viewModel.getCheatTableInfo(name) }
                        SavedTableRow(
                            name = name,
                            info = info,
                            onLoad = {
                                val count = viewModel.loadCheatTable(name)
                                scope.launch {
                                    // Show a toast or snackbar? We don't have one here.
                                }
                                onBack()
                            },
                            onRename = {
                                renameTarget = name
                                renameValue = name
                                showRenameDialog = true
                            },
                            onDelete = {
                                if (viewModel.deleteCheatTable(name)) {
                                    tableNames = viewModel.listSavedTables()
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("Rename List", color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = renameValue,
                    onValueChange = { renameValue = it },
                    label = { Text("New name") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (viewModel.renameCheatTable(renameTarget, renameValue)) {
                        tableNames = viewModel.listSavedTables()
                    }
                    showRenameDialog = false
                }) { Text("Rename", color = Primary) }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text("Cancel", color = MaterialTheme.colorScheme.onSurface)
                }
            }
        )
    }

    if (showSaveCurrentDialog) {
        AlertDialog(
            onDismissRequest = { showSaveCurrentDialog = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("Save Current Results", color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = saveName,
                    onValueChange = { saveName = it },
                    label = { Text("List name") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (saveName.isNotBlank()) {
                        if (viewModel.saveCheatTable(saveName)) {
                            tableNames = viewModel.listSavedTables()
                        }
                        showSaveCurrentDialog = false
                        saveName = ""
                    }
                }) { Text("Save", color = Primary) }
            },
            dismissButton = {
                TextButton(onClick = { showSaveCurrentDialog = false }) {
                    Text("Cancel", color = MaterialTheme.colorScheme.onSurface)
                }
            }
        )
    }
}

@Composable
private fun SavedTableRow(
    name: String,
    info: CheatTableMeta?,
    onLoad: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    val dateStr = info?.let {
        SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()).format(Date(it.savedAt))
    } ?: ""
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceVariant)
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(name, color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(
                "${info?.entryCount ?: 0} addresses · $dateStr · ${info?.processName ?: ""}",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                fontSize = 11.sp
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            IconButton(onClick = onLoad, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.FolderOpen, contentDescription = "Load", tint = Accent, modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = onRename, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.DriveFileRenameOutline, contentDescription = "Rename", tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
            }
        }
    }
}
