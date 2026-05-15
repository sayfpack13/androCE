package com.androce.core

import com.androce.model.MemoryRegion
import com.androce.model.RegionFilter
import com.androce.model.ScanComparison
import com.androce.model.ScanResult
import com.androce.model.ValueType
import com.androce.model.matchesFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class ScanProgress(
    val scannedRegions: Int,
    val totalRegions: Int,
    val foundCount: Int,
    val capped: Boolean = false,
    val skipped: Int = 0
)

object Scanner {

    private const val TAG = "Scanner"
    private const val REGION_CHUNK = 50 // regions per python invocation when chunked

    @Volatile var paused: Boolean = false

    /**
     * First scan: chunks regions to support pause/resume and intermediate progress.
     */
    suspend fun firstScan(
        pid: Int,
        valueType: ValueType,
        pattern: ByteArray,
        wildcard: Byte?,
        regions: List<MemoryRegion>,
        regionFilter: RegionFilter = RegionFilter.HEAP_STACK_ANON,
        onProgress: ((ScanProgress) -> Unit)? = null
    ): List<ScanResult> = withContext(Dispatchers.IO) {
        val filtered = regions.filter { it.matchesFilter(regionFilter) }
        AppLogger.d(TAG, "firstScan pid=$pid type=$valueType regions=${filtered.size}/${regions.size} filter=${regionFilter.label} patternSize=${pattern.size}")
        onProgress?.invoke(ScanProgress(0, filtered.size, 0))

        val allAddrs = mutableListOf<Long>()
        var totalSkipped = 0
        var capped = false
        val cap = MemoryReader.MAX_RESULTS

        val chunks = filtered.chunked(REGION_CHUNK)
        var done = 0
        var lastProgressTime = 0L
        for (chunk in chunks) {
            ensureActive()
            while (paused) { delay(100); ensureActive() }
            val remaining = cap - allAddrs.size
            if (remaining <= 0) { capped = true; break }
            val outcome = MemoryReader.scanAllRegions(pid, chunk, pattern, wildcard, maxResults = remaining)
            allAddrs.addAll(outcome.addresses)
            totalSkipped += outcome.skipped
            if (outcome.capped) capped = true
            done += chunk.size
            val now = System.currentTimeMillis()
            if (now - lastProgressTime > 100 || done == filtered.size) {
                onProgress?.invoke(ScanProgress(done, filtered.size, allAddrs.size, capped, totalSkipped))
                lastProgressTime = now
            }
        }

        // Read current bytes for all matched addresses in a single batch
        val results = if (allAddrs.isEmpty()) emptyList() else {
            val reqs = allAddrs.map { it to pattern.size }
            val bytesList = MemoryReader.readBytesBatch(pid, reqs)
            allAddrs.mapIndexed { idx, addr ->
                val bytes = bytesList[idx] ?: pattern.copyOf()
                ScanResult(addr, valueType, bytes)
            }
        }
        AppLogger.d(TAG, "firstScan done: found=${results.size} capped=$capped skipped=$totalSkipped")
        onProgress?.invoke(ScanProgress(filtered.size, filtered.size, results.size, capped, totalSkipped))
        results
    }

    /**
     * Refined scan: keep results whose bytes still match [pattern]. Single Python process.
     */
    suspend fun refinedScan(
        pid: Int,
        previousResults: List<ScanResult>,
        pattern: ByteArray,
        wildcard: Byte?,
        onProgress: ((ScanProgress) -> Unit)? = null
    ): List<ScanResult> = withContext(Dispatchers.IO) {
        if (previousResults.isEmpty()) return@withContext emptyList()
        onProgress?.invoke(ScanProgress(0, previousResults.size, 0))
        val addrs = previousResults.map { it.address }
        val survivors = MemoryReader.refinedScanBatch(pid, addrs, pattern, wildcard)
        val survivorMap = survivors.toMap()
        val out = previousResults.mapNotNull { prev ->
            val newBytes = survivorMap[prev.address] ?: return@mapNotNull null
            prev.previousBytes = prev.currentBytes.copyOf()
            prev.currentBytes = newBytes
            prev
        }
        onProgress?.invoke(ScanProgress(previousResults.size, previousResults.size, out.size))
        AppLogger.d(TAG, "refinedScan kept=${out.size}/${previousResults.size}")
        out
    }

