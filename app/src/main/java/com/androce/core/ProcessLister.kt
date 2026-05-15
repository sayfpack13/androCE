package com.androce.core

import com.androce.model.ProcessInfo
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ProcessLister {

    suspend fun listProcesses(): List<ProcessInfo> = withContext(Dispatchers.IO) {
        val results = mutableListOf<ProcessInfo>()
        try {
            val pidListResult = Shell.cmd("ls /proc").exec()
            val pids = pidListResult.out
                .flatMap { it.trim().split("\\s+".toRegex()) }
                .mapNotNull { it.toIntOrNull() }
                .filter { it > 0 }

            for (pid in pids) {
                val name = readProcessName(pid) ?: continue
                if (name.isBlank()) continue
                results.add(
                    ProcessInfo(
                        pid = pid,
                        name = name.substringAfterLast('/'),
                        packageName = name
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        results.sortedBy { it.name }
    }

    private fun readProcessName(pid: Int): String? {
        return try {
            val cmdlineResult = Shell.cmd("cat /proc/$pid/cmdline 2>/dev/null").exec()
            val cmdline = cmdlineResult.out.joinToString("").trim().trimEnd('\u0000')
            if (cmdline.isNotEmpty()) cmdline
            else {
                val commResult = Shell.cmd("cat /proc/$pid/comm 2>/dev/null").exec()
                commResult.out.joinToString("").trim()
            }
        } catch (e: Exception) {
            null
        }
    }
}
