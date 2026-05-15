package com.androce.core

import com.androce.model.MemoryRegion
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object MemoryReader {

    private const val TAG = "MemoryReader"
    private const val CHUNK_SIZE = 512 * 1024L // 512 KB — safe chunk for Python reads
    const val MAX_RESULTS = 500_000

    /**
     * Run a Python script by writing it to the app's filesDir, executing once, and cleaning up.
     * Returns the script's stdout lines plus a meta map of `# key:value` diagnostic lines.
     */
    internal data class ScriptResult(val stdout: List<String>, val meta: Map<String, String>)

    internal suspend fun runPythonScript(scriptBody: String, tag: String = "script"): ScriptResult =
        withContext(Dispatchers.IO) {
            val tmpDir = AppLogger.filesDir?.absolutePath ?: "/data/local/tmp"
            val scriptFile = "$tmpDir/androce_$tag.py"
            try {
                java.io.File(scriptFile).writeText(scriptBody)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to write $tag script", e)
                return@withContext ScriptResult(emptyList(), emptyMap())
            }
            val result = Shell.cmd("python3 $scriptFile 2>/dev/null; rm -f $scriptFile").exec()
            val stdout = mutableListOf<String>()
            val meta = mutableMapOf<String, String>()
            for (line in result.out) {
                val t = line.trim()
                if (t.startsWith("#") && t.contains(":")) {
                    val kv = t.removePrefix("#").trim().split(":", limit = 2)
                    if (kv.size == 2) meta[kv[0].trim()] = kv[1].trim()
                } else if (t.isNotEmpty()) stdout.add(t)
            }
            ScriptResult(stdout, meta)
        }

    suspend fun getReadableRegions(pid: Int): List<MemoryRegion> = withContext(Dispatchers.IO) {
        val regions = mutableListOf<MemoryRegion>()
        try {
            val result = Shell.cmd("cat /proc/$pid/maps 2>/dev/null").exec()
            AppLogger.d(TAG, "getReadableRegions pid=$pid shell success=${result.isSuccess} lines=${result.out.size}")
            for (line in result.out) {
                val region = parseMapsLine(line) ?: continue
                if (region.isReadable && region.isUserMemory) regions.add(region)
            }
            AppLogger.d(TAG, "Readable regions found: ${regions.size}")
        } catch (e: Exception) {
            AppLogger.e(TAG, "getReadableRegions failed", e)
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
     * Read [length] bytes from [pid]'s memory at [address] using Python hex dump.
     */
    suspend fun readBytes(pid: Int, address: Long, length: Int): ByteArray? =
        withContext(Dispatchers.IO) {
            try {
                val cmd = "python3 -c \"" +
                    "import os,sys;" +
                    "fd=os.open('/proc/$pid/mem',os.O_RDONLY);" +
                    "os.lseek(fd,$address,os.SEEK_SET);" +
                    "d=os.read(fd,$length);" +
                    "os.close(fd);" +
                    "sys.stdout.write(d.hex())" +
                    "\" 2>/dev/null"
                val result = Shell.cmd(cmd).exec()
                val hex = result.out.joinToString("").trim()
                if (hex.isEmpty()) return@withContext null
                hexToBytes(hex)
            } catch (e: Exception) {
                null
            }
        }

    /**
     * Scan ALL regions in a single Python process. Caps results at [MAX_RESULTS] to prevent OOM
     * and reports skipped (unreadable) regions via diagnostics.
     */
    data class ScanOutcome(val addresses: List<Long>, val skipped: Int, val capped: Boolean)

    suspend fun scanAllRegions(
        pid: Int,
        regions: List<MemoryRegion>,
        pattern: ByteArray,
        wildcard: Byte? = null,
        maxResults: Int = MAX_RESULTS
    ): ScanOutcome = withContext(Dispatchers.IO) {
        if (regions.isEmpty()) return@withContext ScanOutcome(emptyList(), 0, false)

        val patHex = pattern.joinToString("") { "%02x".format(it) }
        val wildcardHex = wildcard?.let { "%02x".format(it) } ?: ""
        val regionList = regions.joinToString(",") { "(${it.startAddress},${it.size})" }

        val scriptBody = if (wildcard == null) {
            """
import os
fd = os.open('/proc/$pid/mem', os.O_RDONLY)
pat = bytes.fromhex('$patHex')
n = len(pat)
cap = $maxResults
found = 0
skipped = 0
for start, size in [$regionList]:
    if found >= cap: break
    try:
        os.lseek(fd, start, os.SEEK_SET)
        data = os.read(fd, size)
        i = data.find(pat)
        while i >= 0 and found < cap:
            print(start + i)
            found += 1
            i = data.find(pat, i + n)
    except Exception:
        skipped += 1
os.close(fd)
print('# skipped:' + str(skipped))
print('# capped:' + ('1' if found >= cap else '0'))
""".trimIndent()
        } else {
            """
import os
fd = os.open('/proc/$pid/mem', os.O_RDONLY)
pat = bytes.fromhex('$patHex')
wc = bytes.fromhex('$wildcardHex')[0]
n = len(pat)
cap = $maxResults
found = 0
skipped = 0
for start, size in [$regionList]:
    if found >= cap: break
    try:
        os.lseek(fd, start, os.SEEK_SET)
        data = os.read(fd, size)
        for i in range(len(data) - n + 1):
            if found >= cap: break
            if all(pat[j] == wc or data[i + j] == pat[j] for j in range(n)):
                print(start + i)
                found += 1
    except Exception:
        skipped += 1
os.close(fd)
print('# skipped:' + str(skipped))
print('# capped:' + ('1' if found >= cap else '0'))
""".trimIndent()
        }

        val res = runPythonScript(scriptBody, tag = "scan_$pid")
        val addrs = res.stdout.mapNotNull { it.toLongOrNull() }
        val skipped = res.meta["skipped"]?.toIntOrNull() ?: 0
        val capped = res.meta["capped"] == "1"
        AppLogger.d(TAG, "scanAllRegions pid=$pid regions=${regions.size} results=${addrs.size} skipped=$skipped capped=$capped")
        ScanOutcome(addrs, skipped, capped)
    }

    /**
     * Read multiple address ranges in a single Python process.
     * Returns a list aligned with [requests] — nulls for failed reads.
     */
    suspend fun readBytesBatch(
        pid: Int,
        requests: List<Pair<Long, Int>>
    ): List<ByteArray?> = withContext(Dispatchers.IO) {
        if (requests.isEmpty()) return@withContext emptyList()
        val reqList = requests.joinToString(",") { "(${it.first},${it.second})" }
        val scriptBody = """
import os
fd = os.open('/proc/$pid/mem', os.O_RDONLY)
for idx, (addr, n) in enumerate([$reqList]):
    try:
        os.lseek(fd, addr, os.SEEK_SET)
        d = os.read(fd, n)
        if len(d) == n:
            print(str(idx) + ':' + d.hex())
        else:
            print(str(idx) + ':')
    except Exception:
        print(str(idx) + ':')
os.close(fd)
""".trimIndent()
        val res = runPythonScript(scriptBody, tag = "readb_$pid")
        val out = arrayOfNulls<ByteArray>(requests.size)
        for (line in res.stdout) {
            val parts = line.split(":", limit = 2)
            if (parts.size != 2) continue
            val idx = parts[0].toIntOrNull() ?: continue
            if (idx !in out.indices) continue
            val hex = parts[1]
            if (hex.isNotEmpty()) out[idx] = hexToBytes(hex)
        }
        out.toList()
    }

    /**
     * Refined scan: keep addresses where bytes still match [pattern] (with optional [wildcard]).
     * Single Python process for the entire batch.
     */
    suspend fun refinedScanBatch(
        pid: Int,
        addresses: List<Long>,
        pattern: ByteArray,
        wildcard: Byte? = null
    ): List<Pair<Long, ByteArray>> = withContext(Dispatchers.IO) {
        if (addresses.isEmpty()) return@withContext emptyList()
        val patHex = pattern.joinToString("") { "%02x".format(it) }
        val wildcardHex = wildcard?.let { "%02x".format(it) } ?: ""
        val addrList = addresses.joinToString(",")
        val scriptBody = if (wildcard == null) {
            """
import os
fd = os.open('/proc/$pid/mem', os.O_RDONLY)
pat = bytes.fromhex('$patHex')
n = len(pat)
for addr in [$addrList]:
    try:
        os.lseek(fd, addr, os.SEEK_SET)
        d = os.read(fd, n)
        if d == pat:
            print(str(addr) + ':' + d.hex())
    except Exception:
        pass
os.close(fd)
""".trimIndent()
        } else {
            """
import os
fd = os.open('/proc/$pid/mem', os.O_RDONLY)
pat = bytes.fromhex('$patHex')
wc = bytes.fromhex('$wildcardHex')[0]
n = len(pat)
for addr in [$addrList]:
    try:
        os.lseek(fd, addr, os.SEEK_SET)
        d = os.read(fd, n)
        if len(d) == n and all(pat[j] == wc or d[j] == pat[j] for j in range(n)):
            print(str(addr) + ':' + d.hex())
    except Exception:
        pass
os.close(fd)
""".trimIndent()
        }
        val res = runPythonScript(scriptBody, tag = "refine_$pid")
        res.stdout.mapNotNull { line ->
            val parts = line.split(":", limit = 2)
            if (parts.size != 2) return@mapNotNull null
            val addr = parts[0].toLongOrNull() ?: return@mapNotNull null
            val bytes = if (parts[1].isNotEmpty()) hexToBytes(parts[1]) else return@mapNotNull null
            addr to bytes
        }
    }

    /**
     * Comparison batch: typed comparisons against previously-known bytes.
     * [tcode] = u1/u2/u4/u8/i1/i2/i4/i8/f4/f8/raw. Operands are decimal strings.
     */
    suspend fun compareBatch(
        pid: Int,
        items: List<Triple<Long, ByteArray, Int>>, // (addr, prevBytes, size)
        op: String,
        tcode: String,
        operand1: String? = null,
        operand2: String? = null
    ): List<Pair<Long, ByteArray>> = withContext(Dispatchers.IO) {
        if (items.isEmpty()) return@withContext emptyList()
        val fmt = when (tcode) {
            "u1" -> "<B"; "i1" -> "<b"
            "u2" -> "<H"; "i2" -> "<h"
            "u4" -> "<I"; "i4" -> "<i"
            "u8" -> "<Q"; "i8" -> "<q"
            "f4" -> "<f"; "f8" -> "<d"
            else -> ""
        }
        val itemsList = items.joinToString(",") { "(${it.first},'${it.second.joinToString("") { b -> "%02x".format(b) }}',${it.third})" }
        val op1 = operand1 ?: "None"
        val op2 = operand2 ?: "None"
        val scriptBody = """
import os, struct
fd = os.open('/proc/$pid/mem', os.O_RDONLY)
fmt = '$fmt'
op = '$op'
op1 = $op1
op2 = $op2
eps = 1e-4
for addr, prev_hex, n in [$itemsList]:
    try:
        os.lseek(fd, addr, os.SEEK_SET)
        d = os.read(fd, n)
        if len(d) != n: continue
        old = bytes.fromhex(prev_hex)
        if fmt:
            v_new = struct.unpack(fmt, d)[0]
            v_old = struct.unpack(fmt, old)[0]
            is_float = fmt in ('<f', '<d')
        else:
            v_new = d
            v_old = old
            is_float = False
        keep = False
        if op == 'CHANGED':
            keep = (d != old)
        elif op == 'UNCHANGED':
            keep = (d == old)
        elif op == 'INCREASED':
            keep = v_new > v_old
        elif op == 'DECREASED':
            keep = v_new < v_old
        elif op == 'INCREASED_BY':
            delta = v_new - v_old
            if is_float: keep = abs(delta - op1) <= eps * max(1.0, abs(op1))
            else: keep = delta == op1
        elif op == 'DECREASED_BY':
            delta = v_old - v_new
            if is_float: keep = abs(delta - op1) <= eps * max(1.0, abs(op1))
            else: keep = delta == op1
        elif op == 'BETWEEN':
            keep = (op1 <= v_new <= op2)
        elif op == 'EXACT':
            if is_float: keep = abs(v_new - op1) <= eps * max(1.0, abs(op1))
            else: keep = (v_new == op1)
        if keep:
            print(str(addr) + ':' + d.hex())
    except Exception:
        pass
os.close(fd)
""".trimIndent()
        val res = runPythonScript(scriptBody, tag = "cmp_$pid")
        res.stdout.mapNotNull { line ->
            val parts = line.split(":", limit = 2)
            if (parts.size != 2) return@mapNotNull null
            val addr = parts[0].toLongOrNull() ?: return@mapNotNull null
            val bytes = if (parts[1].isNotEmpty()) hexToBytes(parts[1]) else return@mapNotNull null
            addr to bytes
        }
    }

    /**
     * Snapshot scan for "unknown initial value" — records the current value at every aligned slot.
     * [step] is the aligned stride (typically the type's byte size).
     */
    suspend fun snapshotScanWithBytes(
        pid: Int,
        regions: List<MemoryRegion>,
        slotSize: Int,
        step: Int = slotSize,
        maxResults: Int = MAX_RESULTS
    ): Triple<List<Pair<Long, ByteArray>>, Int, Boolean> = withContext(Dispatchers.IO) {
        if (regions.isEmpty() || slotSize <= 0) return@withContext Triple(emptyList(), 0, false)
        val regionList = regions.joinToString(",") { "(${it.startAddress},${it.size})" }
        val scriptBody = """
import os
fd = os.open('/proc/$pid/mem', os.O_RDONLY)
n = $slotSize
step = $step
cap = $maxResults
found = 0
skipped = 0
for start, size in [$regionList]:
    if found >= cap: break
    try:
        os.lseek(fd, start, os.SEEK_SET)
        data = os.read(fd, size)
        last = len(data) - n
        i = 0
        while i <= last and found < cap:
            print(str(start + i) + ':' + data[i:i+n].hex())
            found += 1
            i += step
    except Exception:
        skipped += 1
os.close(fd)
print('# skipped:' + str(skipped))
print('# capped:' + ('1' if found >= cap else '0'))
""".trimIndent()
        val res = runPythonScript(scriptBody, tag = "snapb_$pid")
        val skipped = res.meta["skipped"]?.toIntOrNull() ?: 0
        val capped = res.meta["capped"] == "1"
        val items = res.stdout.mapNotNull { line ->
            val parts = line.split(":", limit = 2)
            if (parts.size != 2) return@mapNotNull null
            val addr = parts[0].toLongOrNull() ?: return@mapNotNull null
            val bytes = if (parts[1].isNotEmpty()) hexToBytes(parts[1]) else return@mapNotNull null
            addr to bytes
        }
        AppLogger.d(TAG, "snapshotScanWithBytes pid=$pid slots=${items.size} skipped=$skipped capped=$capped")
        Triple(items, skipped, capped)
    }

    /**
     * Write multiple (address, bytes) pairs in a single Python process.
     */
    suspend fun writeBytesBatch(
        pid: Int,
        writes: List<Pair<Long, ByteArray>>
    ): Boolean = withContext(Dispatchers.IO) {
        if (writes.isEmpty()) return@withContext true
        val list = writes.joinToString(",") {
            "(${it.first},'${it.second.joinToString("") { b -> "%02x".format(b) }}')"
        }
        val scriptBody = """
import os
try:
    fd = os.open('/proc/$pid/mem', os.O_WRONLY)
    for addr, hx in [$list]:
        try:
            os.lseek(fd, addr, os.SEEK_SET)
            os.write(fd, bytes.fromhex(hx))
        except Exception:
            pass
    os.close(fd)
    print('# ok:1')
except Exception as e:
    print('# ok:0')
""".trimIndent()
        val res = runPythonScript(scriptBody, tag = "write_$pid")
        res.meta["ok"] == "1"
    }

    /**
     * Scan a single region — kept for compatibility, delegates to scanAllRegions.
     */
    suspend fun scanRegion(
        pid: Int,
        region: MemoryRegion,
        pattern: ByteArray,
        wildcard: Byte? = null,
        onProgress: ((Long, Long) -> Unit)? = null
    ): List<Long> = scanAllRegions(pid, listOf(region), pattern, wildcard).addresses

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
