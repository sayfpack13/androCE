package com.androce.core

import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object MemoryWriter {

    /**
     * Write [bytes] to [pid]'s memory at [address].
     * Uses a Python one-liner available on most rooted devices (or falls back to dd).
     */
    suspend fun writeBytes(pid: Int, address: Long, bytes: ByteArray): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val hexData = bytes.joinToString("") { "%02x".format(it) }
                // Build a Python script that opens /proc/pid/mem and writes
                val script = buildPythonWriteScript(pid, address, hexData)
                val result = Shell.cmd(script).exec()
                result.isSuccess
            } catch (e: Exception) {
                false
            }
        }

    private fun buildPythonWriteScript(pid: Int, address: Long, hexData: String): String {
        return "python3 -c \"" +
            "import os, struct; " +
            "fd = os.open('/proc/$pid/mem', os.O_WRONLY); " +
            "os.lseek(fd, $address, os.SEEK_SET); " +
            "os.write(fd, bytes.fromhex('$hexData')); " +
            "os.close(fd)" +
            "\" 2>/dev/null || " +
            "python -c \"" +
            "import os; " +
            "fd = os.open('/proc/$pid/mem', os.O_WRONLY); " +
            "os.lseek(fd, $address, os.SEEK_SET); " +
            "os.write(fd, bytes.fromhex('$hexData')); " +
            "os.close(fd)" +
            "\" 2>/dev/null"
    }

    suspend fun writeBytesRaw(pid: Int, address: Long, bytes: ByteArray): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val hexStr = bytes.joinToString("\\\\x") { "%02x".format(it) }
                val cmd = "printf '\\\\x$hexStr' | dd of=/proc/$pid/mem bs=1 seek=$address conv=notrunc 2>/dev/null"
                val result = Shell.cmd(cmd).exec()
                result.isSuccess
            } catch (e: Exception) {
                false
            }
        }
}
