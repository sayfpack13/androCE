package com.androce.core.virtual

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import com.androce.core.AppLogger
import com.androce.model.ProcessInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Orchestrates cloned APK storage and BlackBox virtual install/launch/stop.
 */
object VirtualSpaceManager {

    private const val TAG = "VirtualSpaceManager"

    data class VirtualAppMetadata(
        val packageName: String,
        val appName: String,
        val apkPath: String,
        val installTime: Long
    )

    suspend fun getInstalledVirtualApps(context: Context): List<VirtualAppMetadata> = withContext(Dispatchers.IO) {
        val list = mutableListOf<VirtualAppMetadata>()
        try {
            val pm = context.packageManager
            val appsDir = File(context.filesDir, "virtual_apps")
            if (!appsDir.exists()) return@withContext emptyList()

            appsDir.listFiles()?.forEach { dir ->
                if (!dir.isDirectory) return@forEach
                val apkFile = File(dir, "base.apk")
                if (!apkFile.exists()) return@forEach
                val packageName = dir.name
                val packageInfo: PackageInfo? = try {
                    pm.getPackageArchiveInfo(apkFile.absolutePath, 0)
                } catch (_: Exception) {
                    null
                }
                if (packageInfo?.applicationInfo == null) return@forEach
                val appInfo = packageInfo.applicationInfo!!
                val appName = try {
                    appInfo.sourceDir = apkFile.absolutePath
                    appInfo.publicSourceDir = apkFile.absolutePath
                    pm.getApplicationLabel(appInfo).toString()
                } catch (_: Exception) {
                    packageName
                }
                list.add(
                    VirtualAppMetadata(
                        packageName = packageName,
                        appName = appName,
                        apkPath = apkFile.absolutePath,
                        installTime = apkFile.lastModified()
                    )
                )
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to load installed virtual apps list", e)
        }
        list
    }

    suspend fun installAppToVirtualSpace(context: Context, packageName: String): Boolean {
        AppCloner.cloneInstalledApp(context, packageName) ?: return false
        val result = installIntoVa(context, packageName, File(context.filesDir, "virtual_apps/$packageName/base.apk"))
        if (!result.success) {
            AppLogger.e(TAG, "VA install failed for $packageName: ${result.msg}")
            return false
        }
        return true
    }

    /** Prefer system install (split APKs); fall back to cloned base.apk only. */
    private fun installIntoVa(context: Context, packageName: String, apkFile: File): top.niunaijun.blackbox.entity.pm.InstallResult {
        if (AppCloner.isInstalledOnDevice(context, packageName)) {
            return VirtualEngineFacade.installFromSystem(packageName)
        }
        AppLogger.w(TAG, "Installing $packageName from APK file only — split/native libs may be missing")
        return VirtualEngineFacade.installApk(apkFile)
    }

    suspend fun installExternalApk(context: Context, apkFile: File): Boolean {
        val clonedApk = AppCloner.cloneExternalApk(context, apkFile) ?: return false
        val result = VirtualEngineFacade.installApk(clonedApk)
        if (!result.success) {
            AppLogger.e(TAG, "VA install failed for external APK: ${result.msg}")
            return false
        }
        return true
    }

    suspend fun uninstallApp(context: Context, packageName: String): Boolean = withContext(Dispatchers.IO) {
        if (VirtualEngineFacade.isGuestAlive(context, packageName)) {
            VirtualEngineFacade.kill(context, packageName)
        }
        VirtualEngineFacade.uninstall(packageName)
        val removed = AppCloner.removeClonedApp(context, packageName)
        VirtualStorage.cleanSandbox(context, packageName)
        removed
    }

    suspend fun launchApp(context: Context, packageName: String, appName: String? = null): Boolean {
        val appsDir = File(context.filesDir, "virtual_apps/$packageName")
        val apkFile = File(appsDir, "base.apk")
        if (!apkFile.exists()) {
            AppLogger.e(TAG, "Cannot launch $packageName: base.apk missing")
            return false
        }
        val vaInstall = if (AppCloner.isInstalledOnDevice(context, packageName)) {
            // System install pulls all split APKs (e.g. arm64 Flutter libs) — base.apk alone shows a black screen
            VirtualEngineFacade.installFromSystem(packageName)
        } else if (!VirtualEngineFacade.isInstalled(packageName)) {
            installIntoVa(context, packageName, apkFile)
        } else {
            top.niunaijun.blackbox.entity.pm.InstallResult().apply { success = true; this.packageName = packageName }
        }
        if (!vaInstall.success) {
            AppLogger.e(TAG, "Cannot launch $packageName: VA install failed — ${vaInstall.msg}")
            return false
        }
        val label = appName ?: getInstalledVirtualApps(context).firstOrNull { it.packageName == packageName }?.appName
        return VirtualEngineFacade.launch(context, packageName, label)
    }

    suspend fun stopApp(context: Context, packageName: String) {
        VirtualEngineFacade.kill(context, packageName)
    }

    suspend fun getActiveVirtualProcesses(context: Context): List<ProcessInfo> {
        return VirtualEngineFacade.listRunning(context)
            .filter { it.isRunning }
            .map { guest ->
                ProcessInfo(
                    pid = guest.pid,
                    name = guest.packageName.substringAfterLast('.'),
                    packageName = guest.packageName,
                    appName = guest.appName,
                    isVirtual = true
                )
            }
    }
}
