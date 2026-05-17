package com.androce

import android.Manifest
import android.os.Build
import android.os.Bundle
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import com.androce.ui.ProcessListScreen
import com.androce.ui.ResultsScreen
import com.androce.ui.SearchScreen
import com.androce.ui.SettingsScreen
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

enum class Screen { PROCESS_LIST, SEARCH, RESULTS }

class MainActivity : ComponentActivity() {

    private val notifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted or denied — freeze notification may not show on denial */ }

    private val currentScreen = mutableStateOf(Screen.PROCESS_LIST)
    private val bottomTab = mutableIntStateOf(0) // 0 = Scanner, 1 = Results, 2 = Settings
    private val isLoading = mutableStateOf(true)
    private val hasRoot = mutableStateOf(false)
    private val showRootError = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when (bottomTab.intValue) {
                    2 -> { bottomTab.intValue = 0; return }
                    1 -> { bottomTab.intValue = 0; return }
                }
                when (currentScreen.value) {
                    Screen.RESULTS -> currentScreen.value = Screen.SEARCH
                    Screen.SEARCH -> currentScreen.value = Screen.PROCESS_LIST
                    Screen.PROCESS_LIST -> finish()
                }
            }
        })

        enableEdgeToEdge()
        setContent {
            AndroCETheme {
                val processVm: ProcessViewModel = viewModel()
                val scanVm: ScanViewModel = viewModel()
                val context = LocalContext.current
                var screen by remember { currentScreen }
                var selectedTab by remember { bottomTab }
                var loading by remember { isLoading }
                var hasRootAccess by remember { hasRoot }

                LaunchedEffect(Unit) {
                    // Check root access first
                    val rootGranted = Shell.getShell().isRoot
                    hasRootAccess = rootGranted
                    if (!rootGranted) {
                        loading = false
                        return@LaunchedEffect
                    }
                    // Initialize MemoryReader if not already done
                    if (!com.androce.core.MemoryReader.isNativeHelperReady) {
                        com.androce.core.MemoryReader.init(context)
                    }
                    // Bind freeze service
                    scanVm.bindFreezeService(context)
                    // Minimum 500ms loading screen for smooth UX
                    delay(500)
                    loading = false
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
                                Icons.Default.Error,
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
                                onClick = { finish() },
                                colors = ButtonDefaults.buttonColors(containerColor = Primary)
                            ) {
                                Text("Exit", color = Color.White)
                            }
                        }
                    }
                } else {
                Scaffold(
                    containerColor = Background,
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
                                    .height(48.dp),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                            BottomNavTab(
                                icon = if (selectedTab == 0) Icons.Filled.Memory else Icons.Outlined.Memory,
                                label = "Scanner",
                                selected = selectedTab == 0,
                                onClick = { selectedTab = 0 }
                            )
                            BottomNavTab(
                                icon = if (selectedTab == 1) Icons.Filled.Article else Icons.Outlined.Article,
                                label = "Results",
                                selected = selectedTab == 1,
                                onClick = { selectedTab = 1 }
                            )
                            BottomNavTab(
                                icon = if (selectedTab == 2) Icons.Filled.Settings else Icons.Outlined.Settings,
                                label = "Settings",
                                selected = selectedTab == 2,
                                onClick = { selectedTab = 2 }
                            )
                            }
                        }
                    }
                ) { innerPadding ->
                    Surface(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                        color = Background
                    ) {
                        when (selectedTab) {
                            0 -> when (screen) {
                                Screen.PROCESS_LIST -> ProcessListScreen(
                                    viewModel = processVm,
                                    onProcessSelected = { process ->
                                        scanVm.selectedProcess = process
                                        scanVm.resetScan()
                                        scanVm.loadRegions()
                                        screen = Screen.SEARCH
                                    }
                                )
                                Screen.SEARCH -> SearchScreen(
                                    viewModel = scanVm,
                                    onBack = { screen = Screen.PROCESS_LIST },
                                    onViewResults = {
                                        selectedTab = 1
                                    }
                                )
                                Screen.RESULTS -> ResultsScreen(
                                    viewModel = scanVm,
                                    onBack = null
                                )
                            }
                            1 -> ResultsScreen(
                                viewModel = scanVm,
                                onBack = null
                            )
                            2 -> SettingsScreen()
                        }
                    }
                }
            }
                }
        }
    }
}

@Composable
private fun BottomNavTab(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val tint = if (selected) Primary else OnBackground.copy(alpha = 0.5f)
    Column(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(20.dp))
        Text(label, color = tint, fontSize = 10.sp)
    }
}
