package com.androce.core

import com.androce.model.MemoryRegion
import com.androce.model.ScanResult
import com.androce.model.ValueType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class ScanProgress(
    val scannedRegions: Int,
    val totalRegions: Int,
    val foundCount: Int
)

object Scanner {

    /**
     * First scan: scan all readable regions for [pattern].
     */
    suspend fun firstScan(
        pid: Int,
        valueType: ValueType,
        pattern: ByteArray,
        wildcard: Byte?,
        regions: List<MemoryRegion>,
        onProgress: ((ScanProgress) -> Unit)? = null
    ): List<ScanResult> = withContext(Dispatchers.IO) {
        val results = mutableListOf<ScanResult>()

        for ((index, region) in regions.withIndex()) {
            ensureActive()
            onProgress?.invoke(ScanProgress(index, regions.size, results.size))

            val addresses = MemoryReader.scanRegion(pid, region, pattern, wildcard)
            for (addr in addresses) {
                val bytes = MemoryReader.readBytes(pid, addr, pattern.size)
                    ?: pattern.copyOf()
                results.add(ScanResult(addr, valueType, bytes))
            }
        }
        onProgress?.invoke(ScanProgress(regions.size, regions.size, results.size))
        results
    }

    /**
     * Refined scan: re-read addresses from previous scan and keep only those
     * still matching [pattern].
     */
    suspend fun refinedScan(
        pid: Int,
        previousResults: List<ScanResult>,
        pattern: ByteArray,
        wildcard: Byte?,
        onProgress: ((ScanProgress) -> Unit)? = null
    ): List<ScanResult> = withContext(Dispatchers.IO) {
        val results = mutableListOf<ScanResult>()
        val total = previousResults.size

        for ((index, prev) in previousResults.withIndex()) {
            ensureActive()
            if (index % 500 == 0) {
                onProgress?.invoke(ScanProgress(index, total, results.size))
            }

            val bytes = MemoryReader.readBytes(pid, prev.address, pattern.size) ?: continue
            if (matchesPattern(bytes, pattern, wildcard)) {
                prev.currentBytes = bytes
                results.add(prev)
            }
        }
        onProgress?.invoke(ScanProgress(total, total, results.size))
        results
    }

    /**
     * Re-read current values for a list of results.
     */
    suspend fun refreshValues(
        pid: Int,
        results: List<ScanResult>
    ): List<ScanResult> = withContext(Dispatchers.IO) {
        results.forEach { r ->
            val bytes = MemoryReader.readBytes(pid, r.address, r.currentBytes.size)
            if (bytes != null) r.currentBytes = bytes
        }
        results
    }

    private fun matchesPattern(data: ByteArray, pattern: ByteArray, wildcard: Byte?): Boolean {
        if (data.size < pattern.size) return false
        for (i in pattern.indices) {
            if (wildcard != null && pattern[i] == wildcard) continue
            if (data[i] != pattern[i]) return false
        }
        return true
    }
}
