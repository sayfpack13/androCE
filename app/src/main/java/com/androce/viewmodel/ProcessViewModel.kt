package com.androce.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.androce.core.ProcessLister
import com.androce.model.ProcessInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    val filteredProcesses: StateFlow<List<ProcessInfo>> = combine(_allProcesses, searchQuery) { all, q ->
        if (q.isBlank()) all
        else all.filter {
            it.name.contains(q, ignoreCase = true) ||
                it.packageName.contains(q, ignoreCase = true) ||
                it.pid.toString().contains(q)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun loadProcesses() {
        viewModelScope.launch(Dispatchers.Main) {
            _state.value = ProcessListState.Loading
            delay(32) // let Compose render the spinner frame before blocking IO starts
            try {
                val processes = withContext(Dispatchers.IO) { ProcessLister.listProcesses() }
                _allProcesses.value = processes
                _state.value = ProcessListState.Success(processes)
            } catch (e: Exception) {
                _state.value = ProcessListState.Error(e.message ?: "Unknown error")
            }
        }
    }
}
