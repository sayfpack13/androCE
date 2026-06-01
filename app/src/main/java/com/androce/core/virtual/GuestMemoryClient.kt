package com.androce.core.virtual

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.androce.core.AppLogger
import com.androce.model.MemoryRegion
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Host-side client for [GuestMemoryService] in the BlackBox guest process.
 */
object GuestMemoryClient {

    private const val TAG = "GuestMemoryClient"

    private val stubRef = AtomicReference<IGuestMemory?>(null)
    @Volatile private var boundPackage: String? = null
    @Volatile private var connection: ServiceConnection? = null

    fun isConnected(): Boolean = stubRef.get() != null

    fun connect(context: Context, packageName: String, timeoutMs: Long = 10_000L): Boolean {
        if (boundPackage == packageName && isConnected()) return true
        disconnect(context)
        val latch = CountDownLatch(1)
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                stubRef.set(IGuestMemory.Stub.asInterface(service))
                boundPackage = packageName
                connection = this
                AppLogger.i(TAG, "Connected to guest memory bridge for $packageName")
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                stubRef.set(null)
                boundPackage = null
                connection = null
            }
        }
        val intent = Intent(context, GuestMemoryService::class.java)
        try {
            if (!context.bindService(intent, conn, Context.BIND_AUTO_CREATE)) {
                AppLogger.w(TAG, "bindService returned false for GuestMemoryService")
                return false
            }
            val ok = latch.await(timeoutMs, TimeUnit.MILLISECONDS) && isConnected()
            if (ok) {
                pushMemscanToGuest(context)
            }
            if (!ok) {
                try {
                    context.unbindService(conn)
                } catch (_: Exception) {
                }
                stubRef.set(null)
            }
            return ok
        } catch (e: Exception) {
            AppLogger.e(TAG, "connect failed for $packageName", e)
            return false
        }
    }

    private fun pushMemscanToGuest(context: Context) {
        val stub = stubRef.get() ?: return
        val candidates = listOf(
            File(context.filesDir, "memscan"),
            File("/data/local/tmp/androce_memscan")
        )
        for (file in candidates) {
            if (!file.exists() || file.length() < 1000) continue
            try {
                val bytes = file.readBytes()
                if (stub.installMemscan(bytes)) {
                    AppLogger.i(TAG, "Pushed memscan to guest (${bytes.size} bytes from ${file.absolutePath})")
                    return
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "pushMemscanToGuest failed for ${file.absolutePath}", e)
            }
        }
        try {
            val bytes = context.assets.open("memscan").use { it.readBytes() }
            if (stub.installMemscan(bytes)) {
                AppLogger.i(TAG, "Pushed memscan to guest from assets (${bytes.size} bytes)")
                return
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "pushMemscanToGuest from assets failed", e)
        }
        AppLogger.w(TAG, "No memscan binary available to push to guest")
    }

    fun disconnect(context: Context) {
        connection?.let {
            try {
                context.unbindService(it)
            } catch (_: Exception) {
            }
        }
        stubRef.set(null)
        boundPackage = null
        connection = null
    }

    suspend fun getRegions(): List<MemoryRegion> = withContext(Dispatchers.IO) {
        val stub = stubRef.get() ?: return@withContext emptyList()
        try {
            val maps = stub.getRegionsLines() ?: return@withContext emptyList()
            GuestMemscanRunner.parseRegions(maps)
        } catch (e: Exception) {
            AppLogger.e(TAG, "getRegions failed", e)
            emptyList()
        }
    }

    suspend fun readBytes(address: Long, length: Int): ByteArray? = withContext(Dispatchers.IO) {
        try {
            stubRef.get()?.readBytes(address, length)
        } catch (e: Exception) {
            null
        }
    }

    suspend fun scanRegions(
        regions: List<MemoryRegion>,
        pattern: ByteArray,
        wildcard: Byte?,
        maxResults: Int
    ): Pair<List<Long>, Int> = withContext(Dispatchers.IO) {
        val stub = stubRef.get() ?: return@withContext emptyList<Long>() to 0
        try {
            val pairs = regions.joinToString(",") { "${it.startAddress}:${it.size}" }
            val wc = wildcard ?: (-1).toByte()
            val addrs = stub.scanPattern(pattern, wc, pairs, maxResults)?.toList() ?: emptyList()
            addrs to 0
        } catch (e: Exception) {
            AppLogger.e(TAG, "scanRegions failed", e)
            emptyList<Long>() to 0
        }
    }

    suspend fun readBytesBatch(requests: List<Pair<Long, Int>>): List<ByteArray?> =
        withContext(Dispatchers.IO) {
            val stub = stubRef.get() ?: return@withContext emptyList()
            if (requests.isEmpty()) return@withContext emptyList()
            try {
                val addrs = requests.map { it.first }.toLongArray()
                val length = requests.first().second
                val lines = stub.readBatchLines(addrs, length) ?: return@withContext List(requests.size) { null }
                val out = arrayOfNulls<ByteArray>(requests.size)
                for (line in lines.lineSequence()) {
                    if (line.isBlank()) continue
                    val parts = line.split(":", limit = 2)
                    if (parts.size != 2) continue
                    val idx = parts[0].toIntOrNull() ?: continue
                    val hex = parts[1]
                    if (idx in out.indices && hex.isNotEmpty()) {
                        out[idx] = hexToBytes(hex)
                    }
                }
                out.toList()
            } catch (e: Exception) {
                AppLogger.e(TAG, "readBytesBatch failed", e)
                List(requests.size) { null }
            }
        }

    suspend fun refinedScanBatch(
        addresses: List<Long>,
        pattern: ByteArray,
        wildcard: Byte?
    ): List<Pair<Long, ByteArray>> = withContext(Dispatchers.IO) {
        val stub = stubRef.get() ?: return@withContext emptyList()
        if (addresses.isEmpty()) return@withContext emptyList()
        val out = mutableListOf<Pair<Long, ByteArray>>()
        val wc = wildcard ?: (-1).toByte()
        try {
            for (batch in addresses.chunked(5000)) {
                val lines = stub.refinePatternLines(batch.toLongArray(), pattern, wc) ?: continue
                for (line in lines.lineSequence()) {
                    if (line.isBlank()) continue
                    val parts = line.split(":", limit = 2)
                    if (parts.size != 2) continue
                    val addr = parts[0].toLongOrNull() ?: continue
                    val hex = parts[1]
                    if (hex.isNotEmpty()) out.add(addr to hexToBytes(hex))
                }
            }
            out
        } catch (e: Exception) {
            AppLogger.e(TAG, "refinedScanBatch failed", e)
            emptyList()
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.replace("\\s".toRegex(), "")
        if (clean.isEmpty()) return ByteArray(0)
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    suspend fun writeBytes(address: Long, bytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
        try {
            stubRef.get()?.writeBytes(address, bytes) == true
        } catch (_: Exception) {
            false
        }
    }
}
