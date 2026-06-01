package com.androce.ui

import androidx.compose.animation.core.tween
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.OpenInNew
import com.androce.ui.components.SpinningLoader
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TextButton
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.androce.core.AppPrefs
import com.androce.model.ProcessInfo
import com.androce.ui.components.AppCard
import com.androce.ui.components.AppChip
import com.androce.ui.components.AppDimensions
import com.androce.ui.components.AppIconButton
import com.androce.ui.components.AppSearchField
import com.androce.ui.components.AppTextField
import com.androce.ui.components.EmptyState
import com.androce.ui.components.ScreenScaffold
import com.androce.ui.theme.Accent
import com.androce.ui.theme.AccentGreen
import com.androce.ui.theme.Background
import com.androce.ui.theme.OnBackground
import com.androce.ui.theme.OnSurface
import com.androce.ui.theme.Primary
import com.androce.ui.theme.PrimaryDim
import com.androce.ui.theme.Surface
import com.androce.ui.theme.SurfaceHigh
import com.androce.ui.theme.SurfaceVariant
import com.androce.viewmodel.ProcessListState
import com.androce.viewmodel.ProcessViewModel
import com.androce.viewmodel.SearchMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ProcessListScreen(
    viewModel: ProcessViewModel,
    selectedProcess: ProcessInfo?,
    onProcessSelected: (ProcessInfo?) -> Unit
) {
    val state by viewModel.state.collectAsState()
    val filtered by viewModel.filteredProcesses.collectAsState()
    val query by viewModel.searchQuery.collectAsState()
    val searchMode by viewModel.searchMode.collectAsState()
    val isLoading = state is ProcessListState.Loading
    val context = LocalContext.current
    val isVirtualMode = AppPrefs.operationMode == "virtual" ||
        (AppPrefs.operationMode == "auto" && com.topjohnwu.superuser.Shell.isAppGrantedRoot() != true)
    var virtualTab by remember { mutableStateOf(false) }
    val virtualInstalledApps by viewModel.virtualInstalled.collectAsState()
    val virtualAvailableApps by viewModel.virtualAvailable.collectAsState()
    val virtualLoading by viewModel.virtualCatalogLoading.collectAsState()
    val virtualSpaceUi by viewModel.virtualSpaceUi.collectAsState()
    val guestRuntime by viewModel.virtualGuestRuntime.collectAsState()
    LaunchedEffect(virtualTab) {
        viewModel.setVirtualTabActive(virtualTab, context)
    }

    // Load processes only if not already loaded
    val currentState = state
    val shouldLoad = currentState is ProcessListState.Idle ||
        (currentState is ProcessListState.Success && currentState.processes.isEmpty() && !isVirtualMode)
    if (shouldLoad) {
        LaunchedEffect(Unit) { viewModel.loadProcesses(context) }
    }

    // Auto-refresh process list + watch selected process running state in virtual mode
    LaunchedEffect(isVirtualMode) {
        if (isVirtualMode) viewModel.startAutoRefresh(context)
        else viewModel.stopAutoRefresh()
    }
    LaunchedEffect(selectedProcess?.packageName) {
        if (isVirtualMode) viewModel.watchProcess(context, selectedProcess?.packageName)
    }

    ScreenScaffold(
        title = "androCE",
        subtitle = if (selectedProcess == null) "Memory Scanner" else null,
        selectedProcess = selectedProcess,
        showProcessContext = selectedProcess != null,
        containerColor = Background,
        actions = {
            if (!isLoading) {
                AppIconButton(
                    icon = Icons.Default.Refresh,
                    onClick = { viewModel.loadProcesses(context) },
                    contentDescription = "Refresh"
                )
            }
        }
    ) {
        Column(Modifier.fillMaxSize()) {
            // Search Field
            AppSearchField(
                query = query,
                onQueryChange = { viewModel.searchQuery.value = it },
                placeholder = "Search app or PID...",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp)
            )

            // Tab bar: Processes | Virtual Space Clone
            TabRow(
                selectedTabIndex = if (virtualTab) 1 else 0,
                containerColor = Background,
                contentColor = OnBackground,
                indicator = { tabPositions ->
                    Box(
                        Modifier
                            .tabIndicatorOffset(tabPositions[if (virtualTab) 1 else 0])
                            .height(3.dp)
                            .fillMaxWidth()
                            .background(Primary)
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Tab(
                    selected = !virtualTab,
                    onClick = { virtualTab = false },
                    text = { Text("Processes", fontWeight = if (!virtualTab) FontWeight.Bold else FontWeight.Normal, color = if (!virtualTab) Primary else OnSurface) },
                    selectedContentColor = Primary,
                    unselectedContentColor = OnSurface
                )
                Tab(
                    selected = virtualTab,
                    onClick = { virtualTab = true },
                    text = { Text("Virtual Space Clone", fontWeight = if (virtualTab) FontWeight.Bold else FontWeight.Normal, color = if (virtualTab) Primary else OnSurface) },
                    selectedContentColor = Primary,
                    unselectedContentColor = OnSurface
                )
            }

            // Search mode chips (only on Processes tab)
            if (!virtualTab) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp)
                ) {
                    val modes = listOf(
                        SearchMode.ALL     to "All",
                        SearchMode.TITLE   to "App Title",
                        SearchMode.PACKAGE to "Package"
                    )
                    items(modes.size) { i ->
                        val (mode, label) = modes[i]
                        AppChip(
                            label = label,
                            selected = searchMode == mode,
                            onClick = { viewModel.searchMode.value = mode }
                        )
                    }
                }
            }

            if (virtualTab) {
                // --- Virtual Space Tab ---
                // Apply search filter to virtual apps
                val q = query.trim().lowercase()
                val filteredInstalled = if (q.isBlank()) virtualInstalledApps
                    else virtualInstalledApps.filter { it.appName.lowercase().contains(q) || it.packageName.lowercase().contains(q) }
                val filteredAvailable = if (q.isBlank()) virtualAvailableApps
                    else virtualAvailableApps.filter { it.second.lowercase().contains(q) || it.first.lowercase().contains(q) }

                if (virtualLoading) {
                    LoadingView()
                } else {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // Installed in virtual space
                        if (filteredInstalled.isNotEmpty()) {
                            item {
                                Text("Installed", color = Primary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp))
                            }
                            items(filteredInstalled, key = { "inst_${it.packageName}" }) { app ->
                                val liveProc = (state as? ProcessListState.Success)
                                    ?.processes
                                    ?.firstOrNull { it.packageName == app.packageName && it.pid > 0 }
                                val runtimeRunning = guestRuntime.isRunning &&
                                    guestRuntime.packageName == app.packageName
                                val isRunning = liveProc != null || runtimeRunning
                                val runningPid = liveProc?.pid?.takeIf { it > 0 }
                                    ?: guestRuntime.pid.takeIf { runtimeRunning && it > 0 }
                                VirtualAppRow(
                                    appName = app.appName,
                                    packageName = app.packageName,
                                    isInstalled = true,
                                    isRunning = isRunning,
                                    runningPid = runningPid,
                                    isLaunching = virtualSpaceUi.launchingPackage == app.packageName,
                                    isSelected = selectedProcess?.packageName == app.packageName,
                                    onToggleRun = {
                                        if (isRunning) {
                                            viewModel.stopVirtualGuest(context, app.packageName)
                                            if (selectedProcess?.packageName == app.packageName) {
                                                onProcessSelected(null)
                                            }
                                        } else {
                                            viewModel.launchVirtualGuest(context, app.packageName, app.appName) {
                                                onProcessSelected(it)
                                            }
                                        }
                                    },
                                    onOpenUi = if (isRunning) {
                                        { viewModel.openVirtualGuestUi(context, app.packageName) }
                                    } else {
                                        null
                                    },
                                    onUninstall = {
                                        viewModel.uninstallVirtualApp(context, app.packageName, app.appName)
                                    }
                                )
                            }
                        }
                        // Available to install
                        if (filteredAvailable.isNotEmpty()) {
                            item {
                                Text("Available", color = OnSurface, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp))
                            }
                            items(filteredAvailable, key = { "avail_${it.first}" }) { (pkg, label) ->
                                VirtualAppRow(
                                    appName = label,
                                    packageName = pkg,
                                    isInstalled = false,
                                    isInstalling = virtualSpaceUi.installingPackage == pkg,
                                    onInstall = { viewModel.installVirtualApp(context, pkg) }
                                )
                            }
                        }
                        if (filteredInstalled.isEmpty() && filteredAvailable.isEmpty()) {
                            item {
                                EmptyState(
                                    icon = Icons.Default.Memory,
                                    title = "No apps found",
                                    subtitle = "No launchable apps detected on this device",
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    }
                }
            } else when (state) {
                is ProcessListState.Error -> ErrorView((state as ProcessListState.Error).message)
                else -> {
                    PullToRefreshBox(
                        isRefreshing = isLoading,
                        onRefresh = { viewModel.loadProcesses(context) },
                        modifier = Modifier.fillMaxSize()
                    ) {
                        if (isLoading && filtered.isEmpty()) {
                            LoadingView()
                        } else if (filtered.isEmpty() && query.isNotBlank()) {
                            EmptyState(
                                icon = Icons.Default.Search,
                                title = "No matches found",
                                subtitle = "No apps match \"$query\"",
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            LazyColumn(
                                Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                if (filtered.isNotEmpty()) {
                                    item {
                                        Text(
                                            "${filtered.size} app${if (filtered.size != 1) "s" else ""}",
                                            color = OnSurface,
                                            fontSize = 11.sp,
                                            modifier = Modifier
                                                .padding(horizontal = 4.dp, vertical = 2.dp)
                                                .background(Background)
                                        )
                                    }
                                }
                                items(
                                    items = filtered,
                                    key = { "${it.packageName}_${it.pid}" }
                                ) { process ->
                                    val isSelected = if (process.isVirtual)
                                        selectedProcess?.packageName == process.packageName
                                    else
                                        selectedProcess?.pid == process.pid
                                    val isLiveGuest = process.isVirtual && guestRuntime.isRunning &&
                                        process.packageName == guestRuntime.packageName
                                    ProcessRow(
                                        process = process,
                                        isSelected = isSelected,
                                        isLiveGuest = isLiveGuest,
                                        onClick = { onProcessSelected(process) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

}

@Composable
private fun VirtualAppRow(
    appName: String,
    packageName: String,
    isInstalled: Boolean,
    isRunning: Boolean = false,
    runningPid: Int? = null,
    isLaunching: Boolean = false,
    isInstalling: Boolean = false,
    isSelected: Boolean = false,
    onInstall: (() -> Unit)? = null,
    onToggleRun: (() -> Unit)? = null,
    onOpenUi: (() -> Unit)? = null,
    onUninstall: (() -> Unit)? = null
) {
    val iconPainter = rememberAppIconPainter(packageName)
    var showCloneConfirm by remember { mutableStateOf(false) }
    var showUninstallConfirm by remember { mutableStateOf(false) }

    if (showCloneConfirm) {
        AlertDialog(
            onDismissRequest = { showCloneConfirm = false },
            title = { Text("Clone App") },
            text = { Text("Clone \"$appName\" into virtual space?\nThis copies the APK into the sandbox.") },
            confirmButton = {
                TextButton(onClick = { showCloneConfirm = false; onInstall?.invoke() }) {
                    Text("Clone", color = Primary)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCloneConfirm = false }) {
                    Text("Cancel")
                }
            },
            containerColor = SurfaceVariant
        )
    }

    if (showUninstallConfirm) {
        AlertDialog(
            onDismissRequest = { showUninstallConfirm = false },
            title = { Text("Remove Clone") },
            text = { Text("Remove \"$appName\" from virtual space?") },
            confirmButton = {
                TextButton(onClick = { showUninstallConfirm = false; onUninstall?.invoke() }) {
                    Text("Remove", color = androidx.compose.ui.graphics.Color(0xFFEF5350))
                }
            },
            dismissButton = {
                TextButton(onClick = { showUninstallConfirm = false }) {
                    Text("Cancel")
                }
            },
            containerColor = SurfaceVariant
        )
    }

    val rowBorderColor = when {
        isRunning -> AccentGreen.copy(alpha = 0.7f)
        isSelected -> Primary
        isInstalled -> Primary.copy(alpha = 0.4f)
        else -> SurfaceHigh.copy(alpha = 0.5f)
    }
    val rowBackground = when {
        isRunning -> AccentGreen.copy(alpha = 0.12f)
        isSelected -> Primary.copy(alpha = 0.15f)
        isInstalled -> Primary.copy(alpha = 0.08f)
        else -> SurfaceVariant
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 68.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(rowBackground)
            .border(
                width = if (isRunning || isSelected) 2.dp else 1.dp,
                color = rowBorderColor,
                shape = RoundedCornerShape(14.dp)
            )
            .clickable(enabled = !isLaunching && !isInstalling) {
                when {
                    !isInstalled -> showCloneConfirm = true
                    isRunning -> onOpenUi?.invoke()
                    else -> onToggleRun?.invoke()
                }
            }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (iconPainter != null) {
            Image(painter = iconPainter, contentDescription = null, modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)))
        } else {
            Box(modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp))
                .background(Brush.linearGradient(listOf(PrimaryDim.copy(alpha = 0.4f), Accent.copy(alpha = 0.15f)))),
                contentAlignment = Alignment.Center) {
                Text(text = appName.take(1).uppercase(), color = Primary, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(text = appName, color = OnBackground, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val statusLine = when {
                isRunning && runningPid != null && runningPid > 0 -> "Running in virtual space · PID $runningPid"
                isRunning -> "Running in virtual space"
                isLaunching -> "Starting…"
                isInstalling -> "Cloning…"
                else -> packageName
            }
            Text(
                text = statusLine,
                color = if (isRunning) AccentGreen else OnSurface,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        when {
            isInstalling || isLaunching -> {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp, color = Primary)
            }
            isInstalled -> {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (isRunning) {
                        IconButton(
                            onClick = { onOpenUi?.invoke() },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                Icons.Default.OpenInNew,
                                contentDescription = "Open app",
                                tint = Primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    IconButton(
                        onClick = { onToggleRun?.invoke() },
                        modifier = Modifier.size(36.dp)
                    ) {
                        if (isRunning) {
                            Icon(
                                Icons.Default.Stop,
                                contentDescription = "Stop",
                                tint = androidx.compose.ui.graphics.Color(0xFFEF5350),
                                modifier = Modifier.size(20.dp)
                            )
                        } else {
                            Icon(
                                Icons.Default.PlayArrow,
                                contentDescription = "Start",
                                tint = AccentGreen,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    IconButton(onClick = { showUninstallConfirm = true }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Delete, contentDescription = "Uninstall", tint = OnSurface.copy(alpha = 0.5f), modifier = Modifier.size(18.dp))
                    }
                }
            }
            else -> {
                Icon(Icons.Default.Add, contentDescription = "Clone", tint = Primary, modifier = Modifier.size(22.dp))
            }
        }
    }
}

@Composable
private fun LoadingView() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                SpinningLoader(
                    size = 64.dp,
                    color = Primary,
                    strokeWidth = 3.dp
                )
                SpinningLoader(
                    size = 48.dp,
                    color = Accent.copy(alpha = 0.5f),
                    strokeWidth = 2.dp
                )
                Icon(
                    Icons.Default.Memory,
                    contentDescription = null,
                    tint = Accent,
                    modifier = Modifier.size(22.dp)
                )
            }
            Text(
                "Scanning processes…",
                color = OnSurface,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
private fun ErrorView(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .padding(24.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(SurfaceVariant)
                .padding(24.dp)
        ) {
            Text("⚠", fontSize = 32.sp)
            Spacer(Modifier.height(8.dp))
            Text("Failed to load processes", color = OnBackground, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(message, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
        }
    }
}

@Composable
private fun rememberAppIconPainter(packageName: String): Painter? {
    val context = LocalContext.current
    var painter by remember(packageName) { mutableStateOf<Painter?>(null) }
    LaunchedEffect(packageName) {
        painter = withContext(Dispatchers.IO) {
            try {
                val drawable = context.packageManager.getApplicationIcon(packageName)
                BitmapPainter(drawable.toBitmap(96, 96).asImageBitmap())
            } catch (_: Exception) {
                null
            }
        }
    }
    return painter
}

@Composable
private fun ProcessRow(
    process: ProcessInfo,
    isSelected: Boolean,
    isLiveGuest: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Cache expensive calculations
    val iconPainter = rememberAppIconPainter(process.packageName)
    val displayName = remember(process.appName, process.name) {
        process.appName ?: process.name
    }
    val subtitle = remember(process.appName, process.name, process.packageName) {
        if (process.appName != null) process.name else process.packageName
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(68.dp) // Fixed height for better recycling
            .clip(RoundedCornerShape(14.dp))
            .background(
                when {
                    isLiveGuest -> AccentGreen.copy(alpha = 0.12f)
                    isSelected -> Primary.copy(alpha = 0.15f)
                    else -> SurfaceVariant
                }
            )
            .border(
                width = if (isSelected || isLiveGuest) 2.dp else 1.dp,
                color = when {
                    isLiveGuest -> AccentGreen.copy(alpha = 0.7f)
                    isSelected -> Primary
                    else -> SurfaceHigh.copy(alpha = 0.5f)
                },
                shape = RoundedCornerShape(14.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (iconPainter != null) {
            Image(
                painter = iconPainter,
                contentDescription = null,
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(10.dp))
            )
        } else {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(PrimaryDim.copy(alpha = 0.4f), Accent.copy(alpha = 0.15f))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = process.name.take(1).uppercase(),
                    color = Primary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = displayName,
                color = if (isSelected) Primary else OnBackground,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (isLiveGuest) "In-process · PID ${process.pid}" else subtitle,
                color = when {
                    isLiveGuest -> AccentGreen
                    isSelected -> Accent
                    else -> OnSurface
                },
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Selected indicator or PID badge
        if (isSelected) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Primary)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = "Selected",
                        tint = Color.White,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "${process.pid}",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(SurfaceHigh)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "${process.pid}",
                    color = Accent,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

