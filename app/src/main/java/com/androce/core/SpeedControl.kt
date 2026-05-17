package com.androce.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class SpeedHackState {
    IDLE,
    INJECTING,
    ACTIVE,
    FAILED
}

data class SpeedControlState(
    val state: SpeedHackState = SpeedHackState.IDLE,
    val speedMultiplier: Float = 1.0f,
    val targetPid: Int = -1,
    val targetProcessName: String = "",
    val errorMessage: String? = null
)

object SpeedControl {
    private val _state = MutableStateFlow(SpeedControlState())
    val state: StateFlow<SpeedControlState> = _state

    const val MIN_SPEED = 0.1f
    const val MAX_SPEED = 10.0f
    const val DEFAULT_SPEED = 1.0f

    fun updateState(newState: SpeedControlState) {
        _state.value = newState
    }

    fun updateSpeed(speed: Float) {
        val clamped = speed.coerceIn(MIN_SPEED, MAX_SPEED)
        _state.value = _state.value.copy(speedMultiplier = clamped)
    }

    fun setActive(pid: Int, processName: String) {
        _state.value = _state.value.copy(
            state = SpeedHackState.ACTIVE,
            targetPid = pid,
            targetProcessName = processName
        )
    }

    fun setInjecting() {
        _state.value = _state.value.copy(state = SpeedHackState.INJECTING)
    }

    fun setFailed(error: String) {
        _state.value = _state.value.copy(
            state = SpeedHackState.FAILED,
            errorMessage = error
        )
    }

    fun reset() {
        _state.value = SpeedControlState()
    }

    fun isActive(): Boolean = _state.value.state == SpeedHackState.ACTIVE

    fun getSpeedString(): String = String.format("%.1fx", _state.value.speedMultiplier)
}
