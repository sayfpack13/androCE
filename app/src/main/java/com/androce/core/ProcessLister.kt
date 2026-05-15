package com.androce.core

import com.androce.model.ProcessInfo
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ProcessLister {

    suspend fun listProcesses(): List<ProcessInfo> = withContext(Dispatchers.IO) {
        val results = mutableListOf<ProcessInfo>()
        try {
            // Single shell script: iterate all numeric /proc dirs and print "pid|name"
            val script = """
                for d in /proc/[0-9]*; do
                  pid="${'$'}{d##*/}"
                  name=$(tr -d '\0' < "${'$'}d/cmdline" 2>/dev/null | cut -d' ' -f1)
                  if [ -z "${'$'}name" ]; then
                    name=$(cat "${'$'}d/comm" 2>/dev/null)
                  fi
                  if [ -n "${'$'}name" ]; then
                    echo "${'$'}pid|${'$'}name"
                  fi
                done
            """.trimIndent()

            val result = Shell.cmd(script).exec()
            AppLogger.d("ProcessLister", "shell success=${result.isSuccess} lines=${result.out.size}")

            for (line in result.out) {
                val parts = line.trim().split("|", limit = 2)
                if (parts.size < 2) continue
                val pid = parts[0].toIntOrNull() ?: continue
                val name = parts[1].trim().trimEnd('\u0000')
                if (name.isBlank()) continue
                results.add(
                    ProcessInfo(
                        pid = pid,
                        name = name.substringAfterLast('/'),
                        packageName = name
                    )
                )
            }
            AppLogger.d("ProcessLister", "Processes found: ${results.size}")
        } catch (e: Exception) {
            AppLogger.e("ProcessLister", "listProcesses failed", e)
        }
        results.sortedBy { it.name }
    }
}
