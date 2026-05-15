package com.androce

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.androce.ui.ProcessListScreen
import com.androce.ui.ResultsScreen
import com.androce.ui.SearchScreen
import com.androce.ui.theme.AndroCETheme
import com.androce.viewmodel.ProcessViewModel
import com.androce.viewmodel.ScanViewModel

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
                    val navController = rememberNavController()
                    val processVm: ProcessViewModel = viewModel()
                    val scanVm: ScanViewModel = viewModel()
                    val context = LocalContext.current

                    DisposableEffect(Unit) {
                        scanVm.bindFreezeService(context)
                        onDispose { scanVm.unbindFreezeService(context) }
                    }

                    NavHost(navController = navController, startDestination = "process_list") {
                        composable("process_list") {
                            ProcessListScreen(
                                viewModel = processVm,
                                onProcessSelected = { process ->
                                    scanVm.selectedProcess = process
                                    scanVm.resetScan()
                                    scanVm.loadRegions()
                                    navController.navigate("search")
                                }
                            )
                        }
                        composable("search") {
                            SearchScreen(
                                viewModel = scanVm,
                                onBack = { navController.popBackStack() },
                                onViewResults = { navController.navigate("results") }
                            )
                        }
                        composable("results") {
                            ResultsScreen(
                                viewModel = scanVm,
                                onBack = { navController.popBackStack() }
                            )
                        }
                    }
                }
            }
        }
    }
}
