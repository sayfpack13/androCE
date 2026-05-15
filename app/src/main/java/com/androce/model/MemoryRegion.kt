package com.androce.model

data class MemoryRegion(
    val startAddress: Long,
    val endAddress: Long,
    val permissions: String,
    val name: String
) {
    val size: Long get() = endAddress - startAddress
    val isReadable: Boolean get() = permissions.length >= 1 && permissions[0] == 'r'
}
