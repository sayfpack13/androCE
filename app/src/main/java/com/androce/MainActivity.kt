package com.androce

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

                    var screen by remember { mutableStateOf(Screen.PROCESS_LIST) }

                    DisposableEffect(Unit) {
                        scanVm.bindFreezeService(context)
                        onDispose { scanVm.unbindFreezeService(context) }
                    }

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
