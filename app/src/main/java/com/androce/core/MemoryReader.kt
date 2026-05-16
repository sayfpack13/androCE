package com.androce.core

import android.content.Context
import com.androce.model.MemoryRegion
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object MemoryReader {

    private const val TAG = "MemoryReader"
    const val MAX_RESULTS = 500_000

    private var nativeHelperPath: String = ""
    private var pythonAvailable: Boolean = true
    private var pythonBinary: String = "python3"

    val isPythonAvailable: Boolean get() = pythonAvailable
    val isNativeHelperReady: Boolean get() = nativeHelperPath.isNotEmpty()

    /** Whether to use Python for scanning, respecting the user's scan engine preference */
    val usePython: Boolean get() = when (AppPrefs.scanEngine) {
        "python" -> pythonAvailable
        "native" -> false
        else -> pythonAvailable  // "auto" — prefer Python if available
    }

    fun init(context: Context) {
        var dest = File(context.filesDir, "memscan")
        try {
            context.assets.open("memscan").use { input ->
                dest.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            Shell.cmd("chmod 755 ${dest.absolutePath}").exec()
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to extract native helper", e)
        }

        // Test if binary works from app files dir; if not, try /data/local/tmp
        val testRes = Shell.cmd("\"${dest.absolutePath}\" read 1 0 1 2>/dev/null || echo FAIL").exec()
        val works = testRes.out.any { it.trim().startsWith("# start") }
        if (!works) {
            val tmpDest = File("/data/local/tmp/androce_memscan")
            try {
                context.assets.open("memscan").use { input ->
                    tmpDest.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                Shell.cmd("chmod 755 ${tmpDest.absolutePath}").exec()
                val tmpTest = Shell.cmd("\"${tmpDest.absolutePath}\" read 1 0 1 2>/dev/null || echo FAIL").exec()
                if (tmpTest.out.any { it.trim().startsWith("# start") }) {
                    dest = tmpDest
                    AppLogger.d(TAG, "Using tmp native helper: ${dest.absolutePath}")
                } else {
                    AppLogger.e(TAG, "Native helper does not execute from files dir or tmp")
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to extract native helper to tmp", e)
            }
        }
        nativeHelperPath = dest.absolutePath

        // Set SELinux permissive — required for accessing Termux Python from root shell
        Shell.cmd("setenforce 0").exec()

        // Detect Python — check system PATH first
        pythonAvailable = false
        for (py in listOf("python3", "python")) {
            val check = Shell.cmd("$py --version 2>&1").exec()
            if (check.out.any { it.trim().startsWith("Python") }) {
                pythonBinary = py
                pythonAvailable = true
                break
            }
        }

        // If not found, try Termux Python via nsenter (PID 1 mount namespace)
        if (!pythonAvailable) {
            val termuxPrefix = "/data/data/com.termux/files/usr"
            val pyCmd = "nsenter -t 1 -m -- /system/bin/env LD_LIBRARY_PATH=$termuxPrefix/lib PYTHONHOME=$termuxPrefix $termuxPrefix/bin/python3"
            val setup = Shell.cmd("$pyCmd --version 2>&1").exec()
            if (setup.out.any { it.trim().startsWith("Python") }) {
                pythonBinary = pyCmd
                pythonAvailable = true
            }
        }

        if (pythonAvailable) AppLogger.d(TAG, "Python found: $pythonBinary")
        AppLogger.d(TAG, "Native helper: $nativeHelperPath, Python available: $pythonAvailable ($pythonBinary)")
    }

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
                // App filesDir not writable — write via root shell
                val escaped = scriptBody.replace("'", "'\\''")
                Shell.cmd("echo '$escaped' > $scriptFile").exec()
            }
            val result = Shell.cmd(
                "$pythonBinary $scriptFile 2>/dev/null; rm -f $scriptFile"
            ).exec()
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

    private fun runNativeCommand(vararg args: String): ScriptResult {
        val cmd = mutableListOf(nativeHelperPath)
        cmd.addAll(args)
        val cmdStr = cmd.joinToString(" ") { "\"$it\"" }
        val result = Shell.cmd(cmdStr).exec()
        // Log mode + arg count only — full cmdStr can be huge (thousands of addr:size pairs)
        AppLogger.d(TAG, "runNativeCommand mode=${args.firstOrNull()} args=${args.size} success=${result.isSuccess} out=${result.out.size} err=${result.err.size}")
        val stdout = mutableListOf<String>()
        val meta = mutableMapOf<String, String>()
        for (line in result.out) {
            val t = line.trim()
            if (t.startsWith("#") && t.contains(":")) {
                val kv = t.removePrefix("#").trim().split(":", limit = 2)
                if (kv.size == 2) meta[kv[0].trim()] = kv[1].trim()
            } else if (t.isNotEmpty()) stdout.add(t)
        }
        for (line in result.err) {
            AppLogger.d(TAG, "runNativeCommand stderr: $line")
        }
        return ScriptResult(stdout, meta)
    }

    private fun dispatchScriptOrNative(
        scriptBody: String,
        tag: String,
        nativeMode: String,
        nativeArgs: List<String>
    ): ScriptResult {
        return if (usePython) {
            // TODO: runPythonScript runs in suspend context, but we need a blocking version here.
            // For now, just run synchronously via Shell for native fallback path.
            ScriptResult(emptyList(), emptyMap()) // placeholder - actual callers handle this
        } else {
            runNativeCommand(nativeMode, *nativeArgs.toTypedArray())
        }
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
                val res = if (usePython) {
                    val scriptBody = """
import os
try:
    fd = os.open('/proc/$pid/mem', os.O_RDONLY)
    d = os.pread(fd, $length, $address)
    os.close(fd)
    if len(d) == $length:
        print(d.hex())
except Exception:
    pass
""".trimIndent()
                    runPythonScript(scriptBody, tag = "read1_$pid")
                } else {
                    runNativeCommand("read", pid.toString(), address.toString(), length.toString())
                }
                val hex = res.stdout.joinToString("").trim()
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
        val wildcardHex = wildcard?.let { "%02x".format(it) } ?: "none"
        val regionList = regions.joinToString(",") { "(${it.startAddress},${it.size})" }

        val res = if (usePython) {
            val scriptBody = if (wildcard == null) {
                """
import os
fd = os.open('/proc/$pid/mem', os.O_RDONLY)
pat = bytes.fromhex('$patHex')
n = len(pat)
cap = $maxResults
found = 0
skipped = 0
chunk = 4 * 1024 * 1024  # 4 MB
for start, size in [$regionList]:
    if found >= cap: break
    try:
        off = 0
        while off < size and found < cap:
            rsize = min(chunk, size - off)
            data = os.pread(fd, rsize, start + off)
            if not data:
                off += rsize
                continue
            i = data.find(pat)
            while i >= 0 and found < cap:
                print(start + off + i)
                found += 1
                i = data.find(pat, i + n)
            off += max(1, rsize - n + 1)
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
chunk = 4 * 1024 * 1024  # 4 MB
for start, size in [$regionList]:
    if found >= cap: break
    try:
        off = 0
        while off < size and found < cap:
            rsize = min(chunk, size - off)
            data = os.pread(fd, rsize, start + off)
            if not data:
                off += rsize
                continue
            dlen = len(data)
            for i in range(dlen - n + 1):
                if found >= cap: break
                if all(pat[j] == wc or data[i + j] == pat[j] for j in range(n)):
                    print(start + off + i)
                    found += 1
            off += max(1, rsize - n + 1)
    except Exception:
        skipped += 1
os.close(fd)
print('# skipped:' + str(skipped))
print('# capped:' + ('1' if found >= cap else '0'))
""".trimIndent()
            }
            runPythonScript(scriptBody, tag = "scan_$pid")
        } else {
            val nativeArgs = mutableListOf(pid.toString(), patHex, pattern.size.toString(), wildcardHex)
            nativeArgs.addAll(regions.map { "${it.startAddress}:${it.size}" })
            runNativeCommand("scan", *nativeArgs.toTypedArray())
        }

        val addrs = res.stdout.mapNotNull { it.toLongOrNull() }
        val skipped = res.meta["skipped"]?.toIntOrNull() ?: 0
        val capped = res.meta["capped"] == "1"
        AppLogger.d(TAG, "scanAllRegions pid=$pid regions=${regions.size} results=${addrs.size} skipped=$skipped capped=$capped")
        ScanOutcome(addrs, skipped, capped)
    }

    /**
     * Read multiple address ranges. Returns a list aligned with [requests] — nulls for failed reads.
     * Batches requests to avoid oversized Python scripts.
     */
    suspend fun readBytesBatch(
        pid: Int,
        requests: List<Pair<Long, Int>>
    ): List<ByteArray?> = withContext(Dispatchers.IO) {
        if (requests.isEmpty()) return@withContext emptyList()
        val out = arrayOfNulls<ByteArray>(requests.size)
        // Batch to keep script / command size under ~1 MB (~50K requests per batch)
        val batchSize = if (usePython) 50_000 else 2_000
        for ((batchIndex, batch) in requests.chunked(batchSize).withIndex()) {
            val res = if (usePython) {
                val reqList = batch.joinToString(",") { "(${it.first},${it.second})" }
                val scriptBody = """
import os
fd = os.open('/proc/$pid/mem', os.O_RDONLY)
for idx, (addr, n) in enumerate([$reqList]):
    try:
        d = os.pread(fd, n, addr)
        if len(d) == n:
            print(str(idx) + ':' + d.hex())
        else:
            print(str(idx) + ':')
    except Exception:
        print(str(idx) + ':')
os.close(fd)
""".trimIndent()
                runPythonScript(scriptBody, tag = "readb_${pid}_${batchIndex}")
            } else {
                val reqArgs = batch.map { "${it.first}:${it.second}" }
                val nativeArgs = mutableListOf(pid.toString())
                nativeArgs.addAll(reqArgs)
                runNativeCommand("readbatch", *nativeArgs.toTypedArray())
            }
            for (line in res.stdout) {
                val parts = line.split(":", limit = 2)
                if (parts.size != 2) continue
                val localIdx = parts[0].toIntOrNull() ?: continue
                val globalIdx = batchIndex * batchSize + localIdx
                if (globalIdx !in out.indices) continue
                val hex = parts[1]
                if (hex.isNotEmpty()) out[globalIdx] = hexToBytes(hex)
            }
        }
        out.toList()
    }

    /**
     * Refined scan: keep addresses where bytes still match [pattern] (with optional [wildcard]).
     * Batches addresses to avoid oversized Python scripts.
     */
    suspend fun refinedScanBatch(
        pid: Int,
        addresses: List<Long>,
        pattern: ByteArray,
        wildcard: Byte? = null
    ): List<Pair<Long, ByteArray>> = withContext(Dispatchers.IO) {
        if (addresses.isEmpty()) return@withContext emptyList()
        val allResults = mutableListOf<Pair<Long, ByteArray>>()
        val batchSize = if (usePython) 50_000 else 2_000
        var sampleLogged = false

        for (batch in addresses.chunked(batchSize)) {
            val reqs = batch.map { it to pattern.size }
            val bytesList = readBytesBatch(pid, reqs)

            var nullCount = 0
            batch.forEachIndexed { idx, addr ->
                val bytes = bytesList[idx]
                if (bytes == null) { nullCount++; return@forEachIndexed }
                if (bytes.size != pattern.size) return@forEachIndexed
                val match = if (wildcard != null) {
                    bytes.indices.all { j -> pattern[j] == wildcard || bytes[j] == pattern[j] }
                } else {
                    bytes.contentEquals(pattern)
                }
                if (match) {
                    allResults.add(addr to bytes)
                } else if (!sampleLogged && allResults.isEmpty()) {
                    AppLogger.d(TAG, "refinedScanBatch sample mismatch: addr=0x${"%X".format(addr)} read=${bytes.joinToString("") { "%02x".format(it) }} pattern=${pattern.joinToString("") { "%02x".format(it) }}")
                    sampleLogged = true
                }
            }
            if (nullCount > 0) AppLogger.d(TAG, "refinedScanBatch nullReads=$nullCount/${batch.size}")
        }
        allResults
    }

    /**
     * Comparison batch: typed comparisons against previously-known bytes.
     * [tcode] = u1/u2/u4/u8/i1/i2/i4/i8/f4/f8/raw. Operands are decimal strings.
     * Batches items to avoid oversized Python scripts.
     */
    suspend fun compareBatch(
        pid: Int,
        items: List<Triple<Long, ByteArray, Int>>, // (addr, prevBytes, size)
        op: String,
        tcode: String,
        operand1: String? = null,
        operand2: String? = null,
        onProgress: ((scanned: Int, found: Int) -> Unit)? = null
    ): List<Pair<Long, ByteArray>> = withContext(Dispatchers.IO) {
        if (items.isEmpty()) return@withContext emptyList()
        val allResults = mutableListOf<Pair<Long, ByteArray>>()
        val batchSize = 5_000
        var processed = 0

        for (batch in items.chunked(batchSize)) {
            val reqs = batch.map { it.first to it.third }
            val bytesList = readBytesBatch(pid, reqs)

            var nullCount = 0
            for ((idx, triple) in batch.withIndex()) {
                val (addr, prevBytes, size) = triple
                val newBytes = bytesList[idx]
                if (newBytes == null) { nullCount++; continue }
                if (newBytes.size != size) continue
                val keep = when (op) {
                    "CHANGED" -> !newBytes.contentEquals(prevBytes)
                    "UNCHANGED" -> newBytes.contentEquals(prevBytes)
                    else -> compareNumeric(op, newBytes, prevBytes, operand1, operand2, tcode)
                }
                if (keep) allResults.add(addr to newBytes)
            }
            if (nullCount > 0) AppLogger.d(TAG, "compareBatch nullReads=$nullCount/${batch.size}")
            processed += batch.size
            onProgress?.invoke(processed, allResults.size)
        }
        allResults
    }

    private fun decodeBytesLong(bytes: ByteArray, tcode: String): Long? {
        return try {
            val buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            when (tcode) {
                "u1" -> buffer.get().toLong() and 0xFFL
                "i1" -> buffer.get().toLong()
                "u2" -> buffer.getShort().toLong() and 0xFFFFL
                "i2" -> buffer.getShort().toLong()
                "u4" -> buffer.getInt().toLong() and 0xFFFFFFFFL
                "i4" -> buffer.getInt().toLong()
                "u8", "i8" -> buffer.getLong()
                else -> null
            }
        } catch (_: Exception) { null }
    }

    private fun decodeBytesDouble(bytes: ByteArray, tcode: String): Double? {
        return try {
            val buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            when (tcode) {
                "f4" -> buffer.getFloat().toDouble()
                "f8" -> buffer.getDouble()
                else -> null
            }
        } catch (_: Exception) { null }
    }

    private fun compareNumeric(op: String, newBytes: ByteArray, oldBytes: ByteArray, operand1: String?, operand2: String?, tcode: String): Boolean {
        val isFloat = tcode in listOf("f4", "f8")
        val eps = 1e-4
        return if (isFloat) {
            val vNew = decodeBytesDouble(newBytes, tcode) ?: return false
            val vOld = decodeBytesDouble(oldBytes, tcode) ?: return false
            val op1 = operand1?.toDoubleOrNull()
            val op2 = operand2?.toDoubleOrNull()
            when (op) {
                "INCREASED" -> vNew > vOld
                "DECREASED" -> vNew < vOld
                "INCREASED_BY" -> op1 != null && kotlin.math.abs(vNew - vOld - op1) <= eps * kotlin.math.max(1.0, kotlin.math.abs(op1))
                "DECREASED_BY" -> op1 != null && kotlin.math.abs(vOld - vNew - op1) <= eps * kotlin.math.max(1.0, kotlin.math.abs(op1))
                "BETWEEN" -> op1 != null && op2 != null && vNew >= op1 && vNew <= op2
                "EXACT" -> op1 != null && kotlin.math.abs(vNew - op1) <= eps * kotlin.math.max(1.0, kotlin.math.abs(op1))
                else -> false
            }
        } else {
            val vNew = decodeBytesLong(newBytes, tcode) ?: return false
            val vOld = decodeBytesLong(oldBytes, tcode) ?: return false
            val op1 = operand1?.toLongOrNull() ?: operand1?.toDoubleOrNull()?.toLong()
            val op2 = operand2?.toLongOrNull() ?: operand2?.toDoubleOrNull()?.toLong()
            when (op) {
                "INCREASED" -> vNew > vOld
                "DECREASED" -> vNew < vOld
                "INCREASED_BY" -> op1 != null && (vNew - vOld) == op1
                "DECREASED_BY" -> op1 != null && (vOld - vNew) == op1
                "BETWEEN" -> op1 != null && op2 != null && vNew >= op1 && vNew <= op2
                "EXACT" -> op1 != null && vNew == op1
                else -> false
            }
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
        val res = if (usePython) {
            val scriptBody = """
import os
fd = os.open('/proc/$pid/mem', os.O_RDONLY)
n = $slotSize
step = $step
cap = $maxResults
found = 0
skipped = 0
chunk = 4 * 1024 * 1024  # 4 MB
for start, size in [$regionList]:
    if found >= cap: break
    try:
        off = 0
        while off < size and found < cap:
            rsize = min(chunk, size - off)
            data = os.pread(fd, rsize, start + off)
            if not data:
                off += rsize
                continue
            last = len(data) - n
            i = 0
            while i <= last and found < cap:
                print(str(start + off + i) + ':' + data[i:i+n].hex())
                found += 1
                i += step
            off += max(1, rsize - n + 1)
    except Exception:
        skipped += 1
os.close(fd)
print('# skipped:' + str(skipped))
print('# capped:' + ('1' if found >= cap else '0'))
""".trimIndent()
            runPythonScript(scriptBody, tag = "snapb_$pid")
        } else {
            val nativeArgs = mutableListOf(pid.toString(), slotSize.toString(), step.toString())
            nativeArgs.addAll(regions.map { "${it.startAddress}:${it.size}" })
            runNativeCommand("snapshot", *nativeArgs.toTypedArray())
        }
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
        val res = if (usePython) {
            val list = writes.joinToString(",") {
                "(${it.first},'${it.second.joinToString("") { b -> "%02x".format(b) }}')"
            }
            val scriptBody = """
import os
try:
    fd = os.open('/proc/$pid/mem', os.O_WRONLY)
    for addr, hx in [$list]:
        try:
            os.pwrite(fd, bytes.fromhex(hx), addr)
        except Exception:
            pass
    os.close(fd)
    print('# ok:1')
except Exception as e:
    print('# ok:0')
""".trimIndent()
            runPythonScript(scriptBody, tag = "write_$pid")
        } else {
            val nativeArgs = mutableListOf(pid.toString())
            nativeArgs.addAll(writes.map { "${it.first}:${it.second.joinToString("") { b -> "%02x".format(b) }}" })
            runNativeCommand("write", *nativeArgs.toTypedArray())
        }
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
