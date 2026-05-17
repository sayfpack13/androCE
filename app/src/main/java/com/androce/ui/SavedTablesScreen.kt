package com.androce.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.androce.viewmodel.ScanViewModel
import com.androce.viewmodel.CheatTableMeta
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedTablesScreen(
    viewModel: ScanViewModel,
    onBack: () -> Unit,
    onTableLoaded: (Int) -> Unit = {}
) {
    var tableNames by remember { mutableStateOf(viewModel.listSavedTables()) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf("") }
    var renameValue by remember { mutableStateOf("") }

    BackHandler(enabled = showRenameDialog) {
        showRenameDialog = false
    }

    ScreenScaffold(
        title = "Saved Lists",
        subtitle = "Tap a list to load into Results",
        onBack = onBack,
        containerColor = Background
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (tableNames.isEmpty()) {
                EmptyState(
                    icon = Icons.Default.FolderOpen,
                    title = "No saved lists",
                    subtitle = "On the Results tab, tap Save to store your scan,\nthen return here to load it",
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
                                onTableLoaded(count)
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
        subtitle = buildString {
            append("${info?.entryCount ?: 0} addresses")
            if (dateStr.isNotEmpty()) append(" · $dateStr")
            info?.processName?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
        },
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
