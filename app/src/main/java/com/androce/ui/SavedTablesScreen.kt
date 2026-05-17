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
import com.androce.ui.components.AppCard
import com.androce.ui.components.AppCardWithIcon
import com.androce.ui.components.AppIconButton
import com.androce.ui.components.AppTextButton
import com.androce.ui.components.AppTextField
import com.androce.ui.components.EmptyState
import com.androce.ui.components.ScreenScaffold
import com.androce.ui.theme.Accent
import com.androce.ui.theme.Background
import com.androce.ui.theme.Error
import com.androce.ui.theme.OnBackground
import com.androce.ui.theme.OnSurface
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

    ScreenScaffold(
        title = "Saved Lists",
        onBack = onBack,
        actions = {
            AppTextButton(
                label = "Save Current",
                onClick = { showSaveCurrentDialog = true },
                icon = Icons.Default.Save
            )
        },
        containerColor = Background
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (tableNames.isEmpty()) {
                EmptyState(
                    icon = Icons.Default.FolderOpen,
                    title = "No saved lists",
                    subtitle = "Save results from the Results tab to see them here",
                    modifier = Modifier.fillMaxSize()
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
            title = { Text("Rename List", color = OnBackground, fontWeight = FontWeight.Bold) },
            text = {
                AppTextField(
                    value = renameValue,
                    onValueChange = { renameValue = it },
                    label = "New name"
                )
            },
            confirmButton = {
                AppTextButton(
                    label = "Rename",
                    onClick = {
                        if (viewModel.renameCheatTable(renameTarget, renameValue)) {
                            tableNames = viewModel.listSavedTables()
                        }
                        showRenameDialog = false
                    }
                )
            },
            dismissButton = {
                AppTextButton(
                    label = "Cancel",
                    onClick = { showRenameDialog = false }
                )
            }
        )
    }

    if (showSaveCurrentDialog) {
        AlertDialog(
            onDismissRequest = { showSaveCurrentDialog = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("Save Current Results", color = OnBackground, fontWeight = FontWeight.Bold) },
            text = {
                AppTextField(
                    value = saveName,
                    onValueChange = { saveName = it },
                    label = "List name"
                )
            },
            confirmButton = {
                AppTextButton(
                    label = "Save",
                    onClick = {
                        if (saveName.isNotBlank()) {
                            if (viewModel.saveCheatTable(saveName)) {
                                tableNames = viewModel.listSavedTables()
                            }
                            showSaveCurrentDialog = false
                            saveName = ""
                        }
                    }
                )
            },
            dismissButton = {
                AppTextButton(
                    label = "Cancel",
                    onClick = { showSaveCurrentDialog = false }
                )
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
    
    AppCardWithIcon(
        title = name,
        subtitle = "${info?.entryCount ?: 0} addresses · $dateStr · ${info?.processName ?: ""}",
        icon = Icons.Default.FolderOpen,
        iconTint = Accent,
        onClick = onLoad,
        actions = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                AppIconButton(
                    icon = Icons.Default.DriveFileRenameOutline,
                    onClick = onRename,
                    tint = OnSurface.copy(alpha = 0.7f),
                    contentDescription = "Rename"
                )
                AppIconButton(
                    icon = Icons.Default.Delete,
                    onClick = onDelete,
                    tint = Error,
                    contentDescription = "Delete"
                )
            }
        }
    )
}
