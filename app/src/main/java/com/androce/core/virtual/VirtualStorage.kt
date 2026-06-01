package com.androce.core.virtual

import android.content.Context
import android.os.Environment
import com.androce.core.AppLogger
import java.io.File

/**
 * Handles redirection of guest app data storage directories.
 * Prevents cloned guest apps from writing directly to system partitions,
 * redirecting all app data, SharedPreferences, cache, databases to androCE's internal storage sandbox.
 */
object VirtualStorage {

    private const val TAG = "VirtualStorage"

    /** Returns the simulated data directory for the guest app. */
    fun getDataDir(context: Context, packageName: String): File {
        val dir = File(context.filesDir, "virtual_storage/$packageName/data")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** Returns the simulated cache directory for the guest app. */
    fun getCacheDir(context: Context, packageName: String): File {
        val dir = File(context.filesDir, "virtual_storage/$packageName/cache")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** Returns the simulated external files directory for the guest app. */
    fun getExternalFilesDir(context: Context, packageName: String): File {
        val dir = File(context.getExternalFilesDir(null), "virtual_storage/$packageName/files")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** Prepares sandbox folders for a guest app. */
    fun setupSandbox(context: Context, packageName: String) {
        try {
            getDataDir(context, packageName)
            getCacheDir(context, packageName)
            getExternalFilesDir(context, packageName)
            AppLogger.d(TAG, "Sandbox storage prepared for guest $packageName")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to setup storage sandbox for $packageName", e)
        }
    }

    /** Wipes sandbox storage for a guest app. */
    fun cleanSandbox(context: Context, packageName: String) {
        try {
            val internal = File(context.filesDir, "virtual_storage/$packageName")
            if (internal.exists()) internal.deleteRecursively()

            val external = File(context.getExternalFilesDir(null), "virtual_storage/$packageName")
            if (external.exists()) external.deleteRecursively()

            AppLogger.d(TAG, "Cleaned sandbox storage for guest $packageName")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to clean sandbox storage for $packageName", e)
        }
    }
}
