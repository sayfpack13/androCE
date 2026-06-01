package com.androce.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.InstallDesktop
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.androce.core.AppLogger
import com.androce.core.AppPrefs
import com.androce.core.DependencyInstaller
import com.androce.core.FridaSpeedHack
import com.androce.core.MemoryReader
import com.androce.ui.components.AppButton
import com.androce.ui.components.AppCard
import com.androce.ui.components.AppChip
import com.androce.ui.components.AppIconButton
import com.androce.ui.components.AppTextButton
import com.androce.ui.components.AppTextField
import com.androce.ui.components.ButtonVariant
import com.androce.ui.components.InfoBanner
import com.androce.ui.components.ScreenScaffold
import com.androce.ui.components.WarningBanner
import com.androce.ui.theme.AccentGreen
import com.androce.ui.theme.Background
import com.androce.ui.theme.Error
import com.androce.ui.theme.OnBackground
import com.androce.ui.theme.OnSurface
import com.androce.ui.theme.Primary
import com.androce.ui.theme.Surface
import com.androce.ui.theme.SurfaceVariant
import com.androce.ui.theme.Warning
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class RequirementStatus(
    val label: String,
    val detail: String,
    val level: RequirementLevel,
    val fixLabel: String? = null,
    val fixAction: (() -> Unit)? = null
)

enum class RequirementLevel { OK, WARN, FAIL }

private data class RequirementSnapshot(
    val label: String,
    val detail: String,
    val level: RequirementLevel,
    val fixLabel: String? = null,
    val fixCommand: String? = null
)

private object RequirementsCache {
    var snapshots: List<RequirementSnapshot> = emptyList()
    var checking: Boolean = false
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // --- State declarations (must be before functions that capture them) ---
    var requirements by remember { mutableStateOf<List<RequirementStatus>>(emptyList()) }
    var checking by remember { mutableStateOf(RequirementsCache.checking) }
    var showPythonInstallDialog by remember { mutableStateOf(false) }
    var showFridaInstallDialog by remember { mutableStateOf(false) }
    var showSwapDialog by remember { mutableStateOf(false) }
    var pythonInstallResult by remember { mutableStateOf<DependencyInstaller.InstallResult?>(null) }
    var swapOptions by remember { mutableStateOf<List<DependencyInstaller.SwapDisableOption>>(emptyList()) }
    var swapInfo by remember { mutableStateOf<DependencyInstaller.SwapInfo?>(null) }

    // --- Settings state ---
    var autoRefreshMs by remember { mutableLongStateOf(AppPrefs.autoRefreshIntervalMs) }
    var maxResults by remember { mutableIntStateOf(AppPrefs.maxResults) }
    var maxResultsText by remember { mutableStateOf(AppPrefs.maxResults.toString()) }
    var defaultFilter by remember { mutableStateOf(AppPrefs.defaultRegionFilter) }
    var scanEngine by remember { mutableStateOf(AppPrefs.scanEngine) }
    var freezeMs by remember { mutableLongStateOf(AppPrefs.freezeIntervalMs) }
    var defaultSpeed by remember { mutableFloatStateOf(AppPrefs.defaultSpeedMultiplier) }
    var autoEnableSpeed by remember { mutableStateOf(AppPrefs.autoEnableSpeedHack) }
    var floatingIconEnabled by remember { mutableStateOf(AppPrefs.floatingIconEnabled) }
    var showClearLogDialog by remember { mutableStateOf(false) }

    // --- Requirements state builder ---
    fun buildStatusList(snapshots: List<RequirementSnapshot>, onRecheck: () -> Unit): List<RequirementStatus> = snapshots.map { snap ->
        val fixAction = if (snap.fixLabel != null && snap.fixCommand != null) {
            when (snap.fixCommand) {
                "SHOW_SWAP_DIALOG" -> {
                    { showSwapDialog = true; Unit }
                }
                "SHOW_PYTHON_DIALOG" -> {
                    { showPythonInstallDialog = true; Unit }
                }
                "SHOW_FRIDA_DIALOG" -> {
                    { showFridaInstallDialog = true; Unit }
                }
                "START_FRIDA_SERVER" -> {
                    {
                        scope.launch(Dispatchers.IO) {
                            val ok = DependencyInstaller.startFridaServerShell()
                            if (!ok) {
                                DependencyInstaller.runFridaSetup { }
                            }
                            withContext(Dispatchers.Main) { onRecheck() }
                        }
                        Unit
                    }
                }
                "OPEN_TERMUX" -> {
                    {
                        val launched = Shell.cmd(
                            "am start -n com.termux/.app.TermuxActivity 2>/dev/null"
                        ).exec().isSuccess
                        if (!launched) {
                            Shell.cmd(
                                "am start -a android.intent.action.VIEW " +
                                    "-d 'market://details?id=com.termux' 2>/dev/null"
                            ).exec()
                        }
                        Unit
                    }
                }
                else -> {
                    {
                        scope.launch(Dispatchers.IO) {
                            Shell.cmd(snap.fixCommand).exec()
                            withContext(Dispatchers.Main) { onRecheck() }
                        }
                        Unit
                    }
                }
            }
        } else {
            null
        }
        RequirementStatus(
            label = snap.label,
            detail = snap.detail,
            level = snap.level,
            fixLabel = snap.fixLabel,
            fixAction = fixAction
        )
    }

