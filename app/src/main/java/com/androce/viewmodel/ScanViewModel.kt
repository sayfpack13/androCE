package com.androce.viewmodel

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.androce.core.AppLogger
import com.androce.core.AppPrefs
import com.androce.core.FreezeService
import com.androce.core.MemoryReader
import com.androce.core.MemoryWriter
import com.androce.core.ScanProgress
import com.androce.core.Scanner
import com.androce.core.SpeedControl
import com.androce.core.SpeedHackState
import com.androce.core.SpeedInjector
import com.androce.core.ValueEncoder
import com.androce.model.CheatTable
import com.androce.model.CheatTableEntry
import com.androce.model.MemoryRegion
import com.androce.model.ChangeDirection
import com.androce.model.ProcessInfo
import com.androce.model.RegionFilter
import com.androce.model.ScanComparison
import com.androce.model.ScanResult
import com.androce.model.ValueType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

sealed class ScanState {
    object Idle : ScanState()
    data class Scanning(val progress: ScanProgress) : ScanState()
    data class Done(val results: List<ScanResult>) : ScanState()
    data class Error(val message: String) : ScanState()
}

data class CheatTableMeta(val processName: String, val savedAt: Long, val entryCount: Int)

/** One-shot UI notice after the attached target process changes. */
data class ProcessChangeNotice(val message: String)

class ScanViewModel : ViewModel() {

    private val _selectedProcess = MutableStateFlow<ProcessInfo?>(null)
    val selectedProcess: StateFlow<ProcessInfo?> = _selectedProcess

    private val _processChangeNotice = MutableStateFlow<ProcessChangeNotice?>(null)
    val processChangeNotice: StateFlow<ProcessChangeNotice?> = _processChangeNotice

    fun clearProcessChangeNotice() {
        _processChangeNotice.value = null
    }

    fun setSelectedProcess(value: ProcessInfo?) {
        val current = _selectedProcess.value
        if (current?.pid == value?.pid && current?.packageName == value?.packageName) return

        val previous = current
        _selectedProcess.value = value
        onProcessContextChanged(previous, value)

        _processChangeNotice.value = ProcessChangeNotice(buildProcessChangeMessage(previous, value))
    }

    private fun buildProcessChangeMessage(previous: ProcessInfo?, new: ProcessInfo?): String = when {
        new == null ->
            "Process cleared — scan results, freezes, and speed hack were reset"
        previous == null ->
            "Attached to ${new.displayName()} [PID ${new.pid}]"
        previous.packageName == new.packageName && previous.pid != new.pid ->
            "${new.displayName()} restarted (PID ${previous.pid} → ${new.pid}) — scan results cleared"
        else ->
            "Switched to ${new.displayName()} [PID ${new.pid}] — previous results cleared"
    }

    /** Drop all state that is tied to a specific target process (PID). */
    private fun onProcessContextChanged(previous: ProcessInfo?, new: ProcessInfo?) {
        scanJob?.cancel()
        Scanner.paused = false
        _isPaused.value = false
        _regions.value = emptyList()
        regionsPid = null
        _results.value = emptyList()
        _scanState.value = ScanState.Idle

        freezeService?.clearAll()

        val speed = SpeedControl.state.value
        if (speed.state == SpeedHackState.ACTIVE || speed.state == SpeedHackState.INJECTING) {
            SpeedInjector.reset()
        }

        if (new != null) {
            loadRegions()
        }
    }

    fun requireSelectedPid(): Int? = _selectedProcess.value?.pid

    var selectedValueType: ValueType = ValueType.BYTE4
    var searchInput: String = ""
    var rangeMin: String = ""
    var rangeMax: String = ""
    var xorKey: Long = 0L

    private val _regionFilter = MutableStateFlow<RegionFilter>(RegionFilter.ALL)
    val regionFilter: StateFlow<RegionFilter> = _regionFilter

    private val _scanState = MutableStateFlow<ScanState>(ScanState.Idle)
    val scanState: StateFlow<ScanState> = _scanState

    private val _results = MutableStateFlow<List<ScanResult>>(emptyList())
    val results: StateFlow<List<ScanResult>> = _results

    private val _regions = MutableStateFlow<List<MemoryRegion>>(emptyList())
    val regions: StateFlow<List<MemoryRegion>> = _regions

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused

    private var scanJob: Job? = null
    private val regionLoadMutex = Mutex()
    private var regionsPid: Int? = null
    private var freezeService: FreezeService? = null
    private var freezeServiceBound = false

