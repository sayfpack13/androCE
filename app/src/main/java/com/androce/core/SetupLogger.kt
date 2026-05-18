package com.androce.core

import android.util.Log
import com.topjohnwu.superuser.Shell
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persistent install/setup log on device (survives app restarts) plus logcat.
 * Pull with: adb shell cat /data/local/tmp/androce/install.log
 */
object SetupLogger {
    private const val TAG = "Setup"
    const val DEVICE_LOG_PATH = "/data/local/tmp/androce/install.log"

    private val tsFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun append(message: String) {
        val line = "${tsFmt.format(Date())} $message"
        Log.i("androCE.$TAG", line)
        AppLogger.i(TAG, line)
        val escaped = line.replace("'", "'\"'\"'")
        Shell.cmd("mkdir -p /data/local/tmp/androce && printf '%s\\n' '$escaped' >> '$DEVICE_LOG_PATH'")
            .exec()
    }

    fun section(title: String) {
        append("======== $title ========")
    }

    fun appendShellOutput(label: String, output: String, maxChars: Int = 4000) {
        append("$label:")
        val text = output.trim().ifBlank { "(empty)" }.take(maxChars)
        text.lineSequence().forEach { append("  $it") }
    }

    fun clear() {
        Shell.cmd("mkdir -p /data/local/tmp/androce && : > '$DEVICE_LOG_PATH'").exec()
        append("Log cleared")
    }
}
