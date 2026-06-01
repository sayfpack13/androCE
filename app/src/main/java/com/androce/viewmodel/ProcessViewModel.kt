package com.androce.viewmodel

import android.content.Context
import android.content.pm.PackageManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.androce.core.AppPrefs
import com.androce.core.ProcessLister
import com.androce.core.findActivity
import com.androce.core.virtual.VirtualEngineFacade
import com.androce.core.virtual.VirtualSpaceManager
import com.androce.model.ProcessInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class SearchMode { ALL, TITLE, PACKAGE }

sealed class ProcessListState {
    object Idle : ProcessListState()
    object Loading : ProcessListState()
    data class Error(val message: String) : ProcessListState()
    data class Success(val processes: List<ProcessInfo>) : ProcessListState()
}

class ProcessViewModel : ViewModel() {

    private val _state = MutableStateFlow<ProcessListState>(ProcessListState.Idle)
    val state: StateFlow<ProcessListState> = _state

    private val _allProcesses = MutableStateFlow<List<ProcessInfo>>(emptyList())
    val searchQuery = MutableStateFlow("")
    val searchMode = MutableStateFlow(SearchMode.ALL)

    /** True when the selected target process is confirmed running. Null = unknown/not set. */
    val isTargetRunning = MutableStateFlow<Boolean?>(null)

    private var autoRefreshJob: Job? = null
    private var virtualUiJob: Job? = null

    data class VirtualSpaceUiState(
        val launchingPackage: String? = null,
        val installingPackage: String? = null
    )

    private val _virtualSpaceUi = MutableStateFlow(VirtualSpaceUiState())
    val virtualSpaceUi: StateFlow<VirtualSpaceUiState> = _virtualSpaceUi

    val virtualGuestRuntime = VirtualEngineFacade.runtimeState

    private val _virtualInstalled = MutableStateFlow<List<VirtualSpaceManager.VirtualAppMetadata>>(emptyList())
    val virtualInstalled: StateFlow<List<VirtualSpaceManager.VirtualAppMetadata>> = _virtualInstalled

    private val _virtualAvailable = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val virtualAvailable: StateFlow<List<Pair<String, String>>> = _virtualAvailable

    private val _virtualCatalogLoading = MutableStateFlow(false)
    val virtualCatalogLoading: StateFlow<Boolean> = _virtualCatalogLoading

    /** Load catalog when Virtual Space tab opens; guest run state comes from VirtualEngineFacade.runtimeState. */
    fun setVirtualTabActive(active: Boolean, context: Context) {
        virtualUiJob?.cancel()
        if (!active) return
        refreshVirtualCatalog(context)
        loadProcesses(context)
    }

    fun refreshVirtualCatalog(context: Context) {
        viewModelScope.launch(Dispatchers.Main) {
            _virtualCatalogLoading.value = true
            try {
                val installed = withContext(Dispatchers.IO) {
                    VirtualSpaceManager.getInstalledVirtualApps(context)
                }
                val available = withContext(Dispatchers.IO) { loadAvailableApps(context) }
                val installedPkgs = installed.map { it.packageName }.toSet()
                _virtualInstalled.value = installed
                _virtualAvailable.value = available.filter { it.first !in installedPkgs }
            } finally {
                _virtualCatalogLoading.value = false
            }
        }
    }

    private fun loadAvailableApps(context: Context): List<Pair<String, String>> {
        val pm = context.packageManager
        val launchIntent = android.content.Intent(android.content.Intent.ACTION_MAIN, null)
            .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(launchIntent, android.content.pm.PackageManager.MATCH_ALL)
            .mapNotNull { it.activityInfo?.packageName }
            .filter { it != context.packageName }
            .distinct()
            .mapNotNull { pkg ->
                try {
                    val label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                    pkg to label
                } catch (_: Exception) { null }
            }
            .sortedBy { it.second.lowercase() }
    }

