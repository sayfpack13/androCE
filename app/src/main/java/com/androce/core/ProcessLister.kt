package com.androce.core

import android.content.pm.PackageManager
import com.androce.model.ProcessInfo
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ProcessLister {

    suspend fun listProcesses(pm: PackageManager?): List<ProcessInfo> = withContext(Dispatchers.IO) {
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
