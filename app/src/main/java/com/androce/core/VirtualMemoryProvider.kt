package com.androce.core

import com.androce.core.virtual.GuestMemoryClient
import com.androce.model.MemoryRegion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Non-root virtual memory: scans the BlackBox guest via [GuestMemoryClient] (/proc/self in guest).
 */
object VirtualMemoryProvider : MemoryProvider {

    override val mode: String = "virtual"

    override suspend fun getReadableRegions(pid: Int): List<MemoryRegion> {
        return GuestMemoryClient.getRegions()
    }

    override suspend fun readBytes(pid: Int, address: Long, length: Int): ByteArray? =
        GuestMemoryClient.readBytes(address, length)

    override suspend fun readBytesBatch(pid: Int, requests: List<Pair<Long, Int>>): List<ByteArray?> =
        GuestMemoryClient.readBytesBatch(requests)

    override suspend fun scanAllRegions(
        pid: Int,
        regions: List<MemoryRegion>,
        pattern: ByteArray,
        wildcard: Byte?,
        maxResults: Int
    ): MemoryReader.ScanOutcome {
        val (addrs, skipped) = GuestMemoryClient.scanRegions(regions, pattern, wildcard, maxResults)
        return MemoryReader.ScanOutcome(addrs, skipped, addrs.size >= maxResults)
    }

    override suspend fun refinedScanBatch(
        pid: Int,
        addresses: List<Long>,
        pattern: ByteArray,
        wildcard: Byte?
    ): List<Pair<Long, ByteArray>> =
        GuestMemoryClient.refinedScanBatch(addresses, pattern, wildcard)

    override suspend fun compareBatch(
        pid: Int,
        items: List<Triple<Long, ByteArray, Int>>,
        op: String,
        tcode: String,
        operand1: String?,
        operand2: String?,
        onProgress: ((scanned: Int, found: Int) -> Unit)?
    ): List<Pair<Long, ByteArray>> = withContext(Dispatchers.IO) {
        if (items.isEmpty()) return@withContext emptyList()
        val results = mutableListOf<Pair<Long, ByteArray>>()
        var processed = 0
        for (batch in items.chunked(2000)) {
            val reqs = batch.map { it.first to it.third }
            val bytesList = GuestMemoryClient.readBytesBatch(reqs)
            for ((idx, triple) in batch.withIndex()) {
                val (addr, prevBytes, size) = triple
                val newBytes = bytesList[idx] ?: continue
                if (newBytes.size != size) continue
                val keep = when (op) {
                    "CHANGED" -> !newBytes.contentEquals(prevBytes)
                    "UNCHANGED" -> newBytes.contentEquals(prevBytes)
                    else -> MemoryReader.compareNumeric(op, newBytes, prevBytes, operand1, operand2, tcode)
                }
                if (keep) results.add(addr to newBytes)
            }
            processed += batch.size
            onProgress?.invoke(processed, results.size)
        }
        results
    }

    override suspend fun snapshotScanWithBytes(
        pid: Int,
        regions: List<MemoryRegion>,
        slotSize: Int,
        step: Int,
        maxResults: Int
    ): Triple<List<Pair<Long, ByteArray>>, Int, Boolean> = withContext(Dispatchers.IO) {
        val items = mutableListOf<Pair<Long, ByteArray>>()
        var skipped = 0
        for (region in regions) {
            var addr = region.startAddress
            while (addr + slotSize <= region.endAddress && items.size < maxResults) {
                val bytes = GuestMemoryClient.readBytes(addr, slotSize)
                if (bytes != null) {
                    items.add(addr to bytes)
                } else {
                    skipped++
                }
                addr += step
            }
            if (items.size >= maxResults) break
        }
        Triple(items, skipped, items.size >= maxResults)
    }

    override suspend fun writeBytes(pid: Int, address: Long, bytes: ByteArray): Boolean =
        GuestMemoryClient.writeBytes(address, bytes)

    override suspend fun writeBytesBatch(pid: Int, writes: List<Pair<Long, ByteArray>>): Boolean =
        withContext(Dispatchers.IO) {
            writes.all { (addr, data) -> GuestMemoryClient.writeBytes(addr, data) }
        }
}
