package com.androce.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.androce.core.AppPrefs
import com.androce.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.topjohnwu.superuser.Shell

class FreezeService : Service() {

    inner class FreezeServiceBinder : Binder() {
        fun getService(): FreezeService = this@FreezeService
    }

    private val binder = FreezeServiceBinder()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var freezeJob: Job? = null

    private val frozenEntries = mutableMapOf<Long, FrozenEntry>()

    data class FrozenEntry(val pid: Int, val address: Long, val bytes: ByteArray)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    fun addFreeze(pid: Int, address: Long, bytes: ByteArray) {
        synchronized(frozenEntries) {
            frozenEntries[address] = FrozenEntry(pid, address, bytes.copyOf())
        }
        showForegroundNotification()
        if (freezeJob == null || !freezeJob!!.isActive) startFreezeLoop()
    }

    fun removeFreeze(address: Long) {
        synchronized(frozenEntries) {
            frozenEntries.remove(address)
        }
        if (frozenEntries.isEmpty()) {
            stopFreezeLoop()
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    fun clearAll() {
        synchronized(frozenEntries) { frozenEntries.clear() }
        stopFreezeLoop()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    fun updateFreezeBytes(address: Long, bytes: ByteArray) {
        synchronized(frozenEntries) {
            val entry = frozenEntries[address] ?: return
            frozenEntries[address] = entry.copy(bytes = bytes.copyOf())
        }
    }

    fun isFrozen(address: Long): Boolean = synchronized(frozenEntries) { frozenEntries.containsKey(address) }

    fun getFrozenCount(): Int = synchronized(frozenEntries) { frozenEntries.size }

    private fun startFreezeLoop() {
        freezeJob?.cancel()
        freezeJob = scope.launch {
            while (isActive) {
                val snapshot: List<FrozenEntry> = synchronized(frozenEntries) {
                    frozenEntries.values.toList()
                }
                val nativePath = MemoryReader.nativeHelper
                snapshot.groupBy { it.pid }.forEach { (pid, entries) ->
                    if (nativePath.isNotEmpty()) {
                        // Use Runtime.exec via su — bypasses libsu's serialized shell
                        val args = entries.joinToString(" ") { e ->
                            "${e.address}:${e.bytes.joinToString("") { "%02x".format(it) }}"
                        }
                        try {
                            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "$nativePath write $pid $args"))
                            proc.waitFor()
                        } catch (_: Exception) {}
                    } else {
                        Shell.cmd("true").exec() // no-op if native helper unavailable
                    }
                }
                delay(AppPrefs.freezeIntervalMs)
            }
        }
    }

    private fun stopFreezeLoop() {
        freezeJob?.cancel()
        freezeJob = null
    }

    private fun showForegroundNotification() {
        val count = synchronized(frozenEntries) { frozenEntries.size }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.freeze_notification_title))
            .setContentText("$count address${if (count != 1) "es" else ""} frozen")
            .setSmallIcon(android.R.drawable.ic_media_pause)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.freeze_notification_channel),
            NotificationManager.IMPORTANCE_LOW
        )
        channel.description = "androCE memory freeze background service"
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.coroutineContext[Job]?.cancel()
    }

    companion object {
        const val CHANNEL_ID = "androce_freeze"
        const val NOTIFICATION_ID = 1001
        const val FREEZE_INTERVAL_MS = 100L
    }
}
