package com.androce.core

import com.androce.model.MemoryRegion
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object MemoryReader {

    private const val CHUNK_SIZE = 4 * 1024 * 1024L // 4 MB

    suspend fun getReadableRegions(pid: Int): List<MemoryRegion> = withContext(Dispatchers.IO) {
        val regions = mutableListOf<MemoryRegion>()
        try {
            val result = Shell.cmd("cat /proc/$pid/maps 2>/dev/null").exec()
            for (line in result.out) {
                val region = parseMapsLine(line) ?: continue
                if (region.isReadable) regions.add(region)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        regions
    }

    private fun parseMapsLine(line: String): MemoryRegion? {
        return try {
            val parts = line.trim().split("\\s+".toRegex())
            val rangeParts = parts[0].split("-")
            val start = rangeParts[0].toLong(16)
            val end = rangeParts[1].toLong(16)
            val perms = parts.getOrElse(1) { "----" }
            val name = parts.getOrElse(5) { "" }
            MemoryRegion(start, end, perms, name)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Read [length] bytes from [pid]'s memory at [address].
     * Uses a dd-based approach through the root shell, encoding output as hex.
     */
    suspend fun readBytes(pid: Int, address: Long, length: Int): ByteArray? =
        withContext(Dispatchers.IO) {
            try {
                val skip = address
                val result = Shell.cmd(
                    "dd if=/proc/$pid/mem bs=1 skip=$skip count=$length 2>/dev/null | xxd -p | tr -d '\\n'"
                ).exec()
                val hex = result.out.joinToString("").trim()
                if (hex.isEmpty()) return@withContext null
                hexToBytes(hex)
            } catch (e: Exception) {
                null
            }
        }

    /**
     * Scan a region of memory for a byte pattern, returning offsets (absolute addresses) of matches.
     * Reads the region in chunks to avoid OOM.
     */
    suspend fun scanRegion(
        pid: Int,
        region: MemoryRegion,
        pattern: ByteArray,
        wildcard: Byte? = null,
        onProgress: ((Long, Long) -> Unit)? = null
    ): List<Long> = withContext(Dispatchers.IO) {
        val matches = mutableListOf<Long>()
        var offset = 0L
        val regionSize = region.size

        while (offset < regionSize) {
            val chunkSize = minOf(CHUNK_SIZE, regionSize - offset).toInt()
            val absoluteAddr = region.startAddress + offset

            onProgress?.invoke(offset, regionSize)

            val chunk = readBytes(pid, absoluteAddr, chunkSize) ?: run {
                offset += chunkSize
                continue
            }

            val found = searchBytes(chunk, pattern, wildcard)
            for (rel in found) {
                matches.add(absoluteAddr + rel)
            }

            offset += chunkSize
        }
        matches
    }

    fun searchBytes(data: ByteArray, pattern: ByteArray, wildcard: Byte? = null): List<Int> {
        val results = mutableListOf<Int>()
        if (pattern.isEmpty() || data.size < pattern.size) return results

        outer@ for (i in 0..data.size - pattern.size) {
            for (j in pattern.indices) {
                if (wildcard != null && pattern[j] == wildcard) continue
                if (data[i + j] != pattern[j]) continue@outer
            }
            results.add(i)
        }
        return results
    }

    fun hexToBytes(hex: String): ByteArray {
        val clean = hex.filter { it.isLetterOrDigit() }
        val len = clean.length / 2
        return ByteArray(len) { i -> clean.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }
}
