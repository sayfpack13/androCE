package com.androce.viewmodel

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.androce.core.FreezeService
import com.androce.core.MemoryReader
import com.androce.core.MemoryWriter
import com.androce.core.Scanner
import com.androce.core.ScanProgress
import com.androce.core.ValueEncoder
import com.androce.model.MemoryRegion
import com.androce.model.ProcessInfo
import com.androce.model.ScanResult
import com.androce.model.ValueType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed class ScanState {
    object Idle : ScanState()
    data class Scanning(val progress: ScanProgress) : ScanState()
    data class Done(val results: List<ScanResult>) : ScanState()
    data class Error(val message: String) : ScanState()
}

class ScanViewModel : ViewModel() {

    var selectedProcess: ProcessInfo? = null
    var selectedValueType: ValueType = ValueType.BYTE4
    var searchInput: String = ""
    var xorKey: Long = 0L

    private val _scanState = MutableStateFlow<ScanState>(ScanState.Idle)
    val scanState: StateFlow<ScanState> = _scanState

    private val _results = MutableStateFlow<List<ScanResult>>(emptyList())
    val results: StateFlow<List<ScanResult>> = _results

    private val _regions = MutableStateFlow<List<MemoryRegion>>(emptyList())
    val regions: StateFlow<List<MemoryRegion>> = _regions

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
        viewModelScope.launch {
            try {
                val r = MemoryReader.getReadableRegions(pid)
                _regions.value = r
            } catch (e: Exception) {
                _regions.value = emptyList()
            }
        }
    }

    fun firstScan() {
        val pid = selectedProcess?.pid ?: return
        val (pattern, wildcard) = try {
            ValueEncoder.encodeSearchValue(searchInput, selectedValueType, xorKey)
        } catch (e: Exception) {
            _scanState.value = ScanState.Error("Invalid input: ${e.message}")
            return
        }
        if (pattern.isEmpty()) {
            _scanState.value = ScanState.Error("Pattern is empty")
            return
        }

        scanJob?.cancel()
        scanJob = viewModelScope.launch(Dispatchers.Main) {
            _scanState.value = ScanState.Scanning(ScanProgress(0, 0, 0))
            delay(32)
            try {
                val regionList = withContext(Dispatchers.IO) {
                    _regions.value.ifEmpty {
                        MemoryReader.getReadableRegions(pid).also { _regions.value = it }
                    }
                }
                val found = Scanner.firstScan(
                    pid, selectedValueType, pattern, wildcard, regionList
                ) { progress ->
                    _scanState.value = ScanState.Scanning(progress)
                }
                _results.value = found
                _scanState.value = ScanState.Done(found)
            } catch (e: Exception) {
                _scanState.value = ScanState.Error(e.message ?: "Scan failed")
            }
        }
    }

    fun refinedScan() {
        val pid = selectedProcess?.pid ?: return
        val previous = _results.value
        if (previous.isEmpty()) {
            firstScan(); return
        }
        val (pattern, wildcard) = try {
            ValueEncoder.encodeSearchValue(searchInput, selectedValueType, xorKey)
        } catch (e: Exception) {
            _scanState.value = ScanState.Error("Invalid input: ${e.message}")
            return
        }

        scanJob?.cancel()
        scanJob = viewModelScope.launch(Dispatchers.Main) {
            _scanState.value = ScanState.Scanning(ScanProgress(0, previous.size, 0))
            delay(32)
            try {
                val found = Scanner.refinedScan(pid, previous, pattern, wildcard) { p ->
                    _scanState.value = ScanState.Scanning(p)
                }
                _results.value = found
                _scanState.value = ScanState.Done(found)
            } catch (e: Exception) {
                _scanState.value = ScanState.Error(e.message ?: "Refined scan failed")
            }
        }
    }

    fun refreshValues() {
        val pid = selectedProcess?.pid ?: return
        viewModelScope.launch {
            val refreshed = Scanner.refreshValues(pid, _results.value)
            _results.value = refreshed.toMutableList()
        }
    }

    fun writeBulk(addresses: List<Long>, newValue: String) {
        val pid = selectedProcess?.pid ?: return
        val bytes = ValueEncoder.encodeWriteValue(newValue, selectedValueType, xorKey) ?: return
        viewModelScope.launch {
            for (addr in addresses) {
                MemoryWriter.writeBytes(pid, addr, bytes)
            }
            val updated = _results.value.map { r ->
                if (r.address in addresses) r.copy(currentBytes = bytes.copyOf()) else r
            }
            _results.value = updated
        }
    }

    fun writeAddress(address: Long, newValue: String) {
        writeBulk(listOf(address), newValue)
    }

    fun toggleFreeze(result: ScanResult) {
        val pid = selectedProcess?.pid ?: return
        val service = freezeService ?: return
        if (result.frozen) {
            service.removeFreeze(result.address)
        } else {
            service.addFreeze(pid, result.address, result.currentBytes)
        }
        val updated = _results.value.map {
            if (it.address == result.address) it.copy(frozen = !it.frozen) else it
        }
        _results.value = updated
    }

    fun toggleSelected(address: Long) {
        val updated = _results.value.map {
            if (it.address == address) it.copy(selected = !it.selected) else it
        }
        _results.value = updated
    }

    fun selectAll(select: Boolean) {
        val updated = _results.value.map { it.copy(selected = select) }
        _results.value = updated
    }

    fun removeResult(address: Long) {
        _results.value = _results.value.filter { it.address != address }
    }

    fun cancelScan() {
        scanJob?.cancel()
        _scanState.value = ScanState.Idle
    }

    fun resetScan() {
        scanJob?.cancel()
        _results.value = emptyList()
        _scanState.value = ScanState.Idle
    }
}
