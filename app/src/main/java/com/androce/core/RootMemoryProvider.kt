package com.androce.core

import com.androce.model.MemoryRegion

/**
 * Root-mode memory provider — delegates to the existing MemoryReader / MemoryWriter
 * which use libsu shell commands and the native memscan binary.
 */
object RootMemoryProvider : MemoryProvider {

    override val mode: String = "root"

    override suspend fun getReadableRegions(pid: Int): List<MemoryRegion> =
        MemoryReader.getReadableRegions(pid)

    override suspend fun readBytes(pid: Int, address: Long, length: Int): ByteArray? =
        MemoryReader.readBytes(pid, address, length)

    override suspend fun readBytesBatch(pid: Int, requests: List<Pair<Long, Int>>): List<ByteArray?> =
        MemoryReader.readBytesBatch(pid, requests)

    override suspend fun scanAllRegions(
        pid: Int,
        regions: List<MemoryRegion>,
        pattern: ByteArray,
        wildcard: Byte?,
        maxResults: Int
    ): MemoryReader.ScanOutcome =
        MemoryReader.scanAllRegions(pid, regions, pattern, wildcard, maxResults)

    override suspend fun refinedScanBatch(
        pid: Int,
        addresses: List<Long>,
        pattern: ByteArray,
        wildcard: Byte?
    ): List<Pair<Long, ByteArray>> =
        MemoryReader.refinedScanBatch(pid, addresses, pattern, wildcard)

    override suspend fun compareBatch(
        pid: Int,
        items: List<Triple<Long, ByteArray, Int>>,
        op: String,
        tcode: String,
        operand1: String?,
        operand2: String?,
        onProgress: ((scanned: Int, found: Int) -> Unit)?
    ): List<Pair<Long, ByteArray>> =
        MemoryReader.compareBatch(pid, items, op, tcode, operand1, operand2, onProgress)

    override suspend fun snapshotScanWithBytes(
        pid: Int,
        regions: List<MemoryRegion>,
        slotSize: Int,
        step: Int,
        maxResults: Int
    ): Triple<List<Pair<Long, ByteArray>>, Int, Boolean> =
        MemoryReader.snapshotScanWithBytes(pid, regions, slotSize, step, maxResults)

    override suspend fun writeBytes(pid: Int, address: Long, bytes: ByteArray): Boolean =
        MemoryWriter.writeBytes(pid, address, bytes)

    override suspend fun writeBytesBatch(pid: Int, writes: List<Pair<Long, ByteArray>>): Boolean =
        MemoryWriter.writeBytesMany(pid, writes)
}