    fun runChecks() {
        if (RequirementsCache.checking) return
        checking = true
        RequirementsCache.checking = true
        scope.launch(Dispatchers.IO) {
            try {
                val snapshots = mutableListOf<RequirementSnapshot>()

                val isVirtualMode = AppPrefs.operationMode == "virtual" ||
                    (AppPrefs.operationMode == "auto" && Shell.isAppGrantedRoot() != true)

                if (isVirtualMode) {
                    snapshots.add(
                        RequirementSnapshot(
                            "Operation mode",
                            "Virtual Space — clone GUI in sandbox",
                            RequirementLevel.OK
                        )
                    )
                    val root = Shell.isAppGrantedRoot()
                    snapshots.add(
                        if (root == true) {
                            RequirementSnapshot("Root access", "Required to scan guest PID via /proc/<pid>/mem", RequirementLevel.OK)
                        } else {
                            RequirementSnapshot(
                                "Root access",
                                "Required to scan cloned app process (launch clone still works)",
                                RequirementLevel.WARN
                            )
                        }
                    )
                    snapshots.add(
                        RequirementSnapshot(
                            "Memory access",
                            if (root == true) {
                                "Root: direct guest PID scan"
                            } else {
                                "Non-root: guest /proc/self bridge (no Magisk required)"
                            },
                            RequirementLevel.OK
                        )
                    )
                } else {
                    val root = Shell.isAppGrantedRoot()
                    snapshots.add(
                        if (root == true) RequirementSnapshot("Root access", "Root granted", RequirementLevel.OK)
                        else RequirementSnapshot("Root access", "Root not available — app cannot function", RequirementLevel.FAIL)
                    )

                // Enhanced swap detection
                val detectedSwapInfo = DependencyInstaller.detectSwapType()
                swapInfo = detectedSwapInfo
                swapOptions = DependencyInstaller.getSwapDisableCommands()

                val hasSwap = detectedSwapInfo.hasSwap
                val swapDetail = if (hasSwap) {
                    val typeStr = detectedSwapInfo.swapType.name
                    val sizeStr = "${detectedSwapInfo.totalSizeMB}MB"
                    val devicesStr = detectedSwapInfo.devices.map { it.filename.substringAfterLast("/") }.take(2).joinToString(", ")
                    "$typeStr active ($sizeStr): $devicesStr"
                } else "No swap detected"

                snapshots.add(
                    if (!hasSwap) RequirementSnapshot("Memory swap", swapDetail, RequirementLevel.OK)
                    else RequirementSnapshot(
                        "Memory swap",
                        swapDetail,
                        RequirementLevel.WARN,
                        fixLabel = "Manage",
                        fixCommand = "SHOW_SWAP_DIALOG" // Special marker to show dialog
                    )
                )

                val seResult = Shell.cmd("getenforce 2>/dev/null").exec()
                val seStatus = seResult.out.firstOrNull()?.trim() ?: "Unknown"
                snapshots.add(
                    when {
                        seStatus.equals("Permissive", ignoreCase = true) ->
                            RequirementSnapshot("SELinux", "Permissive", RequirementLevel.OK)
                        seStatus.equals("Disabled", ignoreCase = true) ->
                            RequirementSnapshot("SELinux", "Disabled", RequirementLevel.OK)
                        seStatus.equals("Enforcing", ignoreCase = true) ->
                            RequirementSnapshot(
                                "SELinux",
                                "Enforcing — some memory reads may fail",
                                RequirementLevel.WARN,
                                fixLabel = "Set Permissive",
                                fixCommand = "setenforce 0"
                            )
                        else ->
                            RequirementSnapshot("SELinux", seStatus, RequirementLevel.WARN)
                    }
                )

                snapshots.add(
                    if (MemoryReader.isNativeHelperReady)
                        RequirementSnapshot("Native helper", "Ready", RequirementLevel.OK)
                    else
                        RequirementSnapshot("Native helper", "Not available — scanning will not work", RequirementLevel.FAIL)
                )

                val pythonSources = DependencyInstaller.detectPythonSources()
                val pythonReady = MemoryReader.isPythonAvailable
                val pythonDetail = when {
                    pythonReady -> {
                        val sourceNames = pythonSources.take(2).joinToString(", ") { it.name }
                        when {
                            sourceNames.isNotEmpty() -> "Ready — $sourceNames"
                            else -> "Ready"
                        }
                    }
                    pythonSources.isNotEmpty() -> {
                        val sourceNames = pythonSources.take(2).joinToString(", ") { it.name }
                        "Found ($sourceNames) but not active — tap Install"
                    }
                    else -> "Required for memory scanning — tap Install"
                }

                snapshots.add(
                    if (pythonReady)
                        RequirementSnapshot("Python", pythonDetail, RequirementLevel.OK)
                    else
                        RequirementSnapshot(
                            "Python",
                            pythonDetail,
                            RequirementLevel.WARN,
                            fixLabel = "Install",
                            fixCommand = "SHOW_PYTHON_DIALOG"
                        )
                )

                val frida = FridaSpeedHack.probeStatus()
                val fridaDetail = buildString {
                    append(frida.summary)
                    frida.cliVersion?.let { append(" — ").append(it) }
                    frida.cliPath?.let { append("\n").append(it) }
                    frida.serverBinary?.takeIf { !frida.serverRunning }?.let {
                        append("\nServer binary: ").append(it)
                    }
                }
                snapshots.add(
                    when {
                        frida.ready -> RequirementSnapshot(
                            "Frida (speed hack)",
                            fridaDetail,
                            RequirementLevel.OK
                        )
                        frida.cliPath != null && !frida.serverRunning -> RequirementSnapshot(
                            "Frida (speed hack)",
                            fridaDetail,
                            RequirementLevel.WARN,
                            fixLabel = "Start server",
                            fixCommand = "START_FRIDA_SERVER"
                        )
                        frida.termuxInstalled -> RequirementSnapshot(
                            "Frida (speed hack)",
                            fridaDetail,
                            RequirementLevel.WARN,
                            fixLabel = "Install",
                            fixCommand = "SHOW_FRIDA_DIALOG"
                        )
                        else -> RequirementSnapshot(
                            "Frida (speed hack)",
                            fridaDetail,
                            RequirementLevel.WARN,
                            fixLabel = "Get Termux",
                            fixCommand = "OPEN_TERMUX"
                        )
                    }
                )

                snapshots.add(
                    if (frida.termuxInstalled) {
                        RequirementSnapshot("Termux app", "Installed", RequirementLevel.OK)
                    } else {
                        RequirementSnapshot(
                            "Termux app",
                            "Not installed — needed for Python and Frida speed hack fallback",
                            RequirementLevel.WARN,
                            fixLabel = "Get Termux",
                            fixCommand = "OPEN_TERMUX"
                        )
                    }
                )

                } // end root-mode else block

                withContext(Dispatchers.Main) {
                    RequirementsCache.snapshots = snapshots
                    requirements = buildStatusList(snapshots, ::runChecks)
                }
            } finally {
                withContext(Dispatchers.Main) {
                    RequirementsCache.checking = false
                    checking = false
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        if (RequirementsCache.snapshots.isNotEmpty()) {
            requirements = buildStatusList(RequirementsCache.snapshots, ::runChecks)
            checking = RequirementsCache.checking
        } else if (!RequirementsCache.checking) {
            runChecks()
        }
    }

    val isVirtualMode = AppPrefs.operationMode == "virtual" ||
        (AppPrefs.operationMode == "auto" && Shell.isAppGrantedRoot() != true)
    val fridaReady = isVirtualMode || requirements
        .firstOrNull { it.label == "Frida (speed hack)" }
        ?.level == RequirementLevel.OK

    // --- Tab state ---
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabTitles = listOf("Status", "Scan", "Speed", "General")

    ScreenScaffold(
        title = "Settings",
        containerColor = Background
    ) {
        Column(Modifier.fillMaxSize()) {
            SettingsTabRow(
                tabs = tabTitles,
                selectedIndex = selectedTab,
                onTabSelected = { selectedTab = it }
            )

            Spacer(Modifier.height(8.dp))

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                when (selectedTab) {
                    0 -> StatusTab(requirements, checking, ::runChecks)
                    1 -> ScanTab(
                        autoRefreshMs, maxResultsText, defaultFilter, scanEngine, freezeMs,
                        onAutoRefreshChanged = { autoRefreshMs = it; AppPrefs.autoRefreshIntervalMs = it },
                        onMaxResultsChanged = { text, value ->
                            maxResultsText = text
                            if (value != null) { maxResults = value; AppPrefs.maxResults = value }
                        },
                        onFilterChanged = { defaultFilter = it; AppPrefs.defaultRegionFilter = it },
                        onEngineChanged = { scanEngine = it; AppPrefs.scanEngine = it },
                        onFreezeChanged = { freezeMs = it; AppPrefs.freezeIntervalMs = it }
                    )
                    2 -> SpeedTab(
                        defaultSpeed, autoEnableSpeed, fridaReady,
                        onDefaultSpeedChanged = { defaultSpeed = it; AppPrefs.defaultSpeedMultiplier = it },
                        onAutoEnableChanged = { autoEnableSpeed = it; AppPrefs.autoEnableSpeedHack = it }
                    )
                    3 -> GeneralTab(
                        context, floatingIconEnabled, showClearLogDialog,
                        onShowClearLog = { showClearLogDialog = it },
                        onFloatingIconChanged = { enabled ->
                            floatingIconEnabled = enabled
                            AppPrefs.floatingIconEnabled = enabled
                            val activity = context as? com.androce.MainActivity
                            if (enabled) {
                                activity?.startFloatingIconServiceIfEnabled()
                            } else {
                                activity?.stopFloatingIconService()
                            }
                        }
                    )
                }
            }
        }
    }

    if (showClearLogDialog) {
        AlertDialog(
            onDismissRequest = { showClearLogDialog = false },
            title = { Text("Clear logs?", color = OnBackground) },
            text = { Text("This will delete all saved log data.", color = OnBackground.copy(alpha = 0.7f)) },
            confirmButton = {
                AppTextButton(
                    label = "Clear",
                    onClick = {
                        AppLogger.clearLog()
                        showClearLogDialog = false
                        Toast.makeText(context, "Logs cleared", Toast.LENGTH_SHORT).show()
                    },
                    color = Error
                )
            },
            dismissButton = {
                AppTextButton(
                    label = "Cancel",
                    onClick = { showClearLogDialog = false }
                )
            },
            containerColor = Surface,
            shape = RoundedCornerShape(16.dp)
        )
    }


    // Swap Management Dialog
    if (showSwapDialog) {
        SwapManagementDialog(
            swapOptions = swapOptions,
            swapInfo = swapInfo,
            onDismiss = { showSwapDialog = false },
            onActionComplete = {
                scope.launch(Dispatchers.IO) {
                    withContext(Dispatchers.Main) { runChecks() }
                }
            }
        )
    }

    // Python Installation Dialog
    if (showPythonInstallDialog) {
        PythonInstallDialog(
            onDismiss = { showPythonInstallDialog = false },
            onInstallComplete = { result ->
                pythonInstallResult = result
                if (result.success) {
                    scope.launch(Dispatchers.IO) {
                        MemoryReader.refreshPythonStatus()
                        withContext(Dispatchers.Main) { runChecks() }
                    }
                }
            }
        )
    }

    if (showFridaInstallDialog) {
        FridaInstallDialog(
            onDismiss = { showFridaInstallDialog = false },
            onInstallComplete = { result ->
                if (result.success) {
                    scope.launch(Dispatchers.IO) {
                        withContext(Dispatchers.Main) { runChecks() }
                    }
                }
            }
        )
    }

    // Show Python install result toast
    pythonInstallResult?.let { result ->
        LaunchedEffect(result) {
            Toast.makeText(
                context,
                if (result.success) "Python installed successfully" else "Python installation failed",
                Toast.LENGTH_LONG
            ).show()
            pythonInstallResult = null
        }
    }
}

// ===== Tab content composables =====

@Composable
private fun SettingsTabRow(
    tabs: List<String>,
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            tabs.forEachIndexed { index, title ->
                val selected = selectedIndex == index
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable { onTabSelected(index) }
                        .padding(horizontal = 4.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = title,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        color = if (selected) Primary else OnBackground.copy(alpha = 0.5f)
                    )
                    Spacer(Modifier.height(6.dp))
                    Box(
                        Modifier
                            .height(3.dp)
                            .width(if (selected) 28.dp else 0.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(if (selected) Primary else Color.Transparent)
                    )
                }
            }
        }
        HorizontalDivider(color = SurfaceVariant, thickness = 0.5.dp)
    }
}

