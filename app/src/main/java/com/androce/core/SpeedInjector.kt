package com.androce.core

import android.util.Log
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File

object SpeedInjector {
    private const val TAG = "SpeedInjector"
    private const val SHM_PATH = "/data/local/tmp/speedhack_shm"
    private const val LIB_NAME = "libspeedhook.so"
    private const val TMP_INJECTOR = "/data/local/tmp/speedinjector"
    private const val TMP_LIB = "/data/local/tmp/libspeedhook.so"

    private var libraryPath: String? = null
    private var injectorPath: String? = null

    fun init(context: android.content.Context) {
        val filesDir = context.filesDir
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val possiblePaths = mutableListOf(
            File(nativeLibDir, LIB_NAME),
            File(filesDir, LIB_NAME)
        )

        File(nativeLibDir).listFiles()?.forEach { file ->
            if (file.name.contains("speedhook")) {
                possiblePaths.add(0, file)
            }
        }

        libraryPath = possiblePaths.firstOrNull { it.exists() }?.absolutePath
        if (libraryPath == null) {
            extractLibraryFromApk(context)
        }
        // Remove stale root-owned copy that blocks app writes (EACCES on reinstall).
        Shell.cmd("rm -f '${File(filesDir, "speedinjector").absolutePath}'").exec()
        findOrExtractInjector(context)
        if (injectorPath == null) {
            Log.e(TAG, "Injector not available — grant root and reinstall the app")
        }
    }

    private fun resolveInjectorAbi(context: android.content.Context): String? {
        for (abi in android.os.Build.SUPPORTED_ABIS) {
            try {
                context.assets.open("injectors/$abi/speedinjector").close()
                return abi
            } catch (_: Exception) {
                /* try next ABI */
            }
        }
        return null
    }

