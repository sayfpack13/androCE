package com.androce.core.virtual

import android.content.Context
import com.androce.core.AppLogger
import com.androce.model.MemoryRegion
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Guest memory scan/read via /proc/self/mem in-process.
 * Android blocks executing the memscan binary from app-private storage (EACCES).
 */
internal object GuestMemscanRunner {

    private const val TAG = "GuestMemscanRunner"
    private const val MEM_PATH = "/proc/self/mem"
    private const val READ_CHUNK = 4 * 1024 * 1024
    private const val REGION_CHUNK = 50
    private const val READ_BATCH_CHUNK = 2000

    fun ensureMemscan(@Suppress("UNUSED_PARAMETER") context: Context) {
        // No-op: guest uses in-process /proc/self/mem, not an extracted executable.
    }

    fun readMaps(): String {
        return try {
            File("/proc/self/maps").readText()
        } catch (e: Exception) {
            AppLogger.e(TAG, "readMaps failed", e)
            ""
        }
    }

    fun parseRegions(mapsText: String): List<MemoryRegion> {
        val regions = mutableListOf<MemoryRegion>()
        for (line in mapsText.lineSequence()) {
            val region = parseMapsLine(line) ?: continue
            if (region.isReadable && region.isUserMemory) regions.add(region)
        }
        return regions
    }

    private fun parseMapsLine(line: String): MemoryRegion? {
        return try {
            val parts = line.trim().split(Regex("\\s+"))
            val rangeParts = parts[0].split("-")
            val start = rangeParts[0].toLong(16)
            val end = rangeParts[1].toLong(16)
            val perms = parts.getOrElse(1) { "----" }
            val name = parts.getOrElse(5) { "" }
            MemoryRegion(start, end, perms, name)
        } catch (_: Exception) {
            null
        }
    }

    fun scan(
        @Suppress("UNUSED_PARAMETER") context: Context,
        regions: List<MemoryRegion>,
        pattern: ByteArray,
        wildcard: Byte?,
        maxResults: Int
    ): Pair<List<Long>, Int> {
        if (regions.isEmpty() || pattern.isEmpty()) return emptyList<Long>() to 0
        val all = mutableListOf<Long>()
        var totalSkipped = 0
        for (chunk in regions.chunked(REGION_CHUNK)) {
            if (all.size >= maxResults) break
            val (addrs, skipped) = scanInProcess(chunk, pattern, wildcard, maxResults - all.size)
            totalSkipped += skipped
            all.addAll(addrs)
        }
        AppLogger.i(TAG, "guest scan total found=${all.size} skipped=$totalSkipped regions=${regions.size} engine=proc")
        return all to totalSkipped
    }

