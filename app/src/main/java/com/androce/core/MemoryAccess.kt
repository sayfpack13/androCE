package com.androce.core

/**
 * Active memory backend for [Scanner] and [ScanViewModel].
 */
object MemoryAccess {
    @Volatile
    var current: MemoryProvider = RootMemoryProvider
}
