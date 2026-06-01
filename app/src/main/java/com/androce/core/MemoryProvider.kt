package com.androce.core

import com.androce.model.MemoryRegion

/**
 * Abstraction over memory access — root shell vs virtual container (same-process).
 * All consumers (Scanner, FreezeService, etc.) use this interface so the mode is transparent.
 */
interface MemoryProvider {

    val mode: String // "root" or "virtual"

    /** Enumerate readable user-memory regions for [pid]. In virtual mode, pid is ignored (always self). */
    suspend fun getReadableRegions(pid: Int): List<MemoryRegion>

    /** Read [length] bytes from [pid] at [address]. Returns null on failure. */
    suspend fun readBytes(pid: Int, address: Long, length: Int): ByteArray?

    /** Batch read — aligned with [requests], nulls for failed entries. */
    suspend fun readBytesBatch(pid: Int, requests: List<Pair<Long, Int>>): List<ByteArray?>

    /** Scan all [regions] for [pattern] with optional [wildcard]. */
    suspend fun scanAllRegions(
        pid: Int,
        regions: List<MemoryRegion>,
        pattern: ByteArray,
        wildcard: Byte? = null,
        maxResults: Int = MemoryReader.MAX_RESULTS
    ): MemoryReader.ScanOutcome

    /** Refined scan: keep addresses whose bytes still match [pattern]. */
    suspend fun refinedScanBatch(
        pid: Int,
        addresses: List<Long>,
        pattern: ByteArray,
        wildcard: Byte? = null
    ): List<Pair<Long, ByteArray>>

    /** Comparison batch: typed comparisons against previously-known bytes. */
    suspend fun compareBatch(
        pid: Int,
        items: List<Triple<Long, ByteArray, Int>>,
        op: String,
        tcode: String,
        operand1: String? = null,
        operand2: String? = null,
        onProgress: ((scanned: Int, found: Int) -> Unit)? = null
    ): List<Pair<Long, ByteArray>>

    /** Snapshot scan for "unknown initial value". */
    suspend fun snapshotScanWithBytes(
        pid: Int,
        regions: List<MemoryRegion>,
        slotSize: Int,
        step: Int = slotSize,
        maxResults: Int = MemoryReader.MAX_RESULTS
    ): Triple<List<Pair<Long, ByteArray>>, Int, Boolean>

    /** Write [bytes] to [pid] at [address]. */
    suspend fun writeBytes(pid: Int, address: Long, bytes: ByteArray): Boolean

    /** Batch write — single process for any number of (address, bytes) pairs. */
    suspend fun writeBytesBatch(pid: Int, writes: List<Pair<Long, ByteArray>>): Boolean
}