    val freezeServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as FreezeService.FreezeServiceBinder
            freezeService = binder.getService()
            freezeServiceBound = true
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            freezeServiceBound = false
            freezeService = null
        }
    }

    fun bindFreezeService(context: Context) {
        val intent = Intent(context, FreezeService::class.java)
        context.startService(intent)
        context.bindService(intent, freezeServiceConnection, Context.BIND_AUTO_CREATE)
    }

    fun unbindFreezeService(context: Context) {
        if (freezeServiceBound) {
            context.unbindService(freezeServiceConnection)
            freezeServiceBound = false
        }
        context.stopService(Intent(context, FreezeService::class.java))
    }

    fun loadRegions() {
        val pid = selectedProcess.value?.pid ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val regions = MemoryReader.getReadableRegions(pid)
                withContext(Dispatchers.Main) {
                    if (isCurrentProcess(pid)) {
                        _regions.value = regions
                        regionsPid = pid
                    }
                }
            } catch (e: Exception) {
                AppLogger.e("ScanViewModel", "loadRegions failed", e)
                withContext(Dispatchers.Main) {
                    _regions.value = emptyList()
                    regionsPid = null
                }
            }
        }
    }

    private fun isCurrentProcess(pid: Int): Boolean = _selectedProcess.value?.pid == pid

    /**
     * Returns cached readable regions for [pid], or loads them once in a thread-safe way.
     * Cache is PID-aware and protected with a mutex to prevent duplicate concurrent loads.
     */
    private suspend fun getOrLoadRegions(pid: Int): List<MemoryRegion> {
        return regionLoadMutex.withLock {
            val cached = _regions.value
            if (regionsPid == pid) return@withLock cached

            try {
                val loaded = MemoryReader.getReadableRegions(pid)
                val applied = withContext(Dispatchers.Main) {
                    if (isCurrentProcess(pid)) {
                        _regions.value = loaded
                        regionsPid = pid
                        true
                    } else {
                        regionsPid = null
                        false
                    }
                }
                if (applied) loaded else emptyList()
            } catch (e: Exception) {
                AppLogger.e("ScanViewModel", "getOrLoadRegions failed", e)
                withContext(Dispatchers.Main) {
                    if (isCurrentProcess(pid)) {
                        _regions.value = emptyList()
                        regionsPid = null
                    }
                }
                emptyList()
            }
        }
    }

    // ---- Scans ----

    fun firstScan() {
        val pid = selectedProcess.value?.pid ?: return

        if (selectedValueType == com.androce.model.ValueType.ALL) {
            // Scan across all numeric types
            scanJob?.cancel()
            scanJob = viewModelScope.launch(Dispatchers.IO) {
                try {
                    withContext(Dispatchers.Main) {
                        _scanState.value = ScanState.Scanning(ScanProgress(0, 0, 0))
                        AppPrefs.isScanning = true
                        AppPrefs.scanProgress = 0
                    }
                    val regions = getOrLoadRegions(pid)

                    val numericTypes = listOf(
                        com.androce.model.ValueType.BYTE1, com.androce.model.ValueType.BYTE2,
                        com.androce.model.ValueType.BYTE4, com.androce.model.ValueType.BYTE8,
                        com.androce.model.ValueType.FLOAT, com.androce.model.ValueType.DOUBLE
                    )

                    val allResults = mutableListOf<com.androce.model.ScanResult>()
                    for ((idx, type) in numericTypes.withIndex()) {
                        if (!isActive) break
                        while (Scanner.paused) {
                            delay(100); if (!isActive) break
                        }

                        val (pattern, wildcard) = try {
                            com.androce.core.ValueEncoder.encodeSearchValue(
                                searchInput,
                                type,
                                xorKey
                            )
                        } catch (e: Exception) {
                            continue
                        }

                        AppLogger.d(
                            "ScanViewModel",
                            "firstScan ALL: scanning type=$type pattern=${
                                pattern.joinToString(" ") {
                                    "%02x".format(it)
                                }
                            }"
                        )

                        val results = Scanner.firstScan(
                            pid = pid,
                            valueType = type,
                            pattern = pattern,
                            wildcard = wildcard,
                            regions = regions,
                            regionFilter = _regionFilter.value,
                            onProgress = { progress ->
                                if (isActive) {
                                    viewModelScope.launch(Dispatchers.Main) {
                                        _scanState.value =
                                            ScanState.Scanning(progress.copy(foundCount = allResults.size + progress.foundCount))
                                        val progressPercent = if (progress.totalRegions > 0) {
                                            (progress.scannedRegions * 100 / progress.totalRegions).coerceIn(0, 100)
                                        } else 0
                                        AppPrefs.scanProgress = progressPercent
                                    }
                                }
                            }
                        )
                        allResults.addAll(results)
                    }

                    if (isActive) {
                        withContext(Dispatchers.Main) {
                            _results.value = allResults
                            _scanState.value = ScanState.Done(allResults)
                        }
                        AppLogger.d(
                            "ScanViewModel",
                            "firstScan ALL done: total found=${allResults.size}"
                        )
                    }
                } catch (e: Exception) {
                    if (isActive) {
                        withContext(Dispatchers.Main) {
                            _scanState.value = ScanState.Error(e.message ?: "Scan failed")
                        }
                    }
                }
            }
        } else {
            scanJob?.cancel()
            scanJob = viewModelScope.launch(Dispatchers.IO) {
                try {
                    withContext(Dispatchers.Main) {
                        _scanState.value = ScanState.Scanning(ScanProgress(0, 0, 0))
                        AppPrefs.isScanning = true
                        AppPrefs.scanProgress = 0
                    }
                    val regions = getOrLoadRegions(pid)

                    val allResults = if (selectedValueType == ValueType.STRING) {
                        // Search for both UTF-8 and UTF-16LE encodings
                        val utf8Pattern = searchInput.toByteArray(Charsets.UTF_8)
                        val utf16Pattern = searchInput.toByteArray(Charsets.UTF_16LE)

                        AppLogger.d("ScanViewModel", "firstScan STRING: utf8=${utf8Pattern.size}b utf16=${utf16Pattern.size}b")

                        val utf8Results = Scanner.firstScan(
                            pid = pid,
                            valueType = ValueType.STRING,
                            pattern = utf8Pattern,
                            wildcard = null,
                            regions = regions,
                            regionFilter = _regionFilter.value,
                            onProgress = { progress ->
                                if (isActive) {
                                    viewModelScope.launch(Dispatchers.Main) {
                                        _scanState.value = ScanState.Scanning(progress.copy(foundCount = progress.foundCount))
                                        val progressPercent = if (progress.totalRegions > 0) {
                                            (progress.scannedRegions * 100 / progress.totalRegions).coerceIn(0, 100)
                                        } else 0
                                        AppPrefs.scanProgress = progressPercent
                                    }
                                }
                            }
                        )
                        val utf16Results = Scanner.firstScan(
                            pid = pid,
                            valueType = ValueType.STRING,
                            pattern = utf16Pattern,
                            wildcard = null,
                            regions = regions,
                            regionFilter = _regionFilter.value,
                            onProgress = { progress ->
                                if (isActive) {
                                    viewModelScope.launch(Dispatchers.Main) {
                                        _scanState.value = ScanState.Scanning(
                                            progress.copy(foundCount = utf8Results.size + progress.foundCount)
                                        )
                                        val progressPercent = if (progress.totalRegions > 0) {
                                            (progress.scannedRegions * 100 / progress.totalRegions).coerceIn(0, 100)
                                        } else 0
                                        AppPrefs.scanProgress = progressPercent
                                    }
                                }
                            }
                        )
                        // Combine and deduplicate by address
                        val combined = (utf8Results + utf16Results)
                            .distinctBy { it.address }
                        AppLogger.d("ScanViewModel", "firstScan STRING: utf8=${utf8Results.size} utf16=${utf16Results.size} unique=${combined.size}")
                        combined
                    } else {
                        val (pattern, wildcard) = try {
                            ValueEncoder.encodeSearchValue(searchInput, selectedValueType, xorKey)
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                _scanState.value = ScanState.Error(e.message ?: "Invalid value")
                            }
                            return@launch
                        }
                        AppLogger.d(
                            "ScanViewModel",
                            "firstScan: input='$searchInput' type=$selectedValueType pattern=${
                                pattern.joinToString(" ") { "%02x".format(it) }
                            }"
                        )
                        Scanner.firstScan(
                            pid = pid,
                            valueType = selectedValueType,
                            pattern = pattern,
                            wildcard = wildcard,
                            regions = regions,
                            regionFilter = _regionFilter.value,
                            onProgress = { progress ->
                                if (isActive) {
                                    viewModelScope.launch(Dispatchers.Main) {
                                        _scanState.value = ScanState.Scanning(progress)
                                        val progressPercent = if (progress.totalRegions > 0) {
                                            (progress.scannedRegions * 100 / progress.totalRegions).coerceIn(0, 100)
                                        } else 0
                                        AppPrefs.scanProgress = progressPercent
                                    }
                                }
                            }
                        )
                    }

                    if (isActive) {
                        withContext(Dispatchers.Main) {
                            _results.value = allResults
                            _scanState.value = ScanState.Done(allResults)
                            AppPrefs.isScanning = false
                            AppPrefs.scanProgress = 100
                        }
                    }
                } catch (e: Exception) {
                    if (isActive) {
                        withContext(Dispatchers.Main) {
                            _scanState.value = ScanState.Error(e.message ?: "Scan failed")
                            AppPrefs.isScanning = false
                            AppPrefs.scanProgress = 0
                        }
                    }
                }
            }
        }
    }

    fun unknownInitialScan() {
        val pid = selectedProcess.value?.pid ?: return

        if (selectedValueType == com.androce.model.ValueType.ALL) {
            // Snapshot all numeric types
            scanJob?.cancel()
            Scanner.paused = false
            _isPaused.value = false
            scanJob = viewModelScope.launch(Dispatchers.IO) {
                try {
                    val regions = getOrLoadRegions(pid)
                    val numericTypes = listOf(
                        com.androce.model.ValueType.BYTE1, com.androce.model.ValueType.BYTE2,
                        com.androce.model.ValueType.BYTE4, com.androce.model.ValueType.BYTE8,
                        com.androce.model.ValueType.FLOAT, com.androce.model.ValueType.DOUBLE
                    )

                    val allResults = mutableListOf<com.androce.model.ScanResult>()
                    for (type in numericTypes) {
                        if (!isActive) break
                        while (Scanner.paused) {
                            delay(100); if (!isActive) break
                        }

                        val found = Scanner.unknownInitialScan(
                            pid, type, regions, _regionFilter.value
                        ) { p ->
                            if (isActive) {
                                viewModelScope.launch(Dispatchers.Main) {
                                    _scanState.value =
                                        ScanState.Scanning(p.copy(foundCount = allResults.size + p.foundCount))
                                }
                            }
                        }
                        allResults.addAll(found)
                    }

                    if (isActive) {
                        AppLogger.d(
                            "ScanViewModel",
                            "unknownInitialScan ALL: found=${allResults.size}"
                        )
                        withContext(Dispatchers.Main) {
                            _results.value = allResults
                            _scanState.value = ScanState.Done(allResults)
                        }
                    }
                } catch (e: Exception) {
                    if (isActive) {
                        withContext(Dispatchers.Main) {
                            _scanState.value = ScanState.Error(e.message ?: "Snapshot failed")
                        }
                    }
                }
            }
        } else {
            // Normal single-type scan
            if (selectedValueType.isVariableLength) {
                _scanState.value = ScanState.Error("Unknown initial requires a fixed-size type")
                return
            }
            AppLogger.d("ScanViewModel", "unknownInitialScan: type=$selectedValueType")
            scanJob?.cancel()
            Scanner.paused = false
            _isPaused.value = false
            scanJob = viewModelScope.launch(Dispatchers.IO) {
                try {
                    val regions = getOrLoadRegions(pid)
                    val found = Scanner.unknownInitialScan(
                        pid, selectedValueType, regions, _regionFilter.value
                    ) { p ->
                        if (isActive) {
                            viewModelScope.launch(Dispatchers.Main) {
                                _scanState.value = ScanState.Scanning(p)
                            }
                        }
                    }
                    if (isActive) {
                        // Log sample values for debugging
                        val sample = found.take(5).map { r ->
                            val value = when (selectedValueType) {
                                ValueType.BYTE4 -> r.currentBytes.let {
                                    java.nio.ByteBuffer.wrap(it).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
                                }.toString()
                                ValueType.FLOAT -> r.currentBytes.let {
                                    java.lang.Float.intBitsToFloat(
                                        java.nio.ByteBuffer.wrap(it).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
                                    )
                                }.toString()
                                else -> r.currentBytes.joinToString(" ") { "%02x".format(it) }
                            }
                            "0x${r.address.toString(16)}=$value"
                        }
                        AppLogger.d("ScanViewModel", "unknownInitialScan: found=${found.size} samples=$sample")
                        withContext(Dispatchers.Main) {
                            _results.value = found
                            _scanState.value = ScanState.Done(found)
                        }
                    }
                } catch (e: Exception) {
                    if (isActive) {
                        withContext(Dispatchers.Main) {
                            _scanState.value = ScanState.Error(e.message ?: "Snapshot failed")
                        }
                    }
                }
            }
        }
    }

    fun refinedScan() {
        val pid = selectedProcess.value?.pid ?: return
        val previous = _results.value
        if (previous.isEmpty()) {
            firstScan(); return
        }

        if (selectedValueType == ValueType.ALL) {
            scanJob?.cancel()
            scanJob = viewModelScope.launch(Dispatchers.IO) {
                try {
                    withContext(Dispatchers.Main) {
                        _scanState.value = ScanState.Scanning(ScanProgress(0, previous.size, 0))
                    }
                    val allResults = mutableListOf<ScanResult>()
                    val byType = previous.groupBy { it.valueType }
                    var processed = 0
                    for ((type, items) in byType) {
                        if (!isActive) break
                        while (Scanner.paused) {
                            delay(100); if (!isActive) break
                        }
                        val (pattern, wildcard) = try {
                            ValueEncoder.encodeSearchValue(searchInput, type, xorKey)
                        } catch (e: Exception) {
                            processed += items.size
                            continue
                        }
                        val found = Scanner.refinedScan(pid, items, pattern, wildcard) { p ->
                            if (isActive) {
                                viewModelScope.launch(Dispatchers.Main) {
                                    _scanState.value = ScanState.Scanning(
                                        ScanProgress(processed + p.scannedRegions, previous.size, allResults.size + p.foundCount)
                                    )
                                }
                            }
                        }
                        allResults.addAll(found)
                        processed += items.size
                    }
                    if (isActive) {
                        withContext(Dispatchers.Main) {
                            _results.value = allResults
                            _scanState.value = ScanState.Done(allResults)
                        }
                    }
                } catch (e: Exception) {
                    if (isActive) {
                        withContext(Dispatchers.Main) {
                            _scanState.value = ScanState.Error(e.message ?: "Refined scan failed")
                        }
                    }
                }
            }
            return
        }

        scanJob?.cancel()
        scanJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                AppLogger.d("ScanViewModel", "refinedScan: input='$searchInput' type=$selectedValueType count=${previous.size}")
                val found = if (selectedValueType == ValueType.STRING) {
                    // Refined scan for both UTF-8 and UTF-16 patterns
                    val utf8Pattern = searchInput.toByteArray(Charsets.UTF_8)
                    val utf16Pattern = searchInput.toByteArray(Charsets.UTF_16LE)
                    val utf8Results = Scanner.refinedScan(pid, previous, utf8Pattern, null) { p ->
                        if (isActive) {
                            viewModelScope.launch(Dispatchers.Main) {
                                _scanState.value = ScanState.Scanning(p)
                            }
                        }
                    }
                    val remaining = previous.filter { p -> p.address !in utf8Results.map { it.address }.toSet() }
                    val utf16Results = if (remaining.isNotEmpty()) {
                        Scanner.refinedScan(pid, remaining, utf16Pattern, null) { p ->
                            if (isActive) {
                                viewModelScope.launch(Dispatchers.Main) {
                                    _scanState.value = ScanState.Scanning(
                                        p.copy(foundCount = utf8Results.size + p.foundCount)
                                    )
                                }
                            }
                        }
                    } else emptyList()
                    (utf8Results + utf16Results)
                } else {
                    val (pattern, wildcard) = try {
                        ValueEncoder.encodeSearchValue(searchInput, selectedValueType, xorKey)
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            _scanState.value = ScanState.Error("Invalid input: ${e.message}")
                        }
                        return@launch
                    }
                    Scanner.refinedScan(pid, previous, pattern, wildcard) { p ->
                        if (isActive) {
                            viewModelScope.launch(Dispatchers.Main) {
                                _scanState.value = ScanState.Scanning(p)
                            }
                        }
                    }
                }
                if (isActive) {
                    withContext(Dispatchers.Main) {
                        _results.value = found
                        _scanState.value = ScanState.Done(found)
                    }
                }
            } catch (e: Exception) {
                if (isActive) {
                    withContext(Dispatchers.Main) {
                        _scanState.value = ScanState.Error(e.message ?: "Refined scan failed")
                    }
                }
            }
        }
    }

    /**
     * Run a comparison against the current results. For ops without value, [op.needsValue] is false.
     * EXACT and BETWEEN use the rangeMin/rangeMax or searchInput.
     */
    fun comparisonScan(op: ScanComparison) {
        val pid = selectedProcess.value?.pid ?: return
        val previous = _results.value
        if (previous.isEmpty()) {
            _scanState.value = ScanState.Error("No previous results — run First Scan first"); return
        }
        val (operand1, operand2) = try {
            when (op) {
                ScanComparison.EXACT -> Pair(searchInput.trim().ifBlank { null }, null)
                ScanComparison.INCREASED_BY,
                ScanComparison.DECREASED_BY -> Pair(searchInput.trim().ifBlank { null }, null)
                ScanComparison.BETWEEN -> Pair(
                    rangeMin.trim().ifBlank { null },
                    rangeMax.trim().ifBlank { null }
                )
                else -> Pair(null, null)
            }
        } catch (e: Exception) {
            _scanState.value = ScanState.Error("Invalid operand: ${e.message}"); return
        }
        if (op.needsValue && operand1 == null) {
            _scanState.value = ScanState.Error("${op.label} requires a value"); return
        }
        if (op == ScanComparison.BETWEEN && operand2 == null) {
            _scanState.value = ScanState.Error("Between requires both min and max values"); return
        }
        AppLogger.d("ScanViewModel", "comparisonScan: op=${op.name} type=$selectedValueType count=${previous.size} op1=$operand1 op2=$operand2")

        if (selectedValueType == ValueType.ALL) {
            scanJob?.cancel()
            scanJob = viewModelScope.launch(Dispatchers.IO) {
                try {
                    withContext(Dispatchers.Main) {
                        _scanState.value = ScanState.Scanning(ScanProgress(0, previous.size, 0))
                    }
                    val allResults = mutableListOf<ScanResult>()
                    val byType = previous.groupBy { it.valueType }
                    var processed = 0
                    for ((type, items) in byType) {
                        if (!isActive) break
                        while (Scanner.paused) {
                            delay(100); if (!isActive) break
                        }
                        val found = Scanner.comparisonScan(pid, items, op, type, operand1, operand2) { p ->
                            if (isActive) {
                                viewModelScope.launch(Dispatchers.Main) {
                                    _scanState.value = ScanState.Scanning(
                                        ScanProgress(processed + p.scannedRegions, previous.size, allResults.size + p.foundCount)
                                    )
                                }
                            }
                        }
                        allResults.addAll(found)
                        processed += items.size
                    }
                    if (isActive) {
                        withContext(Dispatchers.Main) {
                            _results.value = allResults
                            _scanState.value = ScanState.Done(allResults)
                        }
                    }
                } catch (e: Exception) {
                    if (isActive) {
                        withContext(Dispatchers.Main) {
                            _scanState.value = ScanState.Error(e.message ?: "Comparison failed")
                        }
                    }
                }
            }
            return
        }

        scanJob?.cancel()
        scanJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val found = Scanner.comparisonScan(
                    pid, previous, op, selectedValueType, operand1, operand2
                ) { p ->
                    if (isActive) {
                        viewModelScope.launch(Dispatchers.Main) {
                            _scanState.value = ScanState.Scanning(p)
                        }
                    }
                }
                if (isActive) {
                    withContext(Dispatchers.Main) {
                        _results.value = found
                        _scanState.value = ScanState.Done(found)
                    }
                }
            } catch (e: Exception) {
                if (isActive) {
                    withContext(Dispatchers.Main) {
                        _scanState.value = ScanState.Error(e.message ?: "Comparison failed")
                    }
                }
            }
        }
    }

    @Volatile
    private var isRefreshing = false

    fun refreshValues() {
        val pid = requireSelectedPid() ?: return
        if (_results.value.isEmpty()) return
        if (isRefreshing) return
        if (_scanState.value is ScanState.Scanning) return
        isRefreshing = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val snapshot = _results.value
                if (!isCurrentProcess(pid) || snapshot.isEmpty()) return@launch
                val refreshed = Scanner.refreshValues(pid, snapshot)
                withContext(Dispatchers.Main) {
                    if (isCurrentProcess(pid)) _results.value = refreshed
                }
            } finally {
                isRefreshing = false
            }
        }
    }

    // ---- Writing ----

    fun writeBulk(addresses: List<Long>, newValue: String) {
        val pid = selectedProcess.value?.pid ?: return
        val service = freezeService
        val bytes = ValueEncoder.encodeWriteValue(newValue, selectedValueType, xorKey) ?: return
        val addrSet = addresses.toSet()
        viewModelScope.launch(Dispatchers.IO) {
            val writes = addresses.map { it to bytes }
            MemoryWriter.writeBytesMany(pid, writes)
            // Update frozen bytes so freeze loop writes the new value
            if (service != null) {
                for (addr in addresses) {
                    if (service.isFrozen(addr)) service.updateFreezeBytes(addr, bytes)
                }
            }
            withContext(Dispatchers.Main) {
                _results.value = _results.value.map { r ->
                    if (r.address in addrSet) r.copy(
                        previousBytes = bytes.copyOf(),
                        currentBytes = bytes.copyOf(),
                        changeDirection = ChangeDirection.NONE,
                        deltaDisplay = ""
                    ) else r
                }
            }
        }
    }

    fun writeAddress(address: Long, newValue: String) {
        writeBulk(listOf(address), newValue)
    }

    fun toggleFreeze(result: ScanResult) {
        val pid = selectedProcess.value?.pid ?: return
        val service = freezeService ?: return
        if (result.frozen) service.removeFreeze(result.address)
        else service.addFreeze(pid, result.address, result.currentBytes)
        _results.value = _results.value.map {
            if (it.address == result.address) it.copy(frozen = !it.frozen) else it
        }
    }

    fun bulkFreezeSelected(freeze: Boolean) {
        val pid = selectedProcess.value?.pid ?: return
        val service = freezeService ?: return
        val selected = _results.value.filter { it.selected }
        for (r in selected) {
            if (freeze && !r.frozen) service.addFreeze(pid, r.address, r.currentBytes)
            else if (!freeze && r.frozen) service.removeFreeze(r.address)
        }
        val addrSet = selected.map { it.address }.toSet()
        _results.value = _results.value.map {
            if (it.address in addrSet) it.copy(frozen = freeze) else it
        }
    }

    fun writeBulkAndFreeze(addresses: List<Long>, newValue: String) {
        val pid = selectedProcess.value?.pid ?: return
        val service = freezeService ?: return
        val bytes = ValueEncoder.encodeWriteValue(newValue, selectedValueType, xorKey) ?: return
        val addrSet = addresses.toSet()
        viewModelScope.launch(Dispatchers.IO) {
            val writes = addresses.map { it to bytes }
            MemoryWriter.writeBytesMany(pid, writes)
            for (addr in addresses) {
                service.addFreeze(pid, addr, bytes)
            }
            withContext(Dispatchers.Main) {
                _results.value = _results.value.map { r ->
                    if (r.address in addrSet) r.copy(
                        previousBytes = bytes.copyOf(),
                        currentBytes = bytes.copyOf(),
                        frozen = true,
                        changeDirection = ChangeDirection.NONE,
                        deltaDisplay = ""
                    ) else r
                }
            }
        }
    }

    fun toggleSelected(address: Long) {
        val list = _results.value
        val idx = list.indexOfFirst { it.address == address }
        if (idx < 0) return
        _results.value = list.toMutableList().also { it[idx] = it[idx].copy(selected = !it[idx].selected) }
    }

    fun selectAll(select: Boolean) {
        val list = _results.value
        if (list.all { it.selected == select }) return
        _results.value = list.map { it.copy(selected = select) }
    }

    fun removeResult(address: Long) {
        _results.value = _results.value.filter { it.address != address }
    }

    fun removeSelected() {
        _results.value = _results.value.filter { !it.selected }
    }

    // ---- Lifecycle ----

    fun cancelScan() {
        scanJob?.cancel()
        Scanner.paused = false
        _isPaused.value = false
        _scanState.value = ScanState.Idle
    }

    fun resetScan() {
        scanJob?.cancel()
        Scanner.paused = false
        _isPaused.value = false
        _results.value = emptyList()
        _scanState.value = ScanState.Idle
    }

    fun togglePause() {
        Scanner.paused = !Scanner.paused
        _isPaused.value = Scanner.paused
    }

    // ---- Cheat tables ----

    fun tablesDir(): File {
        val base = AppLogger.filesDir ?: return File("/data/local/tmp/androce_tables")
        return File(base, "tables").apply { mkdirs() }
    }

    fun listSavedTables(): List<String> = tablesDir().listFiles { f -> f.extension == "json" }
        ?.map { it.nameWithoutExtension }?.sorted() ?: emptyList()

    fun saveCheatTable(name: String): Boolean {
        return saveCheatTable(name, _results.value.map { it.address })
    }

    fun saveCheatTable(name: String, addresses: List<Long>): Boolean {
        return try {
            val safe = name.ifBlank { "table_${System.currentTimeMillis()}" }
                .replace(Regex("[^A-Za-z0-9_-]"), "_")
            val addressSet = addresses.toSet()
            val entries = _results.value.filter { it.address in addressSet }.map { r ->
                CheatTableEntry(
                    address = r.address,
                    label = "",
                    valueType = r.valueType,
                    frozen = r.frozen,
                    frozenValueHex = if (r.frozen) r.currentBytes.joinToString("") {
                        "%02x".format(
                            it
                        )
                    } else null
                )
            }
            val table = CheatTable(
                processName = selectedProcess.value?.name ?: "unknown",
                savedAt = System.currentTimeMillis(),
                entries = entries
            )
            File(tablesDir(), "$safe.json").writeText(table.toJson())
            true
        } catch (e: Exception) {
            AppLogger.e("ScanViewModel", "saveCheatTable failed", e); false
        }
    }

    fun loadCheatTable(name: String): Int {
        return try {
            val file = File(tablesDir(), "$name.json")
            if (!file.exists()) return 0
            val table = CheatTable.fromJson(file.readText())
            val pid = selectedProcess.value?.pid
            // Reconstruct ScanResults; if PID matches, refresh current values.
            val rebuilt = table.entries.map { e ->
                val bytes = e.frozenValueHex?.let { hex ->
                    ByteArray(hex.length / 2) { i ->
                        hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
                    }
                } ?: ByteArray(if (e.valueType.byteSize > 0) e.valueType.byteSize else 4)
                ScanResult(
                    address = e.address,
                    valueType = e.valueType,
                    currentBytes = bytes,
                    frozen = e.frozen
                )
            }
            _results.value = rebuilt
            if (pid != null) {
                viewModelScope.launch {
                    val refreshed = Scanner.refreshValues(pid, rebuilt)
                    _results.value = refreshed.toList()
                }
            }
            rebuilt.size
        } catch (e: Exception) {
            AppLogger.e("ScanViewModel", "loadCheatTable failed", e); 0
        }
    }

    fun deleteCheatTable(name: String): Boolean =
        File(tablesDir(), "$name.json").delete()

    fun getCheatTableInfo(name: String): CheatTableMeta? {
        return try {
            val file = File(tablesDir(), "$name.json")
            if (!file.exists()) return null
            val json = org.json.JSONObject(file.readText())
            CheatTableMeta(
                processName = json.optString("processName", "unknown"),
                savedAt = json.optLong("savedAt", 0),
                entryCount = json.optJSONArray("entries")?.length() ?: 0
            )
        } catch (e: Exception) { null }
    }

    fun renameCheatTable(oldName: String, newName: String): Boolean {
        val safeNew = newName.replace(Regex("[^A-Za-z0-9_-]"), "_")
        if (safeNew.isBlank()) return false
        return try {
            val oldFile = File(tablesDir(), "$oldName.json")
            val newFile = File(tablesDir(), "$safeNew.json")
            if (!oldFile.exists() || newFile.exists()) return false
            oldFile.renameTo(newFile)
        } catch (e: Exception) {
            AppLogger.e("ScanViewModel", "renameCheatTable failed", e); false
        }
    }

    // ---- Speed Hack ----

    fun initSpeedInjector(context: Context) {
        SpeedInjector.init(context)
    }

    fun activateSpeedHack() {
        val process = selectedProcess.value ?: return
        val pid = process.pid
        val processName = process.name

        val active = SpeedControl.state.value
        if (active.state == SpeedHackState.ACTIVE && active.targetPid != pid) {
            SpeedInjector.reset()
        }

        viewModelScope.launch {
            if (!isCurrentProcess(pid)) return@launch
            SpeedInjector.inject(pid, processName)
        }
    }

    fun updateSpeedHack(speed: Float) {
        SpeedControl.updateSpeed(speed)
        if (!SpeedControl.isActive()) return
        val pid = SpeedControl.state.value.targetPid
        if (pid != requireSelectedPid()) return
        viewModelScope.launch {
            SpeedInjector.updateSpeed(speed)
        }
    }

    fun deactivateSpeedHack() {
        SpeedInjector.reset()
    }

    fun isSpeedHackActive(): Boolean = SpeedControl.isActive()

    fun refreshSpeedHackHealth() {
        if (!SpeedControl.isActive()) return
        viewModelScope.launch {
            SpeedInjector.validateActiveInjection()
        }
    }
}
