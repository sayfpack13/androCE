package com.androce

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.androce.ui.ProcessListScreen
import com.androce.ui.ResultsScreen
import com.androce.ui.SearchScreen
import com.androce.ui.theme.AndroCETheme
import com.androce.viewmodel.ProcessViewModel
import com.androce.viewmodel.ScanViewModel

enum class Screen { PROCESS_LIST, SEARCH, RESULTS }

class MainActivity : ComponentActivity() {

    private val notifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted or denied — freeze notification may not show on denial */ }

    private val currentScreen = mutableStateOf(Screen.PROCESS_LIST)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
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
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val processVm: ProcessViewModel = viewModel()
                    val scanVm: ScanViewModel = viewModel()
                    val context = LocalContext.current

                    var screen by remember { currentScreen }

                    when (screen) {
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
                            onViewResults = { screen = Screen.RESULTS }
                        )
                        Screen.RESULTS -> ResultsScreen(
                            viewModel = scanVm,
                            onBack = { screen = Screen.SEARCH }
                        )
                    }
                }
            }
        }
    }
}