@Composable
private fun StatusTab(
    requirements: List<RequirementStatus>,
    checking: Boolean,
    onRecheck: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {

        // Requirements card
        SettingsCard {
            if (checking && requirements.isEmpty()) {
                Text("Checking...", color = OnBackground, fontSize = 14.sp, modifier = Modifier.padding(12.dp))
            } else {
                requirements.forEach { req -> RequirementRow(req) }
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onRecheck, enabled = !checking) {
                        Icon(Icons.Default.Refresh, contentDescription = null, tint = Primary, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Re-check", color = Primary, fontSize = 13.sp)
                    }
                }
            }
        }

        // Tips card
        SettingsCard {
            SectionLabel("Tips")
            Spacer(Modifier.height(8.dp))
            TipRow("Python engine finds more results — it reads fresh pages even when memory is swapped or compressed")
            TipRow("Tap 'Install' next to Python in Status to set up Python automatically via Termux")
            TipRow("Frida (speed hack): tap Install in Status — downloads and configures automatically (Termux required)")
            TipRow("On Android 15, Frida fallback is used when native library injection fails")
            TipRow("Tap 'Manage' next to Memory swap to see device-specific swap disable options")
            TipRow("Native C may miss values on devices with MemFusion/zRAM due to stale page cache")
            TipRow("Disable MemFusion/zRAM and reboot before scanning for best accuracy")
            TipRow("Set SELinux to Permissive for best results")
            TipRow("Use Heap/Stack/Anon filter for faster scans")
            TipRow("Narrow results with comparison scans (Increased/Decreased)")
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun ScanTab(
    autoRefreshMs: Long,
    maxResultsText: String,
    defaultFilter: String,
    scanEngine: String,
    freezeMs: Long,
    onAutoRefreshChanged: (Long) -> Unit,
    onMaxResultsChanged: (String, Int?) -> Unit,
    onFilterChanged: (String) -> Unit,
    onEngineChanged: (String) -> Unit,
    onFreezeChanged: (Long) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Scan settings
        SectionLabel("Scan")
        SettingsCard {
            SettingsDropdown(
                label = "Auto-refresh interval",
                options = listOf(0L to "Off", 500L to "500 ms", 1000L to "1 s", 2000L to "2 s", 5000L to "5 s"),
                selected = autoRefreshMs,
                onSelected = onAutoRefreshChanged
            )
            Spacer(Modifier.height(12.dp))
            Text("Max results", color = OnBackground, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = maxResultsText,
                onValueChange = { input ->
                    val v = input.toIntOrNull()?.takeIf { it in 1..5_000_000 }
                    onMaxResultsChanged(input, v)
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = OnBackground, fontFamily = FontFamily.Monospace),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Primary,
                    unfocusedBorderColor = SurfaceVariant,
                    cursorColor = Primary,
                    focusedTextColor = OnBackground,
                    unfocusedTextColor = OnBackground
                )
            )
            Spacer(Modifier.height(12.dp))
            SettingsDropdown(
                label = "Default region filter",
                options = listOf("all" to "All", "heap_stack_anon" to "Heap / Stack / Anon"),
                selected = defaultFilter,
                onSelected = onFilterChanged
            )
            Spacer(Modifier.height(12.dp))
            SettingsDropdown(
                label = "Scan engine",
                options = listOf(
                    "auto" to "Auto (Python if available)",
                    "python" to "Python (/proc/pid/mem)",
                    "native" to "Native C (/proc/pid/mem + fallback)"
                ),
                selected = scanEngine,
                onSelected = onEngineChanged
            )
            if (scanEngine == "python" && !MemoryReader.isPythonAvailable) {
                Spacer(Modifier.height(12.dp))
                WarningBanner(
                    title = "Python not ready",
                    message = "Install Python in Settings → Status before using the Python engine."
                )
            }
        }

        // Freeze settings
        SectionLabel("Freeze")
        SettingsCard {
            SettingsDropdown(
                label = "Freeze write interval",
                options = listOf(10L to "10 ms (aggressive)", 25L to "25 ms", 50L to "50 ms", 100L to "100 ms", 200L to "200 ms", 500L to "500 ms"),
                selected = freezeMs,
                onSelected = onFreezeChanged
            )
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SpeedTab(
    defaultSpeed: Float,
    autoEnableSpeed: Boolean,
    fridaReady: Boolean,
    onDefaultSpeedChanged: (Float) -> Unit,
    onAutoEnableChanged: (Boolean) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SectionLabel("Speed Hack")
        if (!fridaReady) {
            WarningBanner(
                title = "Frida fallback not ready",
                message = "Open Settings → Status and tap Install next to Frida (speed hack). " +
                    "Required on many Android 15 devices when native injection fails."
            )
            Spacer(Modifier.height(12.dp))
        }
        SettingsCard {
            Text("Default speed multiplier", color = OnBackground, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("0.1x", fontSize = 12.sp, color = OnSurface)
                Text(String.format("%.1fx", defaultSpeed), fontSize = 14.sp, color = Primary, fontWeight = FontWeight.Bold)
                Text("10x", fontSize = 12.sp, color = OnSurface)
            }
            
            Slider(
                value = defaultSpeed,
                onValueChange = onDefaultSpeedChanged,
                valueRange = 0.1f..10f,
                steps = 98,
                colors = SliderDefaults.colors(
                    thumbColor = Primary,
                    activeTrackColor = Primary,
                    inactiveTrackColor = SurfaceVariant
                )
            )
            
            Spacer(Modifier.height(12.dp))
            
            // Preset quick buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(0.5f, 1.0f, 2.0f, 5.0f).forEach { speed ->
                    TextButton(
                        onClick = { onDefaultSpeedChanged(speed) },
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                when {
                                    speed == 1.0f -> Primary.copy(alpha = 0.2f)
                                    speed < 1.0f -> Warning.copy(alpha = 0.2f)
                                    else -> AccentGreen.copy(alpha = 0.2f)
                                }
                            ),
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = when {
                                speed == 1.0f -> Primary
                                speed < 1.0f -> Warning
                                else -> AccentGreen
                            }
                        )
                    ) {
                        Text(String.format("%.1fx", speed), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            
            Spacer(Modifier.height(16.dp))
            
            // Auto-enable option
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Auto-enable speed hack", color = OnBackground, fontSize = 14.sp)
                    Text(
                        "Automatically enable speed hack when selecting a process",
                        color = OnSurface,
                        fontSize = 12.sp
                    )
                }
                Switch(
                    checked = autoEnableSpeed,
                    onCheckedChange = onAutoEnableChanged,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Primary,
                        checkedTrackColor = Primary.copy(alpha = 0.5f),
                        uncheckedThumbColor = OnSurface.copy(alpha = 0.5f),
                        uncheckedTrackColor = OnSurface.copy(alpha = 0.2f)
                    )
                )
            }
        }

        // Info card
        SettingsCard {
            SectionLabel("About Speed Hack")
            Spacer(Modifier.height(8.dp))
            Text(
                "The speed hack works by intercepting time-related system calls in the target process. This can speed up or slow down game timers, animations, and physics.",
                color = OnSurface,
                fontSize = 13.sp,
                lineHeight = 18.sp
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Note: Not all games are compatible. Some use alternative timing methods or have anti-cheat protection.",
                color = Warning,
                fontSize = 12.sp,
                lineHeight = 16.sp
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (fridaReady) {
                    "Frida fallback: ready (see Status tab)."
                } else {
                    "Frida fallback: not ready — check Settings → Status."
                },
                color = if (fridaReady) AccentGreen else OnSurface,
                fontSize = 12.sp,
                lineHeight = 16.sp
            )
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun GeneralTab(
    context: Context,
    floatingIconEnabled: Boolean,
    showClearLogDialog: Boolean,
    onShowClearLog: (Boolean) -> Unit,
    onFloatingIconChanged: (Boolean) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Logs
        SectionLabel("Logs")
        SettingsCard {
            val logPath = AppLogger.getLogPath()
            Text("Log file", color = OnBackground, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            Text(
                logPath,
                color = Primary,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(SurfaceVariant)
                    .clickable {
                        val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clip.setPrimaryClip(ClipData.newPlainText("log path", logPath))
                        Toast.makeText(context, "Path copied", Toast.LENGTH_SHORT).show()
                    }
                    .padding(10.dp)
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { onShowClearLog(true) },
                colors = ButtonDefaults.buttonColors(containerColor = Error.copy(alpha = 0.15f)),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Delete, contentDescription = null, tint = Error, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Clear logs", color = Error, fontWeight = FontWeight.Medium)
            }
        }

        // Floating Icon
        SectionLabel("Floating Icon")
        SettingsCard {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onFloatingIconChanged(!floatingIconEnabled) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Show floating icon", color = OnBackground, fontSize = 14.sp)
                    Text(
                        "Keep a draggable shortcut on screen while playing",
                        color = OnSurface,
                        fontSize = 12.sp
                    )
                }
                Switch(
                    checked = floatingIconEnabled,
                    onCheckedChange = onFloatingIconChanged,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Primary,
                        checkedTrackColor = Primary.copy(alpha = 0.5f),
                        uncheckedThumbColor = OnSurface.copy(alpha = 0.5f),
                        uncheckedTrackColor = OnSurface.copy(alpha = 0.2f)
                    )
                )
            }
        }

        // About
        SectionLabel("About")
        SettingsCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("androCE", color = OnBackground, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("Memory Scanner & Editor", color = Primary, fontSize = 12.sp)
                }
                Text("v1.0", color = OnBackground.copy(alpha = 0.5f), fontSize = 13.sp, fontFamily = FontFamily.Monospace)
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

// ===== Reusable components =====

@Composable
private fun SectionHeader(title: String) {
    Text(
        title.uppercase(),
        color = Primary,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.5.sp,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp)
    )
}