    fun launchVirtualGuest(
        context: Context,
        packageName: String,
        appName: String,
        onSelected: (ProcessInfo?) -> Unit
    ) {
        viewModelScope.launch {
            if (VirtualEngineFacade.needsAllFilesAccess()) {
                VirtualEngineFacade.openAllFilesAccessSettings(context)
                android.widget.Toast.makeText(
                    context,
                    "Allow “All files access” for androCE, then tap Play again",
                    android.widget.Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            _virtualSpaceUi.value = _virtualSpaceUi.value.copy(launchingPackage = packageName)
            try {
                val ok = withContext(Dispatchers.IO) {
                    VirtualSpaceManager.launchApp(context, packageName, appName)
                }
                if (!ok) {
                    android.widget.Toast.makeText(
                        context,
                        "Failed to launch clone — check androCE log",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    return@launch
                }
                VirtualEngineFacade.refreshRuntimeState(context)
                val runtime = VirtualEngineFacade.runtimeState.value
                val pid = runtime.pid.takeIf { it > 0 }
                    ?: VirtualEngineFacade.resolveGuestPid(context, packageName)
                if (pid > 0) {
                    onSelected(
                        ProcessInfo(
                            pid = pid,
                            name = packageName,
                            packageName = packageName,
                            appName = appName,
                            isVirtual = true
                        )
                    )
                }
                openVirtualGuestUi(context, packageName)
                loadProcesses(context)
            } finally {
                _virtualSpaceUi.value = _virtualSpaceUi.value.copy(launchingPackage = null)
            }
        }
    }

    fun openVirtualGuestUi(context: Context, packageName: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                VirtualEngineFacade.bringGuestToForeground(context, packageName)
            }
            withContext(Dispatchers.Main) {
                context.findActivity()?.moveTaskToBack(true)
            }
        }
    }

    fun stopVirtualGuest(context: Context, packageName: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                VirtualSpaceManager.stopApp(context, packageName)
            }
            loadProcesses(context)
        }
    }

    fun installVirtualApp(context: Context, packageName: String) {
        viewModelScope.launch {
            _virtualSpaceUi.value = _virtualSpaceUi.value.copy(installingPackage = packageName)
            try {
                val ok = withContext(Dispatchers.IO) {
                    VirtualSpaceManager.installAppToVirtualSpace(context, packageName)
                }
                if (ok) {
                    refreshVirtualCatalog(context)
                    loadProcesses(context)
                }
            } finally {
                _virtualSpaceUi.value = _virtualSpaceUi.value.copy(installingPackage = null)
            }
        }
    }

    fun uninstallVirtualApp(context: Context, packageName: String, appName: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                VirtualSpaceManager.uninstallApp(context, packageName)
            }
            _virtualAvailable.value = _virtualAvailable.value + (packageName to appName)
            refreshVirtualCatalog(context)
            loadProcesses(context)
        }
    }

    /** Start polling process state every 3s. Call when entering virtual mode. */
    fun startAutoRefresh(context: Context) {
        if (autoRefreshJob?.isActive == true) return
        autoRefreshJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(1500)
                val useVirtual = AppPrefs.operationMode == "virtual" ||
                    (AppPrefs.operationMode == "auto" && com.topjohnwu.superuser.Shell.isAppGrantedRoot() != true)
                if (!useVirtual) break
                val processes = try { ProcessLister.listProcesses(context, context.packageManager) } catch (_: Exception) { continue }
                withContext(Dispatchers.Main) {
                    _allProcesses.value = processes
                    if (_state.value is ProcessListState.Success) {
                        _state.value = ProcessListState.Success(processes)
                    }
                }
            }
        }
    }

    fun stopAutoRefresh() {
        autoRefreshJob?.cancel()
        autoRefreshJob = null
    }

    /** Poll whether the given package is still running and update isTargetRunning. */
    fun watchProcess(context: Context, packageName: String?) {
        if (packageName == null) { isTargetRunning.value = null; return }
        viewModelScope.launch(Dispatchers.IO) {
            val uid = try { context.packageManager.getApplicationInfo(packageName, 0).uid } catch (_: Exception) { -1 }
            while (isActive) {
                val running = VirtualEngineFacade.isGuestAlive(context, packageName) ||
                    (uid > 0 && ProcessLister.findPidForUid(uid) != null) ||
                    ProcessLister.findPidForPackage(packageName) != null
                isTargetRunning.value = running
                delay(1000)
            }
        }
    }

    val filteredProcesses: StateFlow<List<ProcessInfo>> = combine(_allProcesses, searchQuery, searchMode) { all, q, mode ->
        if (q.isBlank()) all
        else all.filter { p ->
            val matchesPid = p.pid.toString().contains(q)
            val matchesTitle = p.appName?.contains(q, ignoreCase = true) == true ||
                p.name.contains(q, ignoreCase = true)
            val matchesPackage = p.packageName.contains(q, ignoreCase = true)
            when (mode) {
                SearchMode.TITLE   -> matchesPid || matchesTitle
                SearchMode.PACKAGE -> matchesPid || matchesPackage
                SearchMode.ALL     -> matchesPid || matchesTitle || matchesPackage
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun loadProcesses(context: Context) {
        viewModelScope.launch(Dispatchers.Main) {
            _state.value = ProcessListState.Loading
            delay(32) // let Compose render the spinner frame before blocking IO starts
            try {
                val processes = withContext(Dispatchers.IO) { ProcessLister.listProcesses(context, context.packageManager) }
                _allProcesses.value = processes
                _state.value = ProcessListState.Success(processes)
            } catch (e: Exception) {
                _state.value = ProcessListState.Error(e.message ?: "Unknown error")
            }
        }
    }
}
