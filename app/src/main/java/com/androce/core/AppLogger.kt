package com.androce.core

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLogger {

    private const val TAG = "androCE"
    private const val LOG_FILE = "androce_log.txt"
    private const val MAX_FILE_SIZE = 2 * 1024 * 1024L // 2 MB

    private var logFile: File? = null
    private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    var filesDir: File? = null
        private set

    fun init(context: Context) {
        filesDir = context.filesDir
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        logFile = File(dir, LOG_FILE)
        if (logFile!!.exists() && logFile!!.length() > MAX_FILE_SIZE) {
            logFile!!.delete()
        }
        i("AppLogger", "Log file: ${logFile!!.absolutePath}")
    }

    fun d(tag: String, msg: String) {
        Log.d("androCE.$tag", msg)
        write("D", tag, msg)
    }

    fun i(tag: String, msg: String) {
        Log.i("androCE.$tag", msg)
        write("I", tag, msg)
    }

    fun e(tag: String, msg: String, throwable: Throwable? = null) {
        Log.e("androCE.$tag", msg, throwable)
        val full = if (throwable != null) "$msg | ${throwable::class.simpleName}: ${throwable.message}" else msg
        write("E", tag, full)
    }

    fun w(tag: String, msg: String) {
        Log.w("androCE.$tag", msg)
        write("W", tag, msg)
    }

    private fun write(level: String, tag: String, msg: String) {
        val file = logFile ?: return
        try {
            FileWriter(file, true).use { fw ->
                fw.appendLine("${dateFmt.format(Date())} [$level] $tag: $msg")
            }
        } catch (_: Exception) {}
    }

    fun getLogPath(): String = logFile?.absolutePath ?: "not initialized"

    fun clearLog() {
        logFile?.delete()
    }
}