@Composable
private fun SectionLabel(title: String) {
    Text(
        title,
        color = OnBackground,
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 2.dp)
    )
}

@Composable
private fun TipRow(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            Icons.Default.Info,
            contentDescription = null,
            tint = Primary.copy(alpha = 0.6f),
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(text, color = OnBackground.copy(alpha = 0.7f), fontSize = 13.sp, lineHeight = 18.sp)
    }
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Surface)
            .padding(16.dp)
    ) {
        content()
    }
}

@Composable
private fun RequirementRow(req: RequirementStatus) {
    val (icon, tint) = when (req.level) {
        RequirementLevel.OK -> Icons.Default.CheckCircle to AccentGreen
        RequirementLevel.WARN -> Icons.Default.Warning to Warning
        RequirementLevel.FAIL -> Icons.Default.Error to Error
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(req.label, color = OnBackground, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(req.detail, color = tint, fontSize = 12.sp)
        }
        if (req.fixAction != null && req.fixLabel != null) {
            Spacer(Modifier.width(8.dp))
            TextButton(
                onClick = { req.fixAction.invoke() },
                colors = ButtonDefaults.textButtonColors(contentColor = Primary),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Primary.copy(alpha = 0.1f))
            ) {
                Text(req.fixLabel, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> SettingsDropdown(
    label: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelected: (T) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.first == selected }?.second ?: ""

    Text(label, color = OnBackground, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(4.dp))
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = OnBackground),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Primary,
                unfocusedBorderColor = SurfaceVariant,
                focusedTextColor = OnBackground,
                unfocusedTextColor = OnBackground
            )
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = Surface
        ) {
            options.forEach { (value, text) ->
                DropdownMenuItem(
                    text = { Text(text, color = if (value == selected) Primary else OnBackground) },
                    onClick = {
                        onSelected(value)
                        expanded = false
                    }
                )
            }
        }
    }
}

