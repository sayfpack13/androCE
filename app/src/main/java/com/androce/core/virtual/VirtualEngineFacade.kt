package com.androce.core.virtual

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import com.androce.core.AppLogger
import com.androce.core.ProcessLister
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import android.app.Application
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.app.configuration.AppLifecycleCallback
import top.niunaijun.blackbox.app.configuration.ClientConfiguration
import top.niunaijun.blackbox.core.GmsCore
import top.niunaijun.blackbox.core.env.BEnvironment
import top.niunaijun.blackbox.core.system.BProcessManagerService
import top.niunaijun.blackbox.entity.pm.InstallResult
import top.niunaijun.blackbox.proxy.ProxyManifest
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Thin wrapper around NewBlackbox (BlackBoxCore) so androCE UI does not depend on engine APIs directly.
 */
object VirtualEngineFacade {

    private const val TAG = "VirtualEngineFacade"
    const val DEFAULT_USER_ID = 0
    private const val LAUNCH_GRACE_MS = 30_000L
    private const val SERVICE_WAIT_SEC = 12L

    data class GuestProcess(
        val packageName: String,
        val appName: String,
        val pid: Int,
        val userId: Int = DEFAULT_USER_ID,
        val isRunning: Boolean
    )

    data class VirtualGuestRuntime(
        val packageName: String?,
        val appName: String?,
        val pid: Int,
        val isRunning: Boolean
    )

    private val _runtimeState = MutableStateFlow(
        VirtualGuestRuntime(packageName = null, appName = null, pid = -1, isRunning = false)
    )
    val runtimeState: StateFlow<VirtualGuestRuntime> = _runtimeState.asStateFlow()

    private val servicesReady = AtomicBoolean(false)
    @Volatile private var attached = false
    @Volatile private var lastLaunchAtMs = 0L

    fun attachBaseContext(context: Context) {
        try {
            BlackBoxCore.get().doAttachBaseContext(
                context,
                object : ClientConfiguration() {
                    override fun getHostPackageName(): String = context.packageName

                    // Demo defaults daemon off — fewer start/stop issues on Android 14+
                    override fun isEnableDaemonService(): Boolean = false

                    // Skip host splash trampoline — it returns to androCE and hides the guest UI.
                    override fun isEnableLauncherActivity(): Boolean = false

                    override fun requestInstallPackage(file: File?, userId: Int): Boolean = false
                }
            )
            attached = true
            AppLogger.i(TAG, "BlackBox engine attached (host=${context.packageName})")
        } catch (e: Exception) {
            AppLogger.e(TAG, "doAttachBaseContext failed", e)
        }
    }

    fun onCreate() {
        if (!attached) return
        try {
            BlackBoxCore.get().doCreate()
            BlackBoxCore.get().addAppLifecycleCallback(object : AppLifecycleCallback() {
                override fun beforeApplicationOnCreate(
                    packageName: String?,
                    processName: String?,
                    application: Application?,
                    userId: Int
                ) {
                    if (packageName == null || application == null) return
                    try {
                        application.startService(
                            Intent(application, GuestMemoryService::class.java)
                        )
                    } catch (e: Exception) {
                        AppLogger.e(TAG, "Failed to start GuestMemoryService in $packageName", e)
                    }
                    if (!PlayLicenseHelper.usesPlayLicensing(application, packageName)) return
                    PlayLicenseHelper.registerLicenseActivityBlocker(application, packageName)
                }

                override fun onStoragePermissionNeeded(packageName: String?, userId: Int): Boolean {
                    AppLogger.w(TAG, "BlackBox needs all-files access for $packageName (userId=$userId)")
                    return false
                }
            })
            BlackBoxCore.get().addServiceAvailableCallback {
                servicesReady.set(true)
                AppLogger.i(TAG, "BlackBox services ready")
            }
            AppLogger.i(TAG, "BlackBox engine initialized")
        } catch (e: Exception) {
            AppLogger.e(TAG, "doCreate failed", e)
        }
    }

    fun isEngineReady(): Boolean = attached

  private val VIRTUAL_GMS_PACKAGES = listOf(
        "com.google.android.gms",
        "com.google.android.gsf",
        "com.android.vending"
    )

