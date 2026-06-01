package com.androce.model

data class ProcessInfo(
    val pid: Int,
    val name: String,
    val packageName: String,
    val appName: String? = null,
    val isVirtual: Boolean = false
) {
    fun displayName(): String = appName?.takeIf { it.isNotBlank() } ?: name
}
