package com.androce.core

import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object GlobalExceptionHandler {
    private const val TAG = "GlobalExceptionHandler"
    private const val LOG_FILE = "crash_log.txt"

    fun setup(context: Context) {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            logCrash(context, throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun logCrash(context: Context, throwable: Throwable) {
        try {
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val deviceInfo = getDeviceInfo()
            val stackTrace = getStackTrace(throwable)

            val logMessage = buildString {
                appendLine("=" .repeat(60))
                appendLine("CRASH REPORT - $timestamp")
                appendLine("=" .repeat(60))
                appendLine("Device: $deviceInfo")
                appendLine("Thread: ${Thread.currentThread().name}")
                appendLine()
                appendLine("Exception: ${throwable.javaClass.simpleName}")
                appendLine("Message: ${throwable.message}")
                appendLine()
                appendLine("Stack Trace:")
                appendLine(stackTrace)
                appendLine("=" .repeat(60))
                appendLine()
            }

            // Log to Logcat
            Log.e(TAG, logMessage)

            // Save to file
            saveToFile(context, logMessage)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to log crash", e)
        }
    }

    private fun getDeviceInfo(): String {
        return buildString {
            append("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            append(", Manufacturer: ${Build.MANUFACTURER}")
            append(", Model: ${Build.MODEL}")
        }
    }

    private fun getStackTrace(throwable: Throwable): String {
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        throwable.printStackTrace(pw)
        return sw.toString()
    }

    private fun saveToFile(context: Context, message: String) {
        try {
            val logDir = File(context.filesDir, "logs")
            if (!logDir.exists()) {
                logDir.mkdirs()
            }
            val logFile = File(logDir, LOG_FILE)
            logFile.appendText(message)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save crash log to file", e)
        }
    }
}
