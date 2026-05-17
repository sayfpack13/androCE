package com.androce

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.widget.Toast
import com.topjohnwu.superuser.Shell
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import com.androce.ui.ProcessListScreen
import com.androce.ui.ResultsScreen
import com.androce.ui.SearchScreen
import com.androce.ui.SettingsScreen
import com.androce.ui.SpeedControlScreen
import com.androce.ui.LoadingScreen
import com.androce.ui.theme.AndroCETheme
import com.androce.ui.theme.Background
import com.androce.ui.theme.OnBackground
import com.androce.ui.theme.OnSurface
import com.androce.ui.theme.Primary
import com.androce.ui.theme.Error
import com.androce.ui.theme.Surface as SurfaceColor
import com.androce.viewmodel.ProcessViewModel
import com.androce.viewmodel.ScanViewModel
import com.androce.core.AppPrefs

class MainActivity : ComponentActivity() {

    private val notifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted or denied — freeze notification may not show on denial */ }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        // Check if permission was granted after returning from settings
        if (canDrawOverlays()) {
            // Permission granted, start the floating service if enabled
            if (AppPrefs.floatingIconEnabled) {
                startService(Intent(this, com.androce.core.FloatingIconService::class.java))
            }
        }
    }

    private val bottomTab = mutableIntStateOf(0) // 0 = Process, 1 = Scanner, 2 = Results, 3 = Speed, 4 = Settings
    private val isLoading = mutableStateOf(true)
    private val hasRoot = mutableStateOf(false)
    private val showRootError = mutableStateOf(false)
    private val showExitDialog = mutableStateOf(false)
    private val showOverlayPermissionDialog = mutableStateOf(false)

    fun canDrawOverlays(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }

    fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
        }
    }

    fun startFloatingIconServiceIfEnabled() {
        if (AppPrefs.floatingIconEnabled) {
            if (canDrawOverlays()) {
                startService(Intent(this, com.androce.core.FloatingIconService::class.java))
            } else {
                requestOverlayPermission()
                Toast.makeText(this, "Grant overlay permission to show floating icon", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun stopFloatingIconService() {
        stopService(Intent(this, com.androce.core.FloatingIconService::class.java))
    }

    /** Tear down services and kill the process so nothing keeps running in the background. */
    private fun forceExit() {
        try {
            val floatIntent = Intent(this, com.androce.core.FloatingIconService::class.java).apply {
                action = com.androce.core.FloatingIconService.ACTION_STOP
            }
            startService(floatIntent)
            stopService(Intent(this, com.androce.core.FloatingIconService::class.java))
            stopService(Intent(this, com.androce.core.FreezeService::class.java))
            val scanVm = ViewModelProvider(this)[ScanViewModel::class.java]
            scanVm.cancelScan()
            scanVm.unbindFreezeService(this)
        } catch (_: Exception) { }

        finishAndRemoveTask()
        Process.killProcess(Process.myPid())
        System.exit(0)
    }

    private fun hideFloatingIcon() {
        if (!AppPrefs.floatingIconEnabled) return
        val intent = Intent(this, com.androce.core.FloatingIconService::class.java).apply {
            action = com.androce.core.FloatingIconService.ACTION_HIDE
        }
        startService(intent)
    }

    private fun showFloatingIcon() {
        if (!AppPrefs.floatingIconEnabled || !canDrawOverlays()) return
        val intent = Intent(this, com.androce.core.FloatingIconService::class.java).apply {
            action = com.androce.core.FloatingIconService.ACTION_SHOW
        }
        startService(intent)
    }

    override fun onResume() {
        super.onResume()
        // Hide floating icon when app is in foreground (only if overlay permission granted)
        if (AppPrefs.floatingIconEnabled && canDrawOverlays()) {
            hideFloatingIcon()
        }
    }

    override fun onPause() {
        super.onPause()
        // Don't pause scan when app goes to background - scan continues
        // Show floating icon when app goes to background (requires overlay permission)
        if (AppPrefs.floatingIconEnabled && canDrawOverlays()) {
            showFloatingIcon()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Setup global exception handler
        com.androce.core.GlobalExceptionHandler.setup(this)

        // Initialize AppPrefs early for permission checks
        com.androce.core.AppPrefs.init(this)

        // Request notification permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // Check and request overlay permission if floating icon is enabled but permission not granted
        if (AppPrefs.floatingIconEnabled && !canDrawOverlays()) {
            showOverlayPermissionDialog.value = true
        }

        // Auto-start floating icon if enabled and permission granted
        if (AppPrefs.floatingIconEnabled && canDrawOverlays()) {
            startService(Intent(this, com.androce.core.FloatingIconService::class.java))
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Navigate back through tabs: Settings -> Speed -> Results -> Scanner -> Process -> Exit
                when (bottomTab.intValue) {
                    4 -> { bottomTab.intValue = 3; return }
                    3 -> { bottomTab.intValue = 2; return }
                    2 -> { bottomTab.intValue = 1; return }
                    1 -> { bottomTab.intValue = 0; return }
                    0 -> forceExit()
                }
            }
        })

        enableEdgeToEdge()
        setContent {
            AndroCETheme {
                val processVm: ProcessViewModel = viewModel()
                val scanVm: ScanViewModel = viewModel()
                val context = LocalContext.current
                var selectedTab by remember { bottomTab }
                val snackbarHostState = remember { SnackbarHostState() }
                val processChangeNotice by scanVm.processChangeNotice.collectAsState()
                val selectedProcess by scanVm.selectedProcess.collectAsState()
                val results by scanVm.results.collectAsState()
                var loading by remember { isLoading }
                var hasRootAccess by remember { hasRoot }

                LaunchedEffect(processChangeNotice) {
                    processChangeNotice?.let { notice ->
                        snackbarHostState.showSnackbar(notice.message)
                        scanVm.clearProcessChangeNotice()
                    }
                }

                LaunchedEffect(Unit) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        // Check root access first
                        val rootGranted = Shell.getShell().isRoot
                        hasRootAccess = rootGranted
                        if (!rootGranted) {
                            withContext(kotlinx.coroutines.Dispatchers.Main) { loading = false }
                            return@withContext
                        }
                        // Initialize AppPrefs
                        com.androce.core.AppPrefs.init(context)
                        // Initialize MemoryReader if not already done
                        if (!com.androce.core.MemoryReader.isNativeHelperReady) {
                            com.androce.core.MemoryReader.init(context)
                        }
                        // Initialize speed injector
                        scanVm.initSpeedInjector(context)
                        // Bind freeze service
                        scanVm.bindFreezeService(context)
                        // Auto-start floating icon if enabled and root granted
                        if (AppPrefs.floatingIconEnabled && canDrawOverlays()) {
                            startService(Intent(context, com.androce.core.FloatingIconService::class.java))
                        }
                        // Minimum 500ms loading screen for smooth UX
                        kotlinx.coroutines.delay(500)
                        withContext(kotlinx.coroutines.Dispatchers.Main) { loading = false }
                    }
                }

                if (loading) {
                    LoadingScreen(message = "Checking root access...")
                } else if (!hasRootAccess) {
                    // Show error screen if root not granted
                    Box(
                        Modifier.fillMaxSize().background(Background),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = null,
                                tint = Error,
                                modifier = Modifier.size(64.dp)
                            )
                            Text(
                                "Root Access Required",
                                color = OnBackground,
                                fontWeight = FontWeight.Bold,
                                fontSize = 20.sp
                            )
                            Text(
                                "This app requires root access to function. Please grant root permission and restart the app.",
                                color = OnSurface,
                                fontSize = 14.sp,
                                textAlign = TextAlign.Center
                            )
                            Button(
                                onClick = { forceExit() },
                                colors = ButtonDefaults.buttonColors(containerColor = Primary)
                            ) {
                                Text("Exit", color = Color.White)
                            }
                        }
                    }
                } else {
                Scaffold(
                    containerColor = Background,
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    bottomBar = {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(SurfaceColor)
                                .navigationBarsPadding()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                BottomNavTab(
                                    icon = if (selectedTab == 0) Icons.Filled.Apps else Icons.Outlined.Apps,
                                    label = "Process",
                                    badge = selectedProcess?.displayName(),
                                    selected = selectedTab == 0,
                                    onClick = { selectedTab = 0 }
                                )
                                BottomNavTab(
                                    icon = if (selectedTab == 1) Icons.Filled.Memory else Icons.Outlined.Memory,
                                    label = "Scanner",
                                    selected = selectedTab == 1,
                                    onClick = { selectedTab = 1 }
                                )
                                BottomNavTab(
                                    icon = if (selectedTab == 2) Icons.AutoMirrored.Filled.Article else Icons.AutoMirrored.Outlined.Article,
                                    label = "Results",
                                    badge = if (results.isNotEmpty()) results.size.toString() else null,
                                    selected = selectedTab == 2,
                                    onClick = { selectedTab = 2 }
                                )
                                BottomNavTab(
                                    icon = if (selectedTab == 3) Icons.Filled.Speed else Icons.Outlined.Speed,
                                    label = "Speed",
                                    selected = selectedTab == 3,
                                    onClick = { selectedTab = 3 }
                                )
                                BottomNavTab(
                                    icon = if (selectedTab == 4) Icons.Filled.Settings else Icons.Outlined.Settings,
                                    label = "Settings",
                                    selected = selectedTab == 4,
                                    onClick = { selectedTab = 4 }
                                )
                            }
                        }
                    }
                ) { innerPadding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(bottom = innerPadding.calculateBottomPadding())
                    ) {
                        GlobalAppBar(
                            onMinimize = { moveTaskToBack(true) },
                            onExit = { showExitDialog.value = true }
                        )
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            color = Background
                        ) {
                            when (selectedTab) {
                            0 -> {
                                val selectedProcess by scanVm.selectedProcess.collectAsState()
                                ProcessListScreen(
                                    viewModel = processVm,
                                    selectedProcess = selectedProcess,
                                    onProcessSelected = { process ->
                                        scanVm.setSelectedProcess(process)
                                    }
                                )
                            }
                            1 -> SearchScreen(
                                viewModel = scanVm,
                                onBack = {},
                                onViewResults = { selectedTab = 2 }
                            )
                            2 -> ResultsScreen(
                                viewModel = scanVm,
                                onBack = null
                            )
                            3 -> {
                                val selectedProcess by scanVm.selectedProcess.collectAsState()
                                SpeedControlScreen(
                                    viewModel = scanVm,
                                    selectedProcess = selectedProcess
                                )
                            }
                            4 -> SettingsScreen()
                            }
                        }
                    }
                }
            }

            if (showExitDialog.value) {
                AlertDialog(
                    onDismissRequest = { showExitDialog.value = false },
                    title = { Text("Exit androCE?", color = OnBackground) },
                    text = { Text("Are you sure you want to exit the app?", color = OnBackground.copy(alpha = 0.7f)) },
                    confirmButton = {
                        TextButton(onClick = { forceExit() }) {
                            Text("Exit", color = Error)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showExitDialog.value = false }) {
                            Text("Cancel", color = OnBackground)
                        }
                    },
                    containerColor = SurfaceColor,
                    shape = RoundedCornerShape(16.dp)
                )
            }

            // Overlay permission dialog
            if (showOverlayPermissionDialog.value) {
                AlertDialog(
                    onDismissRequest = { showOverlayPermissionDialog.value = false },
                    title = { Text("Permission Required", color = OnBackground) },
                    text = {
                        Column {
                            Text(
                                "androCE needs permission to display over other apps for the floating icon feature.",
                                color = OnBackground.copy(alpha = 0.8f),
                                fontSize = 14.sp
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "The floating icon appears when you minimize the app, providing quick access while gaming.",
                                color = OnSurface,
                                fontSize = 13.sp
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "You can disable this feature anytime in Settings > General > Floating Icon.",
                                color = OnSurface.copy(alpha = 0.7f),
                                fontSize = 12.sp
                            )
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                showOverlayPermissionDialog.value = false
                                requestOverlayPermission()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Primary)
                        ) {
                            Text("Grant Permission", color = Color.White)
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = {
                                showOverlayPermissionDialog.value = false
                                // Disable floating icon since user declined permission
                                AppPrefs.floatingIconEnabled = false
                            }
                        ) {
                            Text("Disable Feature", color = OnSurface)
                        }
                    },
                    containerColor = SurfaceColor,
                    shape = RoundedCornerShape(16.dp)
                )
            }
            }
        }
    }
}

@Composable
private fun GlobalAppBar(
    onMinimize: () -> Unit,
    onExit: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(44.dp)
            .background(Background)
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        FilledTonalButton(
            onClick = onMinimize,
            modifier = Modifier.height(36.dp),
            contentPadding = PaddingValues(horizontal = 12.dp),
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = SurfaceColor,
                contentColor = Primary
            )
        ) {
            Icon(
                imageVector = Icons.Default.Apps,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "Minimize",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
        }
        FilledIconButton(
            onClick = onExit,
            modifier = Modifier.size(36.dp),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = Error,
                contentColor = Color.White
            )
        ) {
            Icon(
                Icons.Default.PowerSettingsNew,
                contentDescription = "Exit",
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun BottomNavTab(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    badge: String? = null,
    selected: Boolean,
    onClick: () -> Unit
) {
    val tint = if (selected) Primary else OnSurface.copy(alpha = 0.55f)
    Column(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(20.dp))
        Text(label, color = tint, fontSize = 10.sp)
        badge?.let {
            Text(
                it,
                color = Primary,
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp).widthIn(max = 80.dp)
            )
        }
    }
}