    private fun scanInProcess(
        regions: List<MemoryRegion>,
        pattern: ByteArray,
        wildcard: Byte?,
        maxResults: Int
    ): Pair<List<Long>, Int> {
        val found = mutableListOf<Long>()
        var skipped = 0
        try {
            RandomAccessFile(MEM_PATH, "r").use { mem ->
                for (region in regions) {
                    if (found.size >= maxResults) break
                    val start = region.startAddress
                    val size = region.size
                    if (size <= 0) continue
                    try {
                        var off = 0L
                        while (off < size && found.size < maxResults) {
                            val rsize = minOf(READ_CHUNK.toLong(), size - off).toInt()
                            val data = readAt(mem, start + off, rsize)
                            if (data == null || data.isEmpty()) {
                                off += rsize
                                continue
                            }
                            if (wildcard == null) {
                                findExact(data, pattern, start + off, maxResults - found.size, found)
                            } else {
                                findWildcard(data, pattern, wildcard, start + off, maxResults - found.size, found)
                            }
                            off += maxOf(1, data.size - pattern.size + 1).toLong()
                        }
                    } catch (_: Exception) {
                        skipped++
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "scanInProcess open $MEM_PATH failed", e)
        }
        return found to skipped
    }

    private fun readAt(mem: RandomAccessFile, address: Long, length: Int): ByteArray? {
        return try {
            mem.seek(address)
            val buf = ByteArray(length)
            var total = 0
            while (total < length) {
                val n = mem.read(buf, total, length - total)
                if (n <= 0) break
                total += n
            }
            when {
                total == length -> buf
                total > 0 -> buf.copyOf(total)
                else -> null
            }
        } catch (_: IOException) {
            null
        }
    }

    private fun findExact(
        data: ByteArray,
        pattern: ByteArray,
        baseAddr: Long,
        cap: Int,
        out: MutableList<Long>
    ) {
        val n = pattern.size
        var i = 0
        while (i <= data.size - n && out.size < cap) {
            var hit = -1
            var j = i
            while (j <= data.size - n) {
                if (data[j] == pattern[0]) {
                    var matched = true
                    for (k in 1 until n) {
                        if (data[j + k] != pattern[k]) {
                            matched = false
                            break
                        }
                    }
                    if (matched) {
                        hit = j
                        break
                    }
                }
                j++
            }
            if (hit < 0) break
            out.add(baseAddr + hit)
            i = hit + n
        }
    }

    private fun findWildcard(
        data: ByteArray,
        pattern: ByteArray,
        wildcard: Byte,
        baseAddr: Long,
        cap: Int,
        out: MutableList<Long>
    ) {
        val n = pattern.size
        for (i in 0..data.size - n) {
            if (out.size >= cap) return
            var ok = true
            for (j in 0 until n) {
                if (pattern[j] != wildcard && data[i + j] != pattern[j]) {
                    ok = false
                    break
                }
            }
            if (ok) out.add(baseAddr + i)
        }
    }

    fun readBatch(
        @Suppress("UNUSED_PARAMETER") context: Context,
        addresses: List<Long>,
        length: Int
    ): List<ByteArray?> {
        if (addresses.isEmpty()) return emptyList()
        val out = arrayOfNulls<ByteArray>(addresses.size)
        for ((batchIndex, batch) in addresses.chunked(READ_BATCH_CHUNK).withIndex()) {
            val batchOut = readBatchInProcess(batch, length)
            batch.forEachIndexed { i, _ ->
                val globalIdx = batchIndex * READ_BATCH_CHUNK + i
                if (globalIdx < out.size) out[globalIdx] = batchOut[i]
            }
        }
        return out.toList()
    }

    private fun readBatchInProcess(addresses: List<Long>, length: Int): List<ByteArray?> {
        val out = arrayOfNulls<ByteArray>(addresses.size)
        try {
            RandomAccessFile(MEM_PATH, "r").use { mem ->
                addresses.forEachIndexed { idx, addr ->
                    out[idx] = readAt(mem, addr, length)?.takeIf { it.size == length }
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "readBatchInProcess failed", e)
        }
        return out.toList()
    }

    fun refine(
        context: Context,
        addresses: List<Long>,
        pattern: ByteArray,
        wildcard: Byte?
    ): List<Pair<Long, ByteArray>> {
        if (addresses.isEmpty()) return emptyList()
        val bytesList = readBatch(context, addresses, pattern.size)
        val out = mutableListOf<Pair<Long, ByteArray>>()
        addresses.forEachIndexed { idx, addr ->
            val bytes = bytesList[idx] ?: return@forEachIndexed
            if (bytes.size != pattern.size) return@forEachIndexed
            val match = if (wildcard != null) {
                pattern.indices.all { j -> pattern[j] == wildcard || bytes[j] == pattern[j] }
            } else {
                bytes.contentEquals(pattern)
            }
            if (match) out.add(addr to bytes)
        }
        return out
    }

    fun readBytes(context: Context, address: Long, length: Int): ByteArray? =
        readBatch(context, listOf(address), length).firstOrNull()

    fun writeBytes(@Suppress("UNUSED_PARAMETER") context: Context, address: Long, bytes: ByteArray): Boolean {
        return try {
            RandomAccessFile(MEM_PATH, "rw").use { mem ->
                mem.seek(address)
                mem.write(bytes)
            }
            true
        } catch (e: Exception) {
            AppLogger.e(TAG, "writeBytes failed at 0x${address.toString(16)}", e)
            false
        }
    }
}
