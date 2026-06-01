package com.androce.core.virtual

import android.content.Context
import android.content.pm.PackageManager
import com.androce.core.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Clones target APKs into androCE's sandboxed private storage directory.
 * Extracts APK, parses headers, and duplicates required metadata.
 */
object AppCloner {

    private const val TAG = "AppCloner"

    /**
     * Copies the APK of the target [packageName] to the app's sandboxed files/virtual_apps directory.
     * Returns the sandboxed cloned APK file, or null on failure.
     */
    suspend fun cloneInstalledApp(context: Context, packageName: String): File? = withContext(Dispatchers.IO) {
        try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            val sourceApk = File(appInfo.sourceDir)
            if (!sourceApk.exists()) {
                AppLogger.e(TAG, "Source APK does not exist for $packageName")
                return@withContext null
            }

            val appsDir = File(context.filesDir, "virtual_apps/$packageName")
            if (!appsDir.exists()) {
                appsDir.mkdirs()
            }

            val targetApk = File(appsDir, "base.apk")
            AppLogger.d(TAG, "Cloning $packageName from ${sourceApk.absolutePath} to ${targetApk.absolutePath}")

            copyFile(sourceApk, targetApk)

            var splitCount = 0
            appInfo.splitSourceDirs?.forEach { splitPath ->
                val splitFile = File(splitPath)
                if (!splitFile.exists()) return@forEach
                val dest = File(appsDir, splitFile.name)
                copyFile(splitFile, dest)
                splitCount++
            }
            if (splitCount > 0) {
                AppLogger.i(TAG, "Cloned $splitCount split APK(s) for $packageName (required for Flutter/native libs)")
            }

            if (targetApk.exists() && targetApk.length() > 0) {
                AppLogger.d(TAG, "Successfully cloned APK for $packageName (${targetApk.length()} bytes)")
                targetApk
            } else {
                null
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to clone app $packageName", e)
            null
        }
    }

    /**
     * Clones an APK from a local storage [apkFile] into androCE's virtual sandbox.
     * Returns the sandbox File, or null on failure.
     */
    suspend fun cloneExternalApk(context: Context, apkFile: File): File? = withContext(Dispatchers.IO) {
        try {
            val pm = context.packageManager
            val packageInfo = pm.getPackageArchiveInfo(apkFile.absolutePath, 0) ?: return@withContext null
            val packageName = packageInfo.packageName

            val appsDir = File(context.filesDir, "virtual_apps/$packageName")
            if (!appsDir.exists()) {
                appsDir.mkdirs()
            }

            val targetApk = File(appsDir, "base.apk")
            AppLogger.d(TAG, "Cloning external APK from ${apkFile.absolutePath} to ${targetApk.absolutePath}")

            apkFile.inputStream().use { input ->
                targetApk.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            if (targetApk.exists() && targetApk.length() > 0) {
                AppLogger.d(TAG, "Successfully cloned external APK for $packageName (${targetApk.length()} bytes)")
                targetApk
            } else {
                null
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to clone external APK", e)
            null
        }
    }

    private fun copyFile(source: File, dest: File) {
        source.inputStream().use { input ->
            dest.outputStream().use { output ->
                input.copyTo(output)
            }
        }
    }

    fun isInstalledOnDevice(context: Context, packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    /** Removes a cloned app from the sandbox. */
    fun removeClonedApp(context: Context, packageName: String): Boolean {
        val appsDir = File(context.filesDir, "virtual_apps/$packageName")
        return if (appsDir.exists()) {
            appsDir.deleteRecursively()
        } else {
            true
        }
    }
}
