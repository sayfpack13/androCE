package com.androce.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.androce.core.AppLogger
import com.androce.core.AppPrefs
import com.androce.core.MemoryReader
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

    // --- Requirements state ---
    fun buildStatusList(snapshots: List<RequirementSnapshot>, onRecheck: () -> Unit): List<RequirementStatus> = snapshots.map { snap ->
        val fixAction = if (snap.fixLabel != null && snap.fixCommand != null) {
            {
                scope.launch(Dispatchers.IO) {
                    Shell.cmd(snap.fixCommand).exec()
                    withContext(Dispatchers.Main) { onRecheck() }
                }
                Unit
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

    var requirements by remember { mutableStateOf(buildStatusList(RequirementsCache.snapshots) {}) }
    var checking by remember { mutableStateOf(RequirementsCache.checking) }

    fun runChecks() {
        if (RequirementsCache.checking) return
        checking = true
        RequirementsCache.checking = true
        scope.launch(Dispatchers.IO) {
            try {
                val snapshots = mutableListOf<RequirementSnapshot>()

                val root = Shell.isAppGrantedRoot()
                snapshots.add(
                    if (root == true) RequirementSnapshot("Root access", "Root granted", RequirementLevel.OK)
                    else RequirementSnapshot("Root access", "Root not available — app cannot function", RequirementLevel.FAIL)
                )

                val swapResult = Shell.cmd("cat /proc/swaps 2>/dev/null").exec()
                val hasSwap = swapResult.out.any { line ->
                    val trimmed = line.trim()
                    trimmed.isNotEmpty() && !trimmed.startsWith("Filename")
                }
                snapshots.add(
                    if (!hasSwap) RequirementSnapshot("Memory swap", "No swap detected", RequirementLevel.OK)
                    else RequirementSnapshot(
                        "Memory swap",
                        "Swap/zRAM active — may cause stale reads",
                        RequirementLevel.WARN,
                        fixLabel = "Disable",
                        fixCommand = "swapoff -a"
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

                snapshots.add(
                    if (MemoryReader.isPythonAvailable)
                        RequirementSnapshot("Python", "Available", RequirementLevel.OK)
                    else
                        RequirementSnapshot("Python", "Not found — using native only", RequirementLevel.WARN)
                )

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

    if (RequirementsCache.snapshots.isEmpty() && !checking) {
        runChecks()
    }

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

    // --- Tab state ---
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabTitles = listOf("Status", "Scan", "Speed", "General")

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Settings, contentDescription = null, tint = Primary, modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(10.dp))
                            Text("Settings", color = OnBackground, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Background)
                )
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = Background,
                    contentColor = Primary,
                    indicator = { tabPositions ->
                        if (selectedTab < tabPositions.size) {
                            TabRowDefaults.SecondaryIndicator(
                                Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                                color = Primary,
                                height = 3.dp
                            )
                        }
                    },
                    divider = {
                        HorizontalDivider(color = SurfaceVariant, thickness = 0.5.dp)
                    }
                ) {
                    tabTitles.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = {
                                Text(
                                    title,
                                    fontSize = 13.sp,
                                    fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Medium,
                                    color = if (selectedTab == index) Primary else OnBackground.copy(alpha = 0.5f)
                                )
                            }
                        )
                    }
                }
            }
        },
        containerColor = Background
    ) { padding ->
        when (selectedTab) {
            0 -> StatusTab(padding, requirements, checking, ::runChecks)
            1 -> ScanTab(
                padding, autoRefreshMs, maxResultsText, defaultFilter, scanEngine, freezeMs,
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
                padding, defaultSpeed, autoEnableSpeed,
                onDefaultSpeedChanged = { defaultSpeed = it; AppPrefs.defaultSpeedMultiplier = it },
                onAutoEnableChanged = { autoEnableSpeed = it; AppPrefs.autoEnableSpeedHack = it }
            )
            3 -> GeneralTab(
                padding, context, floatingIconEnabled, showClearLogDialog,
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

    if (showClearLogDialog) {
        AlertDialog(
            onDismissRequest = { showClearLogDialog = false },
            title = { Text("Clear logs?", color = OnBackground) },
            text = { Text("This will delete all saved log data.", color = OnBackground.copy(alpha = 0.7f)) },
            confirmButton = {
                TextButton(onClick = {
                    AppLogger.clearLog()
                    showClearLogDialog = false
                    Toast.makeText(context, "Logs cleared", Toast.LENGTH_SHORT).show()
                }) {
                    Text("Clear", color = Error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearLogDialog = false }) {
                    Text("Cancel", color = OnBackground)
                }
            },
            containerColor = Surface,
            shape = RoundedCornerShape(16.dp)
        )
    }
}

// ===== Tab content composables =====

@Composable
private fun StatusTab(
    padding: androidx.compose.foundation.layout.PaddingValues,
    requirements: List<RequirementStatus>,
    checking: Boolean,
    onRecheck: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(Modifier.height(4.dp))

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
            TipRow("Native C may miss values on devices with MemFusion/zRAM due to stale page cache")
            TipRow("Disable MemFusion/zRAM and reboot before scanning")
            TipRow("Set SELinux to Permissive for best results")
            TipRow("Use Heap/Stack/Anon filter for faster scans")
            TipRow("Narrow results with comparison scans (Increased/Decreased)")
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun ScanTab(
    padding: androidx.compose.foundation.layout.PaddingValues,
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
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(Modifier.height(4.dp))

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
    padding: androidx.compose.foundation.layout.PaddingValues,
    defaultSpeed: Float,
    autoEnableSpeed: Boolean,
    onDefaultSpeedChanged: (Float) -> Unit,
    onAutoEnableChanged: (Boolean) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(Modifier.height(4.dp))

        // Speed Hack settings
        SectionLabel("Speed Hack")
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
                    .clickable { onAutoEnableChanged(!autoEnableSpeed) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Auto-enable on process select", color = OnBackground, fontSize = 14.sp)
                    Text(
                        "Automatically activate speed hack when selecting a process",
                        color = OnSurface,
                        fontSize = 12.sp
                    )
                }
                Icon(
                    imageVector = if (autoEnableSpeed) Icons.Default.CheckCircle else Icons.Default.Error,
                    contentDescription = null,
                    tint = if (autoEnableSpeed) AccentGreen else OnSurface.copy(alpha = 0.3f),
                    modifier = Modifier.size(24.dp)
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
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun GeneralTab(
    padding: androidx.compose.foundation.layout.PaddingValues,
    context: Context,
    floatingIconEnabled: Boolean,
    showClearLogDialog: Boolean,
    onShowClearLog: (Boolean) -> Unit,
    onFloatingIconChanged: (Boolean) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(Modifier.height(4.dp))

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
                Icon(
                    imageVector = if (floatingIconEnabled) Icons.Default.CheckCircle else Icons.Default.Error,
                    contentDescription = null,
                    tint = if (floatingIconEnabled) AccentGreen else OnSurface.copy(alpha = 0.3f),
                    modifier = Modifier.size(24.dp)
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
