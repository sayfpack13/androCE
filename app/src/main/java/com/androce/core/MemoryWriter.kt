package com.androce.core

import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object MemoryWriter {

    private const val TAG = "MemoryWriter"

    /**
     * Write [bytes] to [pid]'s memory at [address].
     * Uses [MemoryReader.writeBytesBatch] (single Python process) for the fast path.
     */
    suspend fun writeBytes(pid: Int, address: Long, bytes: ByteArray): Boolean =
        writeBytesMany(pid, listOf(address to bytes))

    /**
     * Batch write — single Python process for any number of (address, bytes) pairs.
     * Falls back to dd if Python is unavailable.
     */
    suspend fun writeBytesMany(pid: Int, writes: List<Pair<Long, ByteArray>>): Boolean =
        withContext(Dispatchers.IO) {
            if (writes.isEmpty()) return@withContext true
            val ok = MemoryReader.writeBytesBatch(pid, writes)
            if (ok) {
                AppLogger.d(TAG, "writeBytesMany pid=$pid count=${writes.size} via=python ok=true")
                return@withContext true
            }
            // dd fallback: write each one individually via printf | dd
            AppLogger.w(TAG, "writeBytesMany python failed, falling back to dd")
            var allOk = true
            for ((addr, b) in writes) {
                val esc = b.joinToString("") { "\\x%02x".format(it) }
                val cmd = "printf '$esc' | dd of=/proc/$pid/mem bs=${b.size} seek=$addr count=1 conv=notrunc 2>/dev/null"
                if (!Shell.cmd(cmd).exec().isSuccess) allOk = false
            }
            AppLogger.d(TAG, "writeBytesMany pid=$pid count=${writes.size} via=dd ok=$allOk")
            allOk
        }
}
