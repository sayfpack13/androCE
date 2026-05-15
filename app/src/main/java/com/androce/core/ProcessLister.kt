package com.androce.core

import com.androce.model.ProcessInfo
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ProcessLister {

    suspend fun listProcesses(): List<ProcessInfo> = withContext(Dispatchers.IO) {
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
                    results.add(ProcessInfo(pid = pid, name = name.substringAfterLast('/'), packageName = name))
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
                    results.add(ProcessInfo(pid = pid, name = name.substringAfterLast('/'), packageName = name))
                }
            }

            AppLogger.d("ProcessLister", "Processes found: ${results.size}")
        } catch (e: Exception) {
            AppLogger.e("ProcessLister", "listProcesses failed", e)
        }
        results.sortedBy { it.name }
    }
}