    /**
     * Comparison scan: filter previous results using [op]. For ops that need an operand (EXACT,
     * INCREASED_BY, DECREASED_BY, BETWEEN), pass [operand1] / [operand2] as decimal strings.
     */
    suspend fun comparisonScan(
        pid: Int,
        previousResults: List<ScanResult>,
        op: ScanComparison,
        valueType: ValueType,
        operand1: String? = null,
        operand2: String? = null,
        onProgress: ((ScanProgress) -> Unit)? = null
    ): List<ScanResult> = withContext(Dispatchers.IO) {
        if (previousResults.isEmpty()) return@withContext emptyList()
        onProgress?.invoke(ScanProgress(0, previousResults.size, 0))
        val tcode = tcodeFor(valueType)
        val items = previousResults.map { Triple(it.address, it.currentBytes, it.currentBytes.size) }
        val survivors = MemoryReader.compareBatch(pid, items, op.name, tcode, operand1, operand2)
        val map = survivors.toMap()
        val out = previousResults.mapNotNull { prev ->
            val newBytes = map[prev.address] ?: return@mapNotNull null
            prev.previousBytes = prev.currentBytes.copyOf()
            prev.currentBytes = newBytes
            prev
        }
        onProgress?.invoke(ScanProgress(previousResults.size, previousResults.size, out.size))
        AppLogger.d(TAG, "comparisonScan op=${op.name} kept=${out.size}/${previousResults.size}")
        out
    }

    /**
     * Unknown-initial-value scan: snapshots every aligned slot of [valueType] in filtered regions.
     */
    suspend fun unknownInitialScan(
        pid: Int,
        valueType: ValueType,
        regions: List<MemoryRegion>,
        regionFilter: RegionFilter = RegionFilter.HEAP_STACK_ANON,
        onProgress: ((ScanProgress) -> Unit)? = null
    ): List<ScanResult> = withContext(Dispatchers.IO) {
        if (valueType.isVariableLength) return@withContext emptyList()
        val filtered = regions.filter { it.matchesFilter(regionFilter) }
        onProgress?.invoke(ScanProgress(0, filtered.size, 0))
        val (items, skipped, capped) = MemoryReader.snapshotScanWithBytes(
            pid, filtered, valueType.byteSize, valueType.byteSize
        )
        val results = items.map { (addr, bytes) -> ScanResult(addr, valueType, bytes) }
        AppLogger.d(TAG, "unknownInitialScan slots=${results.size} skipped=$skipped capped=$capped")
        onProgress?.invoke(ScanProgress(filtered.size, filtered.size, results.size, capped, skipped))
        results
    }

    /**
     * Re-read current values for [results] using a single Python batch.
     */
    suspend fun refreshValues(pid: Int, results: List<ScanResult>): List<ScanResult> =
        withContext(Dispatchers.IO) {
            if (results.isEmpty()) return@withContext results
            val reqs = results.map { it.address to it.currentBytes.size }
            val bytesList = MemoryReader.readBytesBatch(pid, reqs)
            results.forEachIndexed { idx, r ->
                val b = bytesList[idx] ?: return@forEachIndexed
                r.previousBytes = r.currentBytes.copyOf()
                r.currentBytes = b
            }
            results
        }

    private fun tcodeFor(t: ValueType): String = when (t) {
        ValueType.BYTE1 -> "i1"
        ValueType.BYTE2 -> "i2"
        ValueType.BYTE4 -> "i4"
        ValueType.BYTE8 -> "i8"
        ValueType.FLOAT -> "f4"
        ValueType.DOUBLE -> "f8"
        ValueType.XOR4 -> "i4"
        ValueType.XOR8 -> "i8"
        else -> "raw"
    }
}