    /**
     * Best-effort virtual GMS/Play Store install (per-package; avoids installGApps batch parser failures).
     * PairIP bypass is handled in [PlayLicenseHelper] guest hooks — not via GMS alone.
     */
    fun ensureGoogleServices(userId: Int = DEFAULT_USER_ID): Boolean {
        if (!GmsCore.isSupportGms()) return false
        if (GmsCore.isInstalledGoogleService(userId)) return true
        awaitServices()
        var installedAny = false
        val hostPm = BlackBoxCore.getContext().packageManager
        for (pkg in VIRTUAL_GMS_PACKAGES) {
            try {
                hostPm.getPackageInfo(pkg, 0)
            } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
                continue
            }
            if (BlackBoxCore.get().isInstalled(pkg, userId)) {
                installedAny = true
                continue
            }
            val result = installFromSystem(pkg, userId)
            if (result.success) {
                AppLogger.i(TAG, "Installed virtual $pkg")
                installedAny = true
            } else {
                AppLogger.w(TAG, "Virtual install $pkg failed: ${result.msg}")
            }
        }
        return GmsCore.isInstalledGoogleService(userId) || installedAny
    }

    fun needsAllFilesAccess(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            !BlackBoxCore.get().hasAllFilesAccess()
    }

    fun openAllFilesAccessSettings(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        try {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to open all-files settings", e)
            try {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            } catch (e2: Exception) {
                AppLogger.e(TAG, "Failed to open global all-files settings", e2)
            }
        }
    }

    private fun awaitServices() {
        if (servicesReady.get()) return
        val latch = CountDownLatch(1)
        BlackBoxCore.get().addServiceAvailableCallback {
            servicesReady.set(true)
            latch.countDown()
        }
        try {
            latch.await(SERVICE_WAIT_SEC, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        if (!servicesReady.get()) {
            AppLogger.w(TAG, "BlackBox services not ready after ${SERVICE_WAIT_SEC}s — continuing anyway")
        }
    }

    fun installApk(apkFile: File, userId: Int = DEFAULT_USER_ID): InstallResult {
        awaitServices()
        return try {
            val result = BlackBoxCore.get().installPackageAsUser(apkFile, userId)
            if (!result.success) {
                AppLogger.e(TAG, "installApk (storage) failed: ${result.msg}")
            } else {
                AppLogger.i(TAG, "installApk (storage) ok: ${result.packageName}")
            }
            result
        } catch (e: Exception) {
            AppLogger.e(TAG, "installApk failed: ${apkFile.absolutePath}", e)
            InstallResult().installError(e.message ?: "install failed")
        }
    }

    /**
     * Install from the system copy of an app (includes split APKs / native libs).
     * Required for Flutter and other apps with config.arm64_v8a splits.
     */
    fun installFromSystem(packageName: String, userId: Int = DEFAULT_USER_ID): InstallResult {
        awaitServices()
        return try {
            val result = BlackBoxCore.get().installPackageAsUser(packageName, userId)
            if (!result.success) {
                AppLogger.e(TAG, "installFromSystem failed for $packageName: ${result.msg}")
            } else {
                AppLogger.i(TAG, "installFromSystem ok: $packageName")
            }
            result
        } catch (e: Exception) {
            AppLogger.e(TAG, "installFromSystem failed for $packageName", e)
            InstallResult().installError(e.message ?: "install failed")
        }
    }

    fun isInstalled(packageName: String, userId: Int = DEFAULT_USER_ID): Boolean {
        return try {
            BlackBoxCore.get().isInstalled(packageName, userId)
        } catch (e: Exception) {
            false
        }
    }

    fun launch(context: Context, packageName: String, appName: String?, userId: Int = DEFAULT_USER_ID): Boolean {
        val label = appName ?: packageName
        if (needsAllFilesAccess()) {
            AppLogger.w(TAG, "All-files access missing — guest may exit immediately after launch")
        }
        awaitServices()
        return try {
            if (!isInstalled(packageName, userId)) {
                AppLogger.e(TAG, "Cannot launch $packageName — not installed in BlackBox VA")
                return false
            }
            lastLaunchAtMs = System.currentTimeMillis()
            // PairIP apps: launchApk starts the guest process (license gate activity).
            // PlayLicenseHelper's in-guest blocker then opens MainActivity and finishes the gate.
            // Direct startActivity(MainActivity) from the host does not start a VA guest (pid stays -1).
            val ok = if (PlayLicenseHelper.usesPlayLicensing(context, packageName)) {
                val main = PlayLicenseHelper.resolveMainLauncher(context, packageName)
                if (main != null) {
                    AppLogger.i(TAG, "PairIP $packageName — launchApk then redirect to ${main.className}")
                } else {
                    AppLogger.w(TAG, "PairIP $packageName — main activity unknown, launchApk only")
                }
                BlackBoxCore.get().launchApk(packageName, userId)
            } else {
                BlackBoxCore.get().launchApk(packageName, userId)
            }
            if (!ok) {
                AppLogger.w(TAG, "Launch returned false for $packageName")
                return false
            }
            var pid = -1
            repeat(20) {
                Thread.sleep(250)
                pid = resolveGuestPid(context, packageName, userId)
                if (pid > 0) return@repeat
            }
            val alive = pid > 0 || isActivityRunning(packageName, userId) || ok
            _runtimeState.value = VirtualGuestRuntime(packageName, label, pid, isRunning = alive)
            AppLogger.i(TAG, "Launched $packageName in VA (pid=$pid, activityRunning=$alive)")
            if (ok) {
                scheduleBringGuestToForeground(context, packageName, userId)
                Thread {
                    try {
                        Thread.sleep(800)
                        GuestMemoryClient.connect(context.applicationContext, packageName)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                }.start()
            }
            ok
        } catch (e: Exception) {
            AppLogger.e(TAG, "launch failed for $packageName", e)
            false
        }
    }

    /** Show the guest app window on top (VA guests do not get their own recents card). */
    fun bringGuestToForeground(context: Context, packageName: String, userId: Int = DEFAULT_USER_ID) {
        awaitServices()
        try {
            val intent = buildGuestUiIntent(context, packageName, userId) ?: run {
                AppLogger.w(TAG, "bringGuestToForeground: no UI intent for $packageName")
                return
            }
            BlackBoxCore.getBActivityManager().startActivity(intent, userId)
            AppLogger.i(TAG, "bringGuestToForeground: ${intent.component?.className ?: packageName}")
        } catch (e: Exception) {
            AppLogger.e(TAG, "bringGuestToForeground failed for $packageName", e)
        }
    }

    private fun buildGuestUiIntent(context: Context, packageName: String, userId: Int): Intent? {
        if (PlayLicenseHelper.usesPlayLicensing(context, packageName)) {
            val main = PlayLicenseHelper.resolveMainLauncher(context, packageName) ?: return null
            return Intent().apply {
                setClassName(main.packageName, main.className)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            }
        }
        return BlackBoxCore.getBPackageManager().getLaunchIntentForPackage(packageName, userId)?.apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }
    }

    private fun scheduleBringGuestToForeground(context: Context, packageName: String, userId: Int) {
        Thread {
            try {
                // PairIP redirect + Flutter init need a moment before the main UI can take focus.
                Thread.sleep(600)
                bringGuestToForeground(context, packageName, userId)
                Thread.sleep(1200)
                if (isGuestAlive(context, packageName, userId)) {
                    bringGuestToForeground(context, packageName, userId)
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }.start()
    }

    fun kill(context: Context, packageName: String, userId: Int = DEFAULT_USER_ID) {
        try {
            BlackBoxCore.get().stopPackage(packageName, userId)
            AppLogger.i(TAG, "stopPackage: $packageName")
        } catch (e: Exception) {
            AppLogger.e(TAG, "stopPackage failed for $packageName", e)
        }
        if (_runtimeState.value.packageName == packageName) {
            _runtimeState.value = VirtualGuestRuntime(null, null, -1, isRunning = false)
        }
        refreshRuntimeState(context)
    }

    fun uninstall(packageName: String, userId: Int = DEFAULT_USER_ID) {
        try {
            if (_runtimeState.value.packageName == packageName) {
                _runtimeState.value = VirtualGuestRuntime(null, null, -1, isRunning = false)
            }
            BlackBoxCore.get().uninstallPackageAsUser(packageName, userId)
            AppLogger.i(TAG, "uninstalled from VA: $packageName")
        } catch (e: Exception) {
            AppLogger.e(TAG, "uninstall failed for $packageName", e)
        }
    }

    private fun isActivityRunning(packageName: String, userId: Int): Boolean {
        return try {
            BlackBoxCore.isRunningApplication(packageName, userId)
        } catch (_: Exception) {
            false
        }
    }

    /** Guest is considered running if its process exists OR BlackBox still has a foreground task. */
    fun isGuestAlive(context: Context, packageName: String, userId: Int = DEFAULT_USER_ID): Boolean {
        if (resolveGuestPid(context, packageName, userId) > 0) return true
        if (isActivityRunning(packageName, userId)) return true
        val st = _runtimeState.value
        return st.packageName == packageName && st.isRunning &&
            System.currentTimeMillis() - lastLaunchAtMs < LAUNCH_GRACE_MS
    }

    fun listInstalled(context: Context, userId: Int = DEFAULT_USER_ID): List<String> {
        val hostPkg = context.packageName
        return try {
            BlackBoxCore.get()
                .getInstalledApplications(PackageManager.GET_META_DATA, userId)
                .mapNotNull { it.packageName }
                .filter { it != hostPkg }
        } catch (e: Exception) {
            AppLogger.e(TAG, "listInstalled failed", e)
            emptyList()
        }
    }

    fun listRunning(context: Context, userId: Int = DEFAULT_USER_ID): List<GuestProcess> {
        val pm = context.packageManager
        val results = mutableListOf<GuestProcess>()
        for (pkg in listInstalled(context, userId)) {
            val pid = resolveGuestPid(context, pkg, userId)
            val running = pid > 0 || isActivityRunning(pkg, userId)
            val appName = try {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            } catch (_: Exception) {
                pkg
            }
            if (running) {
                results.add(GuestProcess(pkg, appName, pid, userId, true))
            }
        }
        return results
    }

    fun refreshRuntimeState(context: Context) {
        val current = _runtimeState.value.packageName ?: return
        val pid = resolveGuestPid(context, current)
        val processAlive = pid > 0
        val activityUp = isActivityRunning(current, DEFAULT_USER_ID)
        val inGrace = System.currentTimeMillis() - lastLaunchAtMs < LAUNCH_GRACE_MS
        val running = processAlive || activityUp || (_runtimeState.value.isRunning && inGrace)
        if (_runtimeState.value.isRunning && !running && !inGrace) {
            AppLogger.d(TAG, "Guest $current no longer alive (pid=$pid)")
        }
        _runtimeState.value = _runtimeState.value.copy(
            isRunning = running,
            pid = if (pid > 0) pid else _runtimeState.value.pid
        )
    }

    fun resolveGuestPid(context: Context, packageName: String, userId: Int = DEFAULT_USER_ID): Int {
        val hostPkg = BlackBoxCore.getHostPkg()

        val am = context.getSystemService(ActivityManager::class.java)
        val processes = am?.runningAppProcesses.orEmpty()
        for (info in processes) {
            if (info.processName == packageName) {
                return info.pid
            }
            if (info.pkgList?.contains(packageName) == true &&
                (info.processName.startsWith("$hostPkg:p") || info.processName == packageName)
            ) {
                return info.pid
            }
        }

        val procRoot = BEnvironment.getProcDir()
        if (procRoot.isDirectory) {
            procRoot.listFiles()?.forEach { dir ->
                val bpid = dir.name.toIntOrNull() ?: return@forEach
                val proxyProc = ProxyManifest.getProcessName(bpid)
                val pid = BProcessManagerService.getPid(context, proxyProc)
                if (pid <= 0) return@forEach
                val cmdlineFile = File(dir, "cmdline")
                if (!cmdlineFile.exists()) return@forEach
                val stored = cmdlineFile.readBytes()
                    .toString(Charsets.UTF_8)
                    .trim { it <= ' ' }
                if (stored == packageName || stored.startsWith("$packageName:")) {
                    return pid
                }
            }
        }

        val proxyPids = processes
            .filter { it.processName.startsWith("$hostPkg:p") }
            .map { it.pid }
        if (proxyPids.size == 1 && isActivityRunning(packageName, userId)) {
            return proxyPids[0]
        }

        if (com.topjohnwu.superuser.Shell.isAppGrantedRoot() == true) {
            ProcessLister.findPidForPackageRoot(packageName)?.let { return it }
        }
        ProcessLister.findPidForPackage(packageName)?.let { return it }

        return -1
    }
}
