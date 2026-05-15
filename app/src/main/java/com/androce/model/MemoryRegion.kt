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
        val n = name.trim()
        // Always include core data regions
        if (n.isEmpty() || n == "[heap]" || n == "[stack]") return true
        // Include labeled anonymous regions (Dalvik heap, etc.)
        if (n.startsWith("[anon:") || n == "[anon]") return true
        // Exclude mapped files — libraries, dex, art
        if (n.endsWith(".so") || n.endsWith(".apk") || n.endsWith(".dex")
            || n.endsWith(".oat") || n.endsWith(".art") || n.endsWith(".jar")
            || n.endsWith(".vdex") || n.endsWith(".odex")) return false
        // Exclude device/driver mappings
        if (n.startsWith("/dev/") || n.startsWith("/memfd") || n.startsWith("/dmabuf")) return false
        // Exclude system pipes and sockets
        if (n.startsWith("pipe:") || n.startsWith("socket:") || n.startsWith("anon_inode:")) return false
        // Exclude pure [anon] regions over 256MB — likely GPU/driver
        if (n == "[anon]" || n.isEmpty()) {
            if (size > 256L * 1024 * 1024) return false
        }
        return true
    }
}
