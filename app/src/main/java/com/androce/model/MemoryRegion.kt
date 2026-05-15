package com.androce.model

data class MemoryRegion(
    val startAddress: Long,
    val endAddress: Long,
    val permissions: String,
    val name: String
) {
    val size: Long get() = endAddress - startAddress
    val isReadable: Boolean get() = permissions.length >= 1 && permissions[0] == 'r'
    val isUserMemory: Boolean get() {
        if (name.isBlank()) return true // anonymous — always include
        val n = name.trim()
        if (n == "[heap]" || n == "[stack]" || n.startsWith("[anon")) return true
        if (n.endsWith(".so") || n.endsWith(".apk") || n.endsWith(".dex")
            || n.endsWith(".oat") || n.endsWith(".art") || n.endsWith(".jar")
            || n.endsWith(".vdex") || n.endsWith(".odex")) return false
        return true
    }
}