// ===== Dependency Installation Dialogs =====

@Composable
internal fun PythonInstallDialog(
    onDismiss: () -> Unit,
    onInstallComplete: (DependencyInstaller.InstallResult) -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var installing by remember { mutableStateOf(false) }
    var installLog by remember { mutableStateOf("") }
    var lastResult by remember { mutableStateOf<DependencyInstaller.InstallResult?>(null) }
    var elapsedSec by remember { mutableIntStateOf(0) }
    var autoStarted by remember { mutableStateOf(false) }
    val logScroll = rememberScrollState()
    val bodyScroll = rememberScrollState()

    fun appendLog(line: String) {
        installLog = if (installLog.isEmpty()) line else "$installLog\n$line"
    }

    fun runSetup() {
        if (installing) return
        installing = true
        lastResult = null
        installLog = ""
        elapsedSec = 0
        scope.launch {
            val result = DependencyInstaller.runPythonSetup { appendLog(it) }
            installing = false
            lastResult = result
            appendLog(if (result.success) "✓ ${result.message}" else "✗ ${result.message}")
            onInstallComplete(result)
            Toast.makeText(
                context,
                if (result.success) "Python ready" else "Setup needs attention",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    LaunchedEffect(Unit) {
        if (autoStarted) return@LaunchedEffect
        autoStarted = true
        scope.launch {
            if (DependencyInstaller.isPythonSetupComplete()) {
                appendLog("Python already installed — tap Re-check to verify")
                return@launch
            }
            runSetup()
        }
    }

    LaunchedEffect(installLog) {
        if (installLog.isNotEmpty()) {
            logScroll.scrollTo(logScroll.maxValue)
        }
    }

    LaunchedEffect(installing) {
        if (!installing) return@LaunchedEffect
        while (installing) {
            delay(1000)
            elapsedSec++
        }
    }

    AlertDialog(
        onDismissRequest = { if (!installing) onDismiss() },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.InstallDesktop,
                    contentDescription = null,
                    tint = Primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text("Python Setup", color = OnBackground)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(bodyScroll)
            ) {
                Text(
                    "Python improves scan accuracy on zRAM / MemFusion devices. Setup runs entirely in the background (Termux is not opened), then installs and verifies Python.",
                    color = OnSurface,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Full log on device: ${com.androce.core.SetupLogger.DEVICE_LOG_PATH}",
                    color = OnSurface,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.height(12.dp))

                Button(
                    onClick = { runSetup() },
                    enabled = !installing,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Primary)
                ) {
                    if (installing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            if (elapsedSec > 0) "Setting up... (${elapsedSec}s)" else "Setting up...",
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    } else {
                        Text(
                            if (lastResult?.success == true) "Re-check Python" else "Setup Python",
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text(
                    "Progress",
                    color = OnBackground,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = installLog.ifEmpty { "Starting setup..." },
                    color = if (lastResult?.success == true) AccentGreen else OnSurface,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 16.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 80.dp, max = 200.dp)
                        .verticalScroll(logScroll)
                        .clip(RoundedCornerShape(6.dp))
                        .background(SurfaceVariant.copy(alpha = 0.5f))
                        .padding(8.dp)
                )

                if (!installing && lastResult?.success != true) {
                    Spacer(Modifier.height(10.dp))
                    SettingsCard {
                        Text(
                            "If setup fails:\n" +
                            "1. Install Termux from F-Droid (com.termux)\n" +
                            "2. Grant root and allow internet (bootstrap downloads in background)\n" +
                            "3. Tap Setup Python again — Termux app does not need to be opened",
                            color = OnBackground.copy(alpha = 0.8f),
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 18.sp
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !installing) {
                Text("Close", color = OnBackground)
            }
        },
        containerColor = Surface,
        shape = RoundedCornerShape(16.dp)
    )
}

@Composable
internal fun FridaInstallDialog(
    onDismiss: () -> Unit,
    onInstallComplete: (DependencyInstaller.InstallResult) -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var installing by remember { mutableStateOf(false) }
    var installLog by remember { mutableStateOf("") }
    var lastResult by remember { mutableStateOf<DependencyInstaller.InstallResult?>(null) }
    var elapsedSec by remember { mutableIntStateOf(0) }
    var autoStarted by remember { mutableStateOf(false) }
    val logScroll = rememberScrollState()
    val bodyScroll = rememberScrollState()

    fun appendLog(line: String) {
        installLog = if (installLog.isEmpty()) line else "$installLog\n$line"
    }

    fun runSetup() {
        if (installing) return
        installing = true
        lastResult = null
        installLog = ""
        elapsedSec = 0
        scope.launch {
            val result = DependencyInstaller.runFridaSetup { appendLog(it) }
            installing = false
            lastResult = result
            appendLog(if (result.success) "✓ ${result.message}" else "✗ ${result.message}")
            onInstallComplete(result)
            Toast.makeText(
                context,
                if (result.success) "Frida ready" else "Frida setup needs attention",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    LaunchedEffect(Unit) {
        if (autoStarted) return@LaunchedEffect
        autoStarted = true
        scope.launch {
            if (DependencyInstaller.isFridaSetupComplete()) {
                appendLog("Frida already installed — tap Install Frida to re-check only")
                return@launch
            }
            runSetup()
        }
    }

    LaunchedEffect(installLog) {
        if (installLog.isNotEmpty()) {
            logScroll.scrollTo(logScroll.maxValue)
        }
    }

    LaunchedEffect(installing) {
        if (!installing) return@LaunchedEffect
        while (installing) {
            delay(1000)
            elapsedSec++
        }
    }

    AlertDialog(
        onDismissRequest = { if (!installing) onDismiss() },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.InstallDesktop,
                    contentDescription = null,
                    tint = Primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text("Frida Setup", color = OnBackground)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(bodyScroll)
            ) {
                Text(
                    "Frida is used when native speed-hack injection fails (common on Android 15). " +
                        "Setup downloads Frida from Termux packages into a root-accessible location and starts frida-server — " +
                        "you do not need to open Termux or run commands manually.",
                    color = OnSurface,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Full log on device: ${com.androce.core.SetupLogger.DEVICE_LOG_PATH}",
                    color = OnSurface,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.height(12.dp))

                Button(
                    onClick = { runSetup() },
                    enabled = !installing,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Primary)
                ) {
                    if (installing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            if (elapsedSec > 0) "Setting up... (${elapsedSec}s)" else "Setting up...",
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    } else {
                        Text(
                            if (lastResult?.success == true) "Re-check Frida" else "Install Frida",
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text(
                    "Progress",
                    color = OnBackground,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = installLog.ifEmpty { "Starting setup..." },
                    color = if (lastResult?.success == true) AccentGreen else OnSurface,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 16.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 80.dp, max = 200.dp)
                        .verticalScroll(logScroll)
                        .clip(RoundedCornerShape(6.dp))
                        .background(SurfaceVariant.copy(alpha = 0.5f))
                        .padding(8.dp)
                )

                if (!installing && lastResult?.success != true) {
                    Spacer(Modifier.height(10.dp))
                    SettingsCard {
                        Text(
                            "If setup fails:\n" +
                            "1. Install Termux from F-Droid (com.termux)\n" +
                            "2. Grant root and allow internet\n" +
                            "3. Tap Install Frida again — Termux app does not need to be opened",
                            color = OnBackground.copy(alpha = 0.8f),
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 18.sp
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !installing) {
                Text("Close", color = OnBackground)
            }
        },
        containerColor = Surface,
        shape = RoundedCornerShape(16.dp)
    )
}

@Composable
private fun SwapManagementDialog(
    swapOptions: List<DependencyInstaller.SwapDisableOption>,
    swapInfo: DependencyInstaller.SwapInfo?,
    onDismiss: () -> Unit,
    onActionComplete: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var processing by remember { mutableStateOf(false) }
    var processingOption by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!processing) onDismiss() },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Memory,
                    contentDescription = null,
                    tint = Warning,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text("Memory Swap Management", color = OnBackground)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                swapInfo?.let { info ->
                    SettingsCard {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(
                                    "Current Status",
                                    color = OnBackground,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "Type: ${info.swapType.name}",
                                    color = OnSurface,
                                    fontSize = 12.sp
                                )
                                Text(
                                    "Total: ${info.totalSizeMB}MB",
                                    color = OnSurface,
                                    fontSize = 12.sp
                                )
                                Text(
                                    "Devices: ${info.devices.size}",
                                    color = OnSurface,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }

                Text(
                    "Swap/Compression reduces scan accuracy because memory pages may be compressed or moved. Select an option to disable:",
                    color = OnSurface,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
                Spacer(Modifier.height(12.dp))

                if (swapOptions.isEmpty()) {
                    Text(
                        "No swap management options detected for this device.",
                        color = Warning,
                        fontSize = 13.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Manual command:\nswapoff -a",
                        color = OnBackground.copy(alpha = 0.8f),
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                } else {
                    swapOptions.forEach { option ->
                        val isProcessing = processing && processingOption == option.name
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(enabled = !processing) {
                                    processing = true
                                    processingOption = option.name
                                    scope.launch(Dispatchers.IO) {
                                        val result = Shell.cmd(option.command).exec()
                                        withContext(Dispatchers.Main) {
                                            processing = false
                                            processingOption = null
                                            Toast.makeText(
                                                context,
                                                if (result.isSuccess) "${option.name} applied" else "Command failed",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                            if (result.isSuccess && option.requiresReboot) {
                                                Toast.makeText(
                                                    context,
                                                    "Reboot required for changes to persist",
                                                    Toast.LENGTH_LONG
                                                ).show()
                                            }
                                            onActionComplete()
                                        }
                                    }
                                }
                                .background(SurfaceVariant.copy(alpha = 0.3f))
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (isProcessing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = Primary,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(
                                    Icons.Default.Memory,
                                    contentDescription = null,
                                    tint = if (option.isPersistent) AccentGreen else Primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        option.name,
                                        color = OnBackground,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                    if (option.isPersistent) {
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            "PERSISTENT",
                                            color = AccentGreen,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(AccentGreen.copy(alpha = 0.2f))
                                                .padding(horizontal = 4.dp, vertical = 2.dp)
                                        )
                                    }
                                    if (option.requiresReboot) {
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            "REBOOT",
                                            color = Warning,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(Warning.copy(alpha = 0.2f))
                                                .padding(horizontal = 4.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                Text(
                                    option.description,
                                    color = OnSurface,
                                    fontSize = 12.sp,
                                    lineHeight = 16.sp
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    "Note: Some changes require reboot to persist. Non-persistent changes will be lost after reboot.",
                    color = OnSurface.copy(alpha = 0.7f),
                    fontSize = 11.sp,
                    lineHeight = 14.sp
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !processing) {
                Text("Close", color = OnBackground)
            }
        },
        containerColor = Surface,
        shape = RoundedCornerShape(16.dp)
    )
}
