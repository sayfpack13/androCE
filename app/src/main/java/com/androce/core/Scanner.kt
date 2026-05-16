package com.androce.core

import com.androce.model.ChangeDirection
import com.androce.model.MemoryRegion
import com.androce.model.RegionFilter
import com.androce.model.ScanComparison
import com.androce.model.ScanResult
import com.androce.model.ValueType
import com.androce.model.bytesToLong
import com.androce.model.bytesToInt
import com.androce.model.bytesToShort
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
        AppLogger.d(TAG, "refinedScan: pid=$pid count=${previousResults.size} pattern=${pattern.joinToString(" ") { "%02x".format(it) }} wildcard=${wildcard?.let { "%02x".format(it) }}")
        onProgress?.invoke(ScanProgress(0, previousResults.size, 0))
        val addrs = previousResults.map { it.address }
        val survivors = MemoryReader.refinedScanBatch(pid, addrs, pattern, wildcard)
        val survivorMap = survivors.toMap()
        val out = previousResults.mapNotNull { prev ->
            val newBytes = survivorMap[prev.address] ?: return@mapNotNull null
            // Reset baseline to the refined scan result — deep-copy to avoid shared references
            prev.copy(
                previousBytes = newBytes.copyOf(),
                currentBytes = newBytes.copyOf(),
                changeDirection = ChangeDirection.NONE,
                deltaDisplay = ""
            )
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
        // Use previousBytes (scan baseline) as old value so INCREASED_BY/DECREASED_BY
        // compares against the same baseline the user sees in the delta display.
        val items = previousResults.map { r ->
            Triple(r.address, r.previousBytes, r.currentBytes.size)
        }
        val survivors = MemoryReader.compareBatch(
            pid, items, op.name, tcode, operand1, operand2
        ) { scanned, found ->
            onProgress?.invoke(ScanProgress(scanned, previousResults.size, found))
        }
        val map = survivors.toMap()
        val out = previousResults.mapNotNull { prev ->
            val newBytes = map[prev.address] ?: return@mapNotNull null
            // Deep-copy to avoid shared ByteArray references
            prev.copy(
                previousBytes = newBytes.copyOf(),
                currentBytes = newBytes.copyOf(),
                changeDirection = ChangeDirection.NONE,
                deltaDisplay = ""
            )
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
            results.mapIndexed { idx, r ->
                val raw = bytesList[idx]
                if (raw == null) {
                    return@mapIndexed r.copy(changeDirection = ChangeDirection.NONE, deltaDisplay = "")
                }
                val bytes: ByteArray = raw
                // previousBytes is the scan baseline — set at scan time, never overwritten by refresh
                val baseline = r.previousBytes
                var dir = ChangeDirection.NONE
                var delta = ""
                if (!bytes.contentEquals(baseline)) {
                    val cmp = compareNumericValues(r.valueType, baseline, bytes)
                    dir = when {
                        cmp > 0 -> ChangeDirection.UP
                        cmp < 0 -> ChangeDirection.DOWN
                        else -> ChangeDirection.NONE
                    }
                    if (cmp != 0) {
                        val d = numericDelta(r.valueType, baseline, bytes)
                        if (d != null) delta = (if (cmp > 0) "+" else "") + d
                    }
                }
                r.copy(
                    currentBytes = bytes,
                    // previousBytes stays as scan baseline
                    changeDirection = dir,
                    deltaDisplay = delta
                )
            }
        }

    private fun compareNumericValues(type: ValueType, old: ByteArray, new: ByteArray): Int {
        return try {
            when (type) {
                ValueType.BYTE1 -> (new[0].toInt() and 0xFF).compareTo(old[0].toInt() and 0xFF)
                ValueType.BYTE2 -> bytesToShort(new).compareTo(bytesToShort(old))
                ValueType.BYTE4, ValueType.XOR4 -> bytesToInt(new).compareTo(bytesToInt(old))
                ValueType.BYTE8, ValueType.XOR8 -> bytesToLong(new).compareTo(bytesToLong(old))
                ValueType.FLOAT -> java.lang.Float.intBitsToFloat(bytesToInt(new))
                    .compareTo(java.lang.Float.intBitsToFloat(bytesToInt(old)))
                ValueType.DOUBLE -> java.lang.Double.longBitsToDouble(bytesToLong(new))
                    .compareTo(java.lang.Double.longBitsToDouble(bytesToLong(old)))
                else -> 0
            }
        } catch (_: Exception) { 0 }
    }

    private fun numericDelta(type: ValueType, old: ByteArray, new: ByteArray): String? {
        return try {
            when (type) {
                ValueType.BYTE1 -> ((new[0].toInt() and 0xFF) - (old[0].toInt() and 0xFF)).toString()
                ValueType.BYTE2 -> (bytesToShort(new) - bytesToShort(old)).toString()
                ValueType.BYTE4, ValueType.XOR4 -> (bytesToInt(new) - bytesToInt(old)).toString()
                ValueType.BYTE8, ValueType.XOR8 -> (bytesToLong(new) - bytesToLong(old)).toString()
                ValueType.FLOAT -> String.format("%.3f",
                    java.lang.Float.intBitsToFloat(bytesToInt(new)) -
                    java.lang.Float.intBitsToFloat(bytesToInt(old)))
                ValueType.DOUBLE -> String.format("%.3f",
                    java.lang.Double.longBitsToDouble(bytesToLong(new)) -
                    java.lang.Double.longBitsToDouble(bytesToLong(old)))
                else -> null
            }
        } catch (_: Exception) { null }
    }

    private fun tcodeFor(t: ValueType): String = when (t) {
        ValueType.BYTE1 -> "u1"
        ValueType.BYTE2 -> "u2"
        ValueType.BYTE4 -> "i4"
        ValueType.BYTE8 -> "i8"
        ValueType.FLOAT -> "f4"
        ValueType.DOUBLE -> "f8"
        ValueType.XOR4 -> "i4"
        ValueType.XOR8 -> "i8"
        else -> "raw"
    }
}
