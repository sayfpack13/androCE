package com.androce.core.virtual

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.androce.core.AppLogger

/**
 * Runs in the BlackBox guest process. Exposes /proc/self memory to the host via Binder (no root).
 */
class GuestMemoryService : Service() {

    private val binder = object : IGuestMemory.Stub() {
        override fun ping(): Boolean = true

        override fun installMemscan(binary: ByteArray): Boolean {
            if (binary.isEmpty()) return false
            return try {
                val dest = java.io.File(applicationInfo.dataDir, "files/memscan")
                dest.parentFile?.mkdirs()
                dest.writeBytes(binary)
                Runtime.getRuntime().exec(arrayOf("chmod", "755", dest.absolutePath)).waitFor()
                AppLogger.i(TAG, "installMemscan: ${binary.size} bytes -> ${dest.absolutePath}")
                true
            } catch (e: Exception) {
                AppLogger.e(TAG, "installMemscan failed", e)
                false
            }
        }

        override fun getRegionsLines(): String = GuestMemscanRunner.readMaps()

        override fun readBytes(address: Long, length: Int): ByteArray? =
            GuestMemscanRunner.readBytes(this@GuestMemoryService, address, length)

        override fun scanPattern(
            pattern: ByteArray,
            wildcard: Byte,
            regionPairs: String,
            maxResults: Int
        ): LongArray {
            val regions = if (regionPairs.isBlank()) {
                GuestMemscanRunner.parseRegions(GuestMemscanRunner.readMaps())
            } else {
                parseRegionPairs(regionPairs)
            }
            val (addrs, _) = GuestMemscanRunner.scan(
                this@GuestMemoryService,
                regions,
                pattern,
                if (wildcard.toInt() == -1) null else wildcard,
                maxResults
            )
            return addrs.take(maxResults).toLongArray()
        }

        override fun readBatchLines(addresses: LongArray, length: Int): String {
            val bytesList = GuestMemscanRunner.readBatch(
                this@GuestMemoryService,
                addresses.toList(),
                length
            )
            return buildString {
                bytesList.forEachIndexed { idx, bytes ->
                    append(idx).append(':')
                    if (bytes != null) {
                        bytes.forEach { b -> append("%02x".format(b)) }
                    }
                    append('\n')
                }
            }
        }

        override fun refinePattern(addresses: LongArray, pattern: ByteArray, wildcard: Byte): LongArray =
            refinePatternLines(addresses, pattern, wildcard)
                .lineSequence()
                .mapNotNull { line ->
                    line.substringBefore(':').toLongOrNull()
                }
                .toList()
                .toLongArray()

        override fun refinePatternLines(addresses: LongArray, pattern: ByteArray, wildcard: Byte): String {
            val wc = if (wildcard.toInt() == -1) null else wildcard
            return GuestMemscanRunner.refine(
                this@GuestMemoryService,
                addresses.toList(),
                pattern,
                wc
            ).joinToString("\n") { (addr, bytes) ->
                buildString {
                    append(addr).append(':')
                    bytes.forEach { b -> append("%02x".format(b)) }
                }
            }
        }

        override fun writeBytes(address: Long, data: ByteArray): Boolean =
            GuestMemscanRunner.writeBytes(this@GuestMemoryService, address, data)
    }

    override fun onBind(intent: Intent?): IBinder {
        AppLogger.i(TAG, "Guest memory bridge bound (pid=${android.os.Process.myPid()})")
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        GuestMemscanRunner.ensureMemscan(this)
        AppLogger.i(TAG, "Guest memory bridge started in guest process (pid=${android.os.Process.myPid()})")
    }

    private fun parseRegionPairs(spec: String): List<com.androce.model.MemoryRegion> {
        return spec.split(",").mapNotNull { pair ->
            val p = pair.split(":")
            if (p.size != 2) return@mapNotNull null
            val start = p[0].toLongOrNull() ?: return@mapNotNull null
            val size = p[1].toLongOrNull() ?: return@mapNotNull null
            com.androce.model.MemoryRegion(start, start + size, "r--p", "")
        }
    }

    companion object {
        private const val TAG = "GuestMemoryService"
    }
}
