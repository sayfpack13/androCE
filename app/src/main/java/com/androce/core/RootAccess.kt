package com.androce.core

import com.topjohnwu.superuser.Shell

/**
 * libsu's [Shell.isAppGrantedRoot] is false on some Magisk setups even when [Shell.getShell] is root.
 * Memory scan only needs a working root shell (same as [MemoryReader] uses).
 */
object RootAccess {

    fun hasRoot(): Boolean {
        return try {
            Shell.getShell()
            if (Shell.isAppGrantedRoot() == true) return true
            if (Shell.getShell().isRoot) return true
            Shell.cmd("id -u 2>/dev/null").exec().out.firstOrNull()?.trim() == "0"
        } catch (_: Exception) {
            false
        }
    }
}
