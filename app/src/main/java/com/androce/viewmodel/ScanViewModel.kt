package com.androce.viewmodel

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.androce.core.AppLogger
import com.androce.core.FreezeService
import com.androce.core.MemoryReader
import com.androce.core.MemoryWriter
import com.androce.core.ScanProgress
import com.androce.core.Scanner
import com.androce.core.ValueEncoder
import com.androce.model.CheatTable
import com.androce.model.CheatTableEntry
import com.androce.model.MemoryRegion
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
import kotlinx.coroutines.withContext
import java.io.File

sealed class ScanState {
    object Idle : ScanState()
    data class Scanning(val progress: ScanProgress) : ScanState()
    data class Done(val results: List<ScanResult>) : ScanState()
    data class Error(val message: String) : ScanState()
}

class ScanViewModel : ViewModel() {

    private var _selectedProcess: ProcessInfo? = null
    var selectedProcess: ProcessInfo?
        get() = _selectedProcess
        set(value) {
            val changed = _selectedProcess?.pid != value?.pid
            _selectedProcess = value
            if (changed) {
                // Invalidate everything tied to the old PID
                scanJob?.cancel()
                _regions.value = emptyList()
                _results.value = emptyList()
                _scanState.value = ScanState.Idle
                Scanner.paused = false
            }
        }

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
    }

    fun loadRegions() {
        val pid = selectedProcess?.pid ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val regions = MemoryReader.getReadableRegions(pid)
                withContext(Dispatchers.Main) { _regions.value = regions }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) { _regions.value = emptyList() }
            }
        }
    }

    // ---- Scans ----

    fun firstScan() {
        val pid = selectedProcess?.pid ?: return

        if (selectedValueType == com.androce.model.ValueType.ALL) {
            // Scan across all numeric types
            scanJob?.cancel()
            scanJob = viewModelScope.launch(Dispatchers.IO) {
                try {
                    withContext(Dispatchers.Main) {
                        _scanState.value = ScanState.Scanning(ScanProgress(0, 0, 0))
                    }

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
                            regions = _regions.value,
                            regionFilter = _regionFilter.value,
                            onProgress = { progress ->
                                if (isActive) {
                                    viewModelScope.launch(Dispatchers.Main) {
                                        _scanState.value =
                                            ScanState.Scanning(progress.copy(foundCount = allResults.size + progress.foundCount))
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
            // Normal single-type scan
            val (pattern, wildcard) = try {
                ValueEncoder.encodeSearchValue(searchInput, selectedValueType, xorKey)
            } catch (e: Exception) {
                viewModelScope.launch(Dispatchers.Main) {
                    _scanState.value = ScanState.Error(e.message ?: "Invalid value")
                }
                return
            }

            AppLogger.d(
                "ScanViewModel",
                "firstScan: input='$searchInput' type=$selectedValueType xorKey=$xorKey pattern=${
                    pattern.joinToString(" ") { "%02x".format(it) }
                }"
            )

            scanJob?.cancel()
            scanJob = viewModelScope.launch(Dispatchers.IO) {
                try {
                    withContext(Dispatchers.Main) {
                        _scanState.value = ScanState.Scanning(ScanProgress(0, 0, 0))
                    }
                    val results = Scanner.firstScan(
                        pid = pid,
                        valueType = selectedValueType,
                        pattern = pattern,
                        wildcard = wildcard,
                        regions = _regions.value,
                        regionFilter = _regionFilter.value,
                        onProgress = { progress ->
                            if (isActive) {
                                viewModelScope.launch(Dispatchers.Main) {
                                    _scanState.value = ScanState.Scanning(progress)
                                }
                            }
                        }
                    )
                    if (isActive) {
                        withContext(Dispatchers.Main) {
                            _results.value = results
                            _scanState.value = ScanState.Done(results)
                        }
                    }
                } catch (e: Exception) {
                    if (isActive) {
                        withContext(Dispatchers.Main) {
                            _scanState.value = ScanState.Error(e.message ?: "Scan failed")
                        }
                    }
                }
            }
        }
    }

    fun unknownInitialScan() {
        val pid = selectedProcess?.pid ?: return

        if (selectedValueType == com.androce.model.ValueType.ALL) {
            // Snapshot all numeric types
            scanJob?.cancel()
            Scanner.paused = false
            _isPaused.value = false
            scanJob = viewModelScope.launch(Dispatchers.IO) {
                try {
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
                            pid, type, _regions.value, _regionFilter.value
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
                    val found = Scanner.unknownInitialScan(
                        pid, selectedValueType, _regions.value, _regionFilter.value
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
        val pid = selectedProcess?.pid ?: return
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

        val (pattern, wildcard) = try {
            ValueEncoder.encodeSearchValue(searchInput, selectedValueType, xorKey)
        } catch (e: Exception) {
            _scanState.value = ScanState.Error("Invalid input: ${e.message}"); return
        }
        scanJob?.cancel()
        scanJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val found = Scanner.refinedScan(pid, previous, pattern, wildcard) { p ->
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
        val pid = selectedProcess?.pid ?: return
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
        val pid = selectedProcess?.pid ?: return
        if (isRefreshing) return
        isRefreshing = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val refreshed = Scanner.refreshValues(pid, _results.value)
                withContext(Dispatchers.Main) { _results.value = refreshed }
            } finally {
                isRefreshing = false
            }
        }
    }

    // ---- Writing ----

    fun writeBulk(addresses: List<Long>, newValue: String) {
        val pid = selectedProcess?.pid ?: return
        val bytes = ValueEncoder.encodeWriteValue(newValue, selectedValueType, xorKey) ?: return
        val addrSet = addresses.toSet()
        viewModelScope.launch(Dispatchers.IO) {
            val writes = addresses.map { it to bytes }
            MemoryWriter.writeBytesMany(pid, writes)
            withContext(Dispatchers.Main) {
                _results.value = _results.value.map { r ->
                    if (r.address in addrSet) r.copy(currentBytes = bytes.copyOf()) else r
                }
            }
        }
    }

    fun writeAddress(address: Long, newValue: String) {
        writeBulk(listOf(address), newValue)
    }

    fun toggleFreeze(result: ScanResult) {
        val pid = selectedProcess?.pid ?: return
        val service = freezeService ?: return
        if (result.frozen) service.removeFreeze(result.address)
        else service.addFreeze(pid, result.address, result.currentBytes)
        _results.value = _results.value.map {
            if (it.address == result.address) it.copy(frozen = !it.frozen) else it
        }
    }

    fun toggleSelected(address: Long) {
        _results.value = _results.value.map {
            if (it.address == address) it.copy(selected = !it.selected) else it
        }
    }

    fun selectAll(select: Boolean) {
        _results.value = _results.value.map { it.copy(selected = select) }
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
        return try {
            val safe = name.ifBlank { "table_${System.currentTimeMillis()}" }
                .replace(Regex("[^A-Za-z0-9_-]"), "_")
            val entries = _results.value.map { r ->
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
                processName = selectedProcess?.name ?: "unknown",
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
            val pid = selectedProcess?.pid
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
}