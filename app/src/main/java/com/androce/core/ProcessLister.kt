package com.androce.core

import android.content.Context
import android.content.pm.PackageManager
import com.androce.core.virtual.VirtualEngineFacade
import com.androce.core.virtual.VirtualSpaceManager
import com.androce.model.ProcessInfo
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ProcessLister {

    suspend fun listProcesses(context: Context, pm: PackageManager?): List<ProcessInfo> = withContext(Dispatchers.IO) {
        // If operationMode is forced to virtual, or if we don't have root, list virtual guest processes.
        val useVirtual = AppPrefs.operationMode == "virtual" || (AppPrefs.operationMode == "auto" && Shell.getShell().isRoot == false)
        if (useVirtual) {
            AppLogger.d("ProcessLister", "Virtual mode — listing BlackBox guest processes")
            VirtualEngineFacade.refreshRuntimeState(context)
            val installed = try {
                VirtualSpaceManager.getInstalledVirtualApps(context)
            } catch (_: Exception) {
                emptyList()
            }
            val runningByPkg = VirtualEngineFacade.listRunning(context).associateBy { it.packageName }

            val results = mutableListOf<ProcessInfo>()
            for (meta in installed) {
                val guest = runningByPkg[meta.packageName]
                val alive = guest != null || VirtualEngineFacade.isGuestAlive(context, meta.packageName)
                if (alive) {
                    var pid = guest?.pid?.takeIf { it > 0 }
                        ?: VirtualEngineFacade.resolveGuestPid(context, meta.packageName).takeIf { it > 0 }
                        ?: findPidForPackage(meta.packageName)
                        ?: 0
                    val label = if (pid > 0) meta.appName else "${meta.appName} (running, resolving PID…)"
                    results.add(
                        ProcessInfo(
                            pid = pid,
                            name = meta.packageName.substringAfterLast('.'),
                            packageName = meta.packageName,
                            appName = label,
                            isVirtual = true
                        )
                    )
                } else {
                    results.add(
                        ProcessInfo(
                            pid = 0,
                            name = meta.packageName.substringAfterLast('.'),
                            packageName = meta.packageName,
                            appName = "${meta.appName} (not running)",
                            isVirtual = true
                        )
                    )
                }
            }
            return@withContext results.sortedBy { it.appName?.lowercase() ?: it.name.lowercase() }
        }

        val results = mutableListOf<ProcessInfo>()
        try {
            // Fast path: single ps command — returns all processes in one syscall
            val psResult = Shell.cmd(
                "ps -A -o PID,UID,NAME 2>/dev/null | awk 'NR>1 && \$2>=10000 {print \$1\"|\"\$3}'"
            ).exec()

            AppLogger.d("ProcessLister", "ps result: success=${psResult.isSuccess} lines=${psResult.out.size}")

            if (psResult.isSuccess && psResult.out.isNotEmpty()) {
                for (line in psResult.out) {
                    val parts = line.trim().split("|", limit = 2)
                    if (parts.size < 2) continue
                    val pid = parts[0].trim().toIntOrNull() ?: continue
                    val name = parts[1].trim()
                    if (name.isBlank()) continue
                    // ps NAME is truncated to 15 chars — read full cmdline from /proc when needed
                    val fullName = fullCmdline(pid) ?: name
                    val (cleanPkg, appName) = resolveAppInfo(pm, fullName)
                    results.add(ProcessInfo(pid = pid, name = cleanPkg.substringAfterLast('/'), packageName = cleanPkg, appName = appName))
                }
            } else {
                // Fallback: per-PID loop for stripped ROMs that lack ps
                AppLogger.w("ProcessLister", "ps unavailable, using fallback loop")
                val script = """
                    for d in /proc/[0-9]*; do
                      pid="${'$'}{d##*/}"
                      cmdline=$(tr -d '\0' < "${'$'}d/cmdline" 2>/dev/null | cut -d' ' -f1)
                      [ -z "${'$'}cmdline" ] && continue
                      uid=$(awk '/^Uid:/{print ${'$'}2}' "${'$'}d/status" 2>/dev/null)
                      [ -z "${'$'}uid" ] && continue
                      [ "${'$'}uid" -lt 10000 ] && continue
                      echo "${'$'}pid|${'$'}cmdline"
                    done
                """.trimIndent()
                val fallbackResult = Shell.cmd(script).exec()
                for (line in fallbackResult.out) {
                    val parts = line.trim().split("|", limit = 2)
                    if (parts.size < 2) continue
                    val pid = parts[0].toIntOrNull() ?: continue
                    val name = parts[1].trim().trimEnd('\u0000')
                    if (name.isBlank()) continue
                    val (cleanPkg, appName) = resolveAppInfo(pm, name)
                    results.add(ProcessInfo(pid = pid, name = name.substringAfterLast('/'), packageName = cleanPkg, appName = appName))
                }
            }

            AppLogger.d("ProcessLister", "Processes found: ${results.size}")
        } catch (e: Exception) {
            AppLogger.e("ProcessLister", "listProcesses failed", e)
        }
        results.sortedBy { it.appName?.lowercase() ?: it.name.lowercase() }
    }

    /**
     * Returns the main PID for the given package name.
     * Tries shell pidof first (works from adb shell / root), then falls back to
     * scanning /proc dir names with cmdline check (works if SELinux allows it).
     * Returns null if the process is not found or not readable.
     */
    fun findPidForPackage(packageName: String): Int? {
        if (Shell.isAppGrantedRoot() == true) {
            val rootPid = findPidForPackageRoot(packageName)
            if (rootPid != null) return rootPid
        }
        // Try pidof via Runtime — works on some devices, blocked on others
        try {
            val proc = Runtime.getRuntime().exec(arrayOf("pidof", packageName))
            val output = proc.inputStream.bufferedReader().readLine()?.trim()
            proc.waitFor()
            val pid = output?.split("\\s+".toRegex())?.mapNotNull { it.toIntOrNull() }?.minOrNull()
            if (pid != null) return pid
        } catch (_: Exception) {}

        // Fallback: scan /proc dirs + read cmdline (works when SELinux allows cross-uid reads)
        return try {
            java.io.File("/proc").listFiles()
                ?.mapNotNull { it.name.toIntOrNull()?.let { pid -> pid to it } }
                ?.sortedBy { it.first }
                ?.firstOrNull { (_, dir) ->
                    try {
                        val b = java.io.File(dir, "cmdline").readBytes()
                        val e = b.indexOfFirst { it == 0.toByte() }.takeIf { it >= 0 } ?: b.size
                        if (e == 0) return@firstOrNull false
                        String(b, 0, e).substringBefore(':').trim() == packageName
                    } catch (_: Exception) { false }
                }?.first
        } catch (_: Exception) { null }
    }

    /** Root shell pidof + /proc cmdline scan (needed for BlackBox VA guest PIDs). */
    fun findPidForPackageRoot(packageName: String): Int? {
        return try {
            val safePkg = packageName.replace("'", "")
            val pidof = Shell.cmd("pidof '$safePkg' 2>/dev/null").exec()
            val fromPidof = pidof.out.joinToString(" ").trim()
                .split(Regex("\\s+"))
                .mapNotNull { it.toIntOrNull() }
                .minOrNull()
            if (fromPidof != null) return fromPidof

            val scan = Shell.cmd(
                """
                for d in /proc/[0-9]*; do
                  pid=${'$'}{d##*/}
                  cmd=$(tr -d '\0' < "${'$'}d/cmdline" 2>/dev/null | cut -d: -f1)
                  [ "${'$'}cmd" = '$safePkg' ] && echo ${'$'}pid && break
                done
                """.trimIndent()
            ).exec()
            scan.out.firstOrNull()?.trim()?.toIntOrNull()
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Scans /proc for a process matching the given UID.
     * Uses /proc/pid/status which is world-readable on all Android versions unlike cmdline.
     * Returns the lowest (main process) PID found, or null if not running.
     */
    fun findPidForUid(uid: Int): Int? {
        return try {
            java.io.File("/proc").listFiles()
                ?.mapNotNull { it.name.toIntOrNull()?.let { pid -> pid to it } }
                ?.sortedBy { it.first }
                ?.firstOrNull { (_, pidDir) ->
                    try {
                        java.io.File(pidDir, "status").readLines().any { line ->
                            line.startsWith("Uid:") && line.split("\\s+".toRegex()).getOrNull(1)?.trim()?.toIntOrNull() == uid
                        }
                    } catch (_: Exception) { false }
                }?.first
        } catch (_: Exception) { null }
    }

    /**
     * Finds the PID of a running app via ActivityManager.getRunningAppProcesses().
     * This works without root on most Android versions (though may return null
     * on some restricted ROMs or Android 12+ with background restrictions).
     */
    fun findPidViaActivityManager(packageName: String?): Int? {
        if (packageName.isNullOrBlank()) return null
        return try {
            // We need a Context to get ActivityManager — use app context from App
            val context = try { com.androce.AndroCEApp.instance } catch (_: Exception) { null }
            if (context == null) {
                AppLogger.d("ProcessLister", "findPidViaActivityManager: no app context available")
                return null
            }
            val activityManager = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            val procs = activityManager?.runningAppProcesses
            if (procs != null) {
                for (proc in procs) {
                    if (proc.processName == packageName || proc.processName.startsWith("$packageName:")) {
                        return proc.pid
                    }
                }
            }
            null
        } catch (_: Exception) { null }
    }

    fun isAppRunning(context: Context, packageName: String): Boolean {
        return try {
            val usm = context.getSystemService(android.app.usage.UsageStatsManager::class.java)
            val now = System.currentTimeMillis()
            val stats = usm.queryUsageStats(
                android.app.usage.UsageStatsManager.INTERVAL_DAILY,
                now - 10_000L, now
            )
            stats?.any { it.packageName == packageName && it.lastTimeUsed > (now - 10_000L) } == true
        } catch (_: Exception) { false }
    }

    private fun fullCmdline(pid: Int): String? {
        return try {
            val bytes = java.io.File("/proc/$pid/cmdline").readBytes()
            val end = bytes.indexOfFirst { it == 0.toByte() }.takeIf { it >= 0 } ?: bytes.size
            val full = String(bytes, 0, end).trim()
            if (full.isBlank()) null else full
        } catch (_: Exception) {
            null
        }
    }

    private fun resolveAppInfo(pm: PackageManager?, rawName: String): Pair<String, String?> {
        val cleanPackage = rawName.substringBefore(':').substringAfterLast('/')
        if (pm == null) return Pair(cleanPackage, null)
        val label = try {
            val info = pm.getApplicationInfo(cleanPackage, 0)
            pm.getApplicationLabel(info)?.toString()
        } catch (_: Exception) {
            null
        }
        return Pair(cleanPackage, label)
    }
}