    /** Stage injector under /data/local/tmp (executable); app-private dirs are not writable/executable on many ROMs. */
    private fun findOrExtractInjector(context: android.content.Context) {
        val abi = resolveInjectorAbi(context)
        if (abi == null) {
            Log.e(TAG, "No speedinjector binary in APK assets for ABIs: ${android.os.Build.SUPPORTED_ABIS.joinToString()}")
            return
        }

        if (stageInjectorToTmp(context, abi)) {
            injectorPath = TMP_INJECTOR
            return
        }

        // Fallback: cache dir is app-writable; root copies to tmp and marks executable.
        val cacheFile = File(context.cacheDir, "speedinjector")
        try {
            Shell.cmd("rm -f '${cacheFile.absolutePath}'").exec()
            context.assets.open("injectors/$abi/speedinjector").use { input ->
                cacheFile.outputStream().use { output -> input.copyTo(output) }
            }
            val copy = Shell.cmd(
                "cp '${cacheFile.absolutePath}' '$TMP_INJECTOR' && chmod 755 '$TMP_INJECTOR' && test -s '$TMP_INJECTOR'"
            ).exec()
            if (copy.isSuccess) {
                injectorPath = TMP_INJECTOR
            } else {
                Log.e(TAG, "Failed to copy injector to $TMP_INJECTOR: ${copy.err}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract injector from assets for $abi", e)
        }
    }

    private fun stageInjectorToTmp(context: android.content.Context, abi: String): Boolean {
        val apkPath = context.applicationInfo.sourceDir
        val entry = "assets/injectors/$abi/speedinjector"
        Shell.cmd("rm -f '$TMP_INJECTOR'").exec()
        val result = Shell.cmd(
            "unzip -p '$apkPath' '$entry' > '$TMP_INJECTOR' && chmod 755 '$TMP_INJECTOR' && test -s '$TMP_INJECTOR'"
        ).exec()
        if (!result.isSuccess) {
            Log.e(TAG, "unzip injector failed: ${result.err.joinToString()}")
        }
        return result.isSuccess
    }

    private fun extractLibraryFromApk(context: android.content.Context): Boolean {
        return try {
            val abi = android.os.Build.SUPPORTED_ABIS[0] ?: "arm64-v8a"
            val apkPath = context.applicationInfo.sourceDir
            val targetFile = File(context.filesDir, LIB_NAME)
            val libPathInApk = "lib/$abi/$LIB_NAME"

            val result = Shell.cmd(
                "unzip -p '$apkPath' $libPathInApk > '${targetFile.absolutePath}' && chmod 755 '${targetFile.absolutePath}'"
            ).exec()

            if (result.isSuccess && targetFile.exists()) {
                libraryPath = targetFile.absolutePath
                true
            } else {
                Log.e(TAG, "Failed to extract library: ${result.err}")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting library", e)
            false
        }
    }

    suspend fun inject(pid: Int, processName: String): Boolean = withContext(Dispatchers.IO) {
        try {
            SpeedControl.setInjecting()
            withTimeout(35_000) {
                val libPath = libraryPath ?: run {
                    SpeedControl.setFailed("Speed hook library not found")
                    return@withTimeout false
                }

                val injector = injectorPath ?: run {
                    SpeedControl.setFailed("Injector binary not found — reinstall the app")
                    return@withTimeout false
                }

                setupSharedMemory()

                val injectLibPath = prepareLibraryForTarget(pid, processName, libPath)
                    ?: run {
                        SpeedControl.setFailed("Could not prepare library for target process")
                        return@withTimeout false
                    }

                val injection = performInjection(pid, injector, injectLibPath)

                if (injection.success) {
                    writeSpeedToShm(SpeedControl.state.value.speedMultiplier)
                    SpeedControl.setActive(pid, processName)
                    Log.i(TAG, "OK pid=$pid $processName")
                } else {
                    val msg = injection.error
                        ?: "Injection failed — grant root, keep target app running, then retry"
                    Log.e(TAG, "FAIL pid=$pid $processName: $msg")
                    SpeedControl.setFailed(msg)
                }

                injection.success
            }
        } catch (_: TimeoutCancellationException) {
            Log.e(TAG, "FAIL pid=$pid $processName: shell timeout")
            SpeedControl.setFailed(
                "Injection timed out — stay in androCE until it finishes (don't open the game mid-inject)"
            )
            false
        } catch (e: Exception) {
            Log.e(TAG, "Injection failed", e)
            SpeedControl.setFailed(e.message ?: "Unknown error")
            false
        }
    }

    /** Copy hook library into a path the target app can dlopen (owned by target UID). */
    private fun prepareLibraryForTarget(pid: Int, processName: String, libPath: String): String? {
        val packageName = processName.trim().ifBlank {
            Shell.cmd("tr '\\0' '\\n' < /proc/$pid/cmdline | head -1").exec()
                .out.firstOrNull()?.trim().orEmpty()
        }
        if (packageName.isBlank()) {
            Log.e(TAG, "Could not resolve package name for pid $pid")
            return null
        }

        val uid = Shell.cmd("stat -c %u /proc/$pid").exec().out.firstOrNull()?.trim() ?: "0"
        val gid = Shell.cmd("stat -c %g /proc/$pid").exec().out.firstOrNull()?.trim() ?: uid

        val destPaths = listOf(
            TMP_LIB,
            "/data/user/0/$packageName/files/$LIB_NAME",
            "/data/data/$packageName/files/$LIB_NAME"
        )

        for (dest in destPaths) {
            val parent = dest.substringBeforeLast('/')
            val cmd = buildString {
                append("mkdir -p '$parent' && cp '$libPath' '$dest' && chmod 755 '$dest'")
                if (dest == TMP_LIB) {
                    append(" && chcon u:object_r:shell_data_file:s0 '$dest' 2>/dev/null || true")
                } else {
                    append(" && chown $uid:$gid '$dest'")
                    append(" && chcon u:object_r:app_data_file:s0 '$dest' 2>/dev/null || true")
                }
            }
            val result = Shell.cmd(cmd).exec()
            if (result.isSuccess && Shell.cmd("test -r '$dest'").exec().isSuccess) {
                return dest
            }
        }

        Log.e(TAG, "Failed to stage library for $packageName")
        return null
    }

    private fun setupSharedMemory(): Boolean {
        try {
            Shell.cmd("rm -f $SHM_PATH").exec()
            Shell.cmd("dd if=/dev/zero of=$SHM_PATH bs=4096 count=1 2>/dev/null && chmod 666 $SHM_PATH").exec()
            writeSpeedToShm(1.0f)
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Shared memory setup failed", e)
            return false
        }
    }

    fun writeSpeedToShm(speed: Float) {
        try {
            val speedBytes = java.nio.ByteBuffer.allocate(4)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .putFloat(speed)
                .array()

            val shmFile = File(SHM_PATH)
            if (shmFile.exists()) {
                try {
                    java.io.RandomAccessFile(shmFile, "rw").use { raf ->
                        raf.seek(0)
                        raf.write(speedBytes)
                    }
                    return
                } catch (_: Exception) { /* shell fallback */ }
            }

            val octal = speedBytes.joinToString("") { b ->
                "\\$(Integer.toOctalString((b.toInt() and 0xFF) or 0x100).substring(1))"
            }
            Shell.cmd("printf '$octal' > $SHM_PATH").exec()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write speed to shared memory", e)
        }
    }

    private data class InjectionResult(val success: Boolean, val error: String? = null)

    private fun performInjection(pid: Int, injector: String, libPath: String): InjectionResult {
        val check = Shell.cmd("test -x '$injector'").exec()
        if (!check.isSuccess) {
            return InjectionResult(false, "Injector missing at $injector — reinstall the app")
        }

        val result = Shell.cmd("'$injector' $pid '$libPath' 2>&1").exec()
        val output = (result.out + result.err).joinToString("\n")
        val injectLine = output.lines().firstOrNull { it.contains("ANDROCE_INJECT:") }

        if (!output.contains("ANDROCE_INJECT: OK")) {
            /* fall through to failure handling */
        } else if (!verifyInjection(pid)) {
            return InjectionResult(
                false,
                "Library not loaded into game — try setenforce 0, then activate again"
            )
        } else {
            return InjectionResult(true)
        }

        val failLine = output.lines().firstOrNull { it.contains("ANDROCE_INJECT: FAIL") }
        if (failLine != null) {
            Log.e(TAG, failLine.trim())
            if (failLine.contains("timeout", ignoreCase = true)) {
                return InjectionResult(
                    false,
                    "Timed out — stay in androCE until inject completes"
                )
            }
        } else if (injectLine != null) {
            Log.e(TAG, injectLine.trim())
        }

        val detail = failLine?.substringAfter("FAIL")?.trim()?.ifBlank { null }
        return InjectionResult(
            success = false,
            error = detail ?: "Could not load libspeedhook into PID $pid (ptrace/SELinux?)"
        )
    }

    private fun injectViaDebugger(pid: Int, libPath: String): Boolean {
        val gdbCheck = Shell.cmd("which gdb 2>/dev/null").exec()
        if (gdbCheck.out.isEmpty()) return false

        val gdbScript = """
            set timeout 5
            attach $pid
            call (void*)dlopen("$libPath", 2)
            detach
            quit
        """.trimIndent()

        Shell.cmd("echo '$gdbScript' | gdb -q 2>/dev/null").exec()
        return verifyInjection(pid)
    }

    fun isProcessAlive(pid: Int): Boolean =
        pid > 0 && Shell.cmd("test -d /proc/$pid").exec().isSuccess

    private fun verifyInjection(pid: Int): Boolean {
        if (!isProcessAlive(pid)) return false
        val result = Shell.cmd(
            "grep -F 'libspeedhook.so' /proc/$pid/maps 2>/dev/null | grep -E 'r-xp|r--p|rw-p'"
        ).exec()
        return result.out.isNotEmpty()
    }

    /** Call when returning to the app or on a timer while speed hack is "active". */
    suspend fun validateActiveInjection(): Boolean = withContext(Dispatchers.IO) {
        val state = SpeedControl.state.value
        if (state.state != com.androce.core.SpeedHackState.ACTIVE) return@withContext true

        val pid = state.targetPid
        val ok = isProcessAlive(pid) && verifyInjection(pid)
        if (!ok) {
            reset()
            SpeedControl.setFailed(
                "Game restarted or hook unloaded — select the game again and tap Activate"
            )
        }
        ok
    }

    suspend fun updateSpeed(speed: Float) = withContext(Dispatchers.IO) {
        writeSpeedToShm(speed)
    }

    fun reset() {
        try {
            Shell.cmd("rm -f $SHM_PATH").exec()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cleanup shared memory", e)
        }
        SpeedControl.reset()
    }
}
