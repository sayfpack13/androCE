package com.androce.core

import android.content.Context
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
    const val HOOK_MODE_MONITOR = 0
    const val HOOK_MODE_LIBC = 1
    const val HOOK_MODE_PLT_GAME = 2
    const val HOOK_MODE_UNIVERSAL = 3
    const val HOOK_MODE_PLT_CLOCK_ONLY = 4

    private const val LIB_NAME = "libspeedhook.so"
    private const val TMP_INJECTOR = "/data/local/tmp/speedinjector"
    private const val TMP_LIB = "/data/local/tmp/libspeedhook.so"

    private var appContext: Context? = null
    private var libraryPath: String? = null
    private var injectorPath: String? = null
    private val stagedLibPaths = mutableSetOf<String>()
    private var lastHookMode = HOOK_MODE_PLT_GAME

    fun currentHookMode(): Int = SpeedControl.state.value.hookMethod.id

    fun init(context: Context) {
        SpeedControl.loadHookMethodFromPrefs()
        appContext = context.applicationContext
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
            extractLibraryFromApk(context, deviceAbi())
        }
        Shell.cmd("rm -f '${File(filesDir, "speedinjector").absolutePath}'").exec()
        stageInjector(context, deviceAbi())
        if (injectorPath == null) {
            Log.e(TAG, "Injector not available — grant root and reinstall the app")
        }
    }

    private fun deviceAbi(): String =
        android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"

    /** 64 = arm64, 32 = armeabi — injector is ARM64-only today. */
    fun detectTargetElfClass(pid: Int): Int? {
        val line = Shell.cmd("od -An -tx1 -j4 -N1 /proc/$pid/exe 2>/dev/null").exec()
            .out.joinToString("").trim()
        return when (line) {
            "02" -> 64
            "01" -> 32
            else -> null
        }
    }

    fun targetAbiForPid(pid: Int): String? = when (detectTargetElfClass(pid)) {
        64 -> when {
            deviceAbi().contains("arm64") -> "arm64-v8a"
            deviceAbi().contains("x86_64") -> "x86_64"
            else -> null
        }
        32 -> when {
            deviceAbi().contains("arm64") || deviceAbi().contains("armeabi") -> "armeabi-v7a"
            deviceAbi().contains("x86") -> "x86"
            else -> null
        }
        else -> null
    }

    private fun ensureRoot(): Boolean {
        if (Shell.isAppGrantedRoot() != true) {
            Log.e(TAG, "Root not granted to com.androce")
            return false
        }
        val id = Shell.cmd("id").exec().out.joinToString(" ")
        if (!id.contains("uid=0")) {
            Log.e(TAG, "Shell is not root: $id")
            return false
        }
        return true
    }

    private fun stageInjector(context: Context, abi: String): Boolean {
        val apkPath = context.applicationInfo.sourceDir
        val entry = "assets/injectors/$abi/speedinjector"
        Shell.cmd("rm -f '$TMP_INJECTOR'").exec()
        val result = Shell.cmd(
            "unzip -p '$apkPath' '$entry' > '$TMP_INJECTOR' && chmod 755 '$TMP_INJECTOR' && test -s '$TMP_INJECTOR'"
        ).exec()
        if (result.isSuccess) {
            injectorPath = TMP_INJECTOR
            return true
        }
        try {
            val cacheFile = File(context.cacheDir, "speedinjector_$abi")
            context.assets.open("injectors/$abi/speedinjector").use { input ->
                cacheFile.outputStream().use { output -> input.copyTo(output) }
            }
            val copy = Shell.cmd(
                "cp '${cacheFile.absolutePath}' '$TMP_INJECTOR' && chmod 755 '$TMP_INJECTOR' && test -s '$TMP_INJECTOR'"
            ).exec()
            if (copy.isSuccess) {
                injectorPath = TMP_INJECTOR
                return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract injector for $abi", e)
        }
        Log.e(TAG, "Injector staging failed for ABI $abi: ${result.err.joinToString()}")
        return false
    }

    private fun libraryForTargetAbi(context: Context, abi: String): String? {
        val cacheFile = File(context.cacheDir, "speedhook_$abi.so")
        if (cacheFile.exists() && cacheFile.length() > 1024) {
            return cacheFile.absolutePath
        }
        val apkPath = context.applicationInfo.sourceDir
        val libPathInApk = "lib/$abi/$LIB_NAME"
        val result = Shell.cmd(
            "unzip -p '$apkPath' $libPathInApk > '${cacheFile.absolutePath}' && chmod 755 '${cacheFile.absolutePath}'"
        ).exec()
        return if (result.isSuccess && cacheFile.exists()) cacheFile.absolutePath else null
    }

    private fun extractLibraryFromApk(context: Context, abi: String): Boolean {
        val path = libraryForTargetAbi(context, abi) ?: return false
        libraryPath = path
        return true
    }

    fun resolveLivePid(packageName: String, fallbackPid: Int): Int? {
        if (isProcessAlive(fallbackPid)) {
            val owner = Shell.cmd("tr '\\0' ' ' < /proc/$fallbackPid/cmdline 2>/dev/null").exec()
                .out.joinToString("").trim()
            if (owner.contains(packageName)) return fallbackPid
        }
        val pidof = Shell.cmd("pidof '$packageName' 2>/dev/null").exec()
            .out.firstOrNull()?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.toIntOrNull()
        if (pidof != null && isProcessAlive(pidof)) return pidof
        return null
    }

    private inline fun withPermissiveSelinux(block: () -> Unit) {
        val mode = Shell.cmd("getenforce 2>/dev/null").exec().out.firstOrNull()?.trim()
        val wasEnforcing = mode.equals("Enforcing", ignoreCase = true)
        if (wasEnforcing) {
            val r = Shell.cmd("setenforce 0").exec()
            Log.i(TAG, "setenforce 0 -> success=${r.isSuccess} getenforce=${Shell.cmd("getenforce").exec().out}")
        }
        try {
            block()
        } finally {
            if (wasEnforcing) {
                Shell.cmd("setenforce 1").exec()
            }
        }
    }

    suspend fun inject(pid: Int, packageName: String, processName: String): Boolean = withContext(Dispatchers.IO) {
        try {
            SpeedControl.setInjecting()

            if (!ensureRoot()) {
                SpeedControl.setFailed("Root not granted — open your root manager and allow androCE")
                return@withContext false
            }

            val livePid = resolveLivePid(packageName, pid)
            if (livePid == null) {
                SpeedControl.setFailed("Target app is not running — open it first, then activate")
                return@withContext false
            }

            val targetAbi = targetAbiForPid(livePid)
            if (targetAbi == null) {
                SpeedControl.setFailed("Could not detect target app CPU type")
                return@withContext false
            }
            if (targetAbi == "armeabi-v7a" || targetAbi == "x86") {
                SpeedControl.setFailed("Target is 32-bit — speed hack needs a 64-bit app build")
                return@withContext false
            }

            val ctx = appContext
            if (ctx == null) {
                SpeedControl.setFailed("App not initialized")
                return@withContext false
            }
            if (!stageInjector(ctx, targetAbi)) {
                SpeedControl.setFailed("Injector missing for $targetAbi — reinstall androCE")
                return@withContext false
            }

            val libPath = libraryForTargetAbi(ctx, targetAbi)
                ?: libraryPath
                ?: run {
                    SpeedControl.setFailed("Speed hook library not found for $targetAbi")
                    return@withContext false
                }

            val injector = injectorPath ?: run {
                SpeedControl.setFailed("Injector binary not found — reinstall the app")
                return@withContext false
            }

            setupSharedMemory()
            val hookMode = currentHookMode()
            lastHookMode = hookMode
            writeSpeedToShm(SpeedControl.state.value.speedMultiplier, hookMode)

            var injection = InjectionResult(false, "Injection failed")

            withTimeout(35_000) {
                withPermissiveSelinux {
                    val injectPaths = prepareLibraryForTarget(
                        livePid,
                        packageName.ifBlank { processName },
                        libPath
                    )
                    if (injectPaths.isEmpty()) {
                        SpeedControl.setFailed("Could not stage hook library for target app")
                        return@withTimeout false
                    }
                    for ((index, injectLibPath) in injectPaths.withIndex()) {
                        Log.i(TAG, "Inject try ${index + 1}/${injectPaths.size}: $injectLibPath")
                        injection = performInjection(livePid, injector, injectLibPath)
                        if (injection.success || verifyInjection(livePid)) break
                        if (index < injectPaths.lastIndex && isProcessAlive(livePid)) {
                            kotlinx.coroutines.delay(250)
                        }
                    }
                }
                injection.success || verifyInjection(livePid)
            }

            if (!injection.success && !verifyInjection(livePid) && isProcessAlive(livePid)) {
                withTimeout(180_000) {
                    Log.i(TAG, "Native inject failed — setting up Frida in-app...")
                    val fridaReady = FridaSpeedHack.ensureReady(ctx) { line ->
                        Log.i(TAG, "Frida setup: $line")
                    }
                    if (fridaReady) {
                        withPermissiveSelinux {
                            injection = tryFridaFallback(ctx, livePid, packageName)
                        }
                    } else {
                        injection = InjectionResult(
                            false,
                            FridaSpeedHack.lastSetupError() ?: FridaSpeedHack.termuxSetupHint()
                        )
                    }
                    injection.success
                }
            }

            if (injection.success) {
                writeSpeedToShm(SpeedControl.state.value.speedMultiplier, hookMode)
                SpeedControl.setActive(livePid, processName)
                Log.i(TAG, "OK pid=$livePid $processName abi=$targetAbi hookMode=$hookMode")
            } else {
                Log.e(TAG, "FAIL pid=$livePid $processName: ${injection.error}")
                SpeedControl.setFailed(injection.error ?: "Injection failed")
            }

            injection.success
        } catch (_: TimeoutCancellationException) {
            Log.e(TAG, "FAIL pid=$pid $processName: shell timeout")
            SpeedControl.setFailed("Injection timed out — reopen the target app and activate again")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Injection failed", e)
            SpeedControl.setFailed(e.message ?: "Unknown error")
            false
        }
    }

    private fun removeHookLibFromApkLibDir(packageName: String) {
        val pkg = packageName.trim()
        if (pkg.isBlank()) return
        Shell.cmd(
            "rm -f /data/app/*/$pkg-*/lib/arm64/$LIB_NAME " +
                "/data/app/*/$pkg-*/lib/arm/$LIB_NAME 2>/dev/null"
        ).exec()
    }

    private fun prepareLibraryForTarget(
        pid: Int,
        packageName: String,
        libPath: String
    ): List<String> {
        val pkg = packageName.trim().ifBlank {
            Shell.cmd("tr '\\0' '\\n' < /proc/$pid/cmdline | head -1").exec()
                .out.firstOrNull()?.trim().orEmpty()
        }
        if (pkg.isBlank()) {
            Log.e(TAG, "Could not resolve package name for pid $pid")
            return emptyList()
        }

        val uid = Shell.cmd("stat -c %u /proc/$pid").exec().out.firstOrNull()?.trim() ?: "0"
        val gid = Shell.cmd("stat -c %g /proc/$pid").exec().out.firstOrNull()?.trim() ?: uid

        val appLibDir = Shell.cmd(
            "grep -m1 -oE '/data/app[^ ]+/lib/arm64' /proc/$pid/maps 2>/dev/null || " +
                "grep -m1 -oE '/data/app[^ ]+/lib/arm' /proc/$pid/maps 2>/dev/null"
        ).exec().out.firstOrNull()?.trim()

        val apkLibDir = Shell.cmd(
            "for d in /data/app/*/$pkg-*; do " +
                "[ -d \"\$d/lib/arm64\" ] && echo \"\$d/lib/arm64\" && break; " +
                "[ -d \"\$d/lib/arm\" ] && echo \"\$d/lib/arm\" && break; " +
                "done"
        ).exec().out.firstOrNull()?.trim()

        val destPaths = buildList {
            apkLibDir?.let { add("$it/$LIB_NAME") }
            appLibDir?.let { add("$it/$LIB_NAME") }
            add("/data/data/$pkg/files/$LIB_NAME")
            add("/data/data/$pkg/lib/$LIB_NAME")
            add(TMP_LIB)
            add("/data/data/$pkg/code_cache/$LIB_NAME")
        }.distinct()

        val staged = mutableListOf<String>()
        for (dest in destPaths) {
            val parent = dest.substringBeforeLast('/')
            val cmd = buildString {
                append("mkdir -p '$parent' && cp '$libPath' '$dest' && chmod 755 '$dest'")
                if (dest == TMP_LIB) {
                    append(" && chcon u:object_r:shell_data_file:s0 '$dest' 2>/dev/null || true")
                } else {
                    append(" && chown $uid:$gid '$dest'")
                    append(" && chcon u:object_r:app_data_file:s0 '$dest' 2>/dev/null || true")
                    append(" && restorecon -F '$dest' 2>/dev/null || true")
                }
            }
            val result = Shell.cmd(cmd).exec()
            if (result.isSuccess && Shell.cmd("test -r '$dest'").exec().isSuccess) {
                stagedLibPaths.add(dest)
                staged.add(dest)
                Log.i(TAG, "Staged library at $dest")
            }
        }

        if (staged.isEmpty()) {
            Log.e(TAG, "Failed to stage library for $pkg")
            return emptyList()
        }

        return staged.sortedBy { path ->
            when {
                path.contains("/lib/arm64") || path.contains("/lib/arm/") -> 0
                path.contains("/files/") -> 1
                path.contains("/data/data") && path.contains("/lib/") && !path.contains("/lib/arm") -> 2
                path == TMP_LIB -> 3
                else -> 4
            }
        }
    }

    private fun setupSharedMemory(): Boolean {
        try {
            Shell.cmd("rm -f $SHM_PATH").exec()
            Shell.cmd("dd if=/dev/zero of=$SHM_PATH bs=4096 count=1 2>/dev/null && chmod 666 $SHM_PATH").exec()
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Shared memory setup failed", e)
            return false
        }
    }

    fun writeSpeedToShm(speed: Float, hookMode: Int = lastHookMode) {
        try {
            lastHookMode = hookMode
            val shmBytes = java.nio.ByteBuffer.allocate(8)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .putFloat(speed)
                .putInt(hookMode)
                .array()

            val shmFile = File(SHM_PATH)
            if (shmFile.exists()) {
                try {
                    java.io.RandomAccessFile(shmFile, "rw").use { raf ->
                        raf.seek(0)
                        raf.write(shmBytes)
                    }
                    return
                } catch (_: Exception) { /* shell fallback */ }
            }

            val octal = shmBytes.joinToString("") { b ->
                "\\${Integer.toOctalString((b.toInt() and 0xFF) or 0x100).substring(1)}"
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

        Log.i(
            TAG,
            "=== inject pid=$pid hookMode=${currentHookMode()} lib=$libPath ==="
        )
        val result = Shell.cmd("'$injector' $pid '$libPath' 2>&1").exec()
        val lines = result.out + result.err
        lines.filter { it.isNotBlank() }.forEach { line ->
            val t = line.trim()
            when {
                t.contains("ANDROCE_INJECT: FAIL") || t.contains("ANDROCE_DBG:") &&
                    (t.contains("crash") || t.contains("timeout") || t.contains("errno") ||
                        t.contains("invalid") || t.contains("failed") || t.contains("NULL")) ->
                    Log.e(TAG, t)
                t.startsWith("ANDROCE_") -> Log.i(TAG, t)
                else -> Log.d(TAG, "injector: $t")
            }
        }
        if (!result.isSuccess) {
            Log.e(TAG, "injector exit code=${result.code}")
        }

        val output = lines.joinToString("\n")

        val verified = verifyInjection(pid)
        if (output.contains("ANDROCE_INJECT: OK") || verified) {
            if (verified) {
                return InjectionResult(true)
            }
            return InjectionResult(
                false,
                "Hook reported OK but library not visible in memory — retry after reopening the app"
            )
        }

        val failLine = output.lines().lastOrNull { it.contains("ANDROCE_INJECT: FAIL") }
        if (failLine != null) {
            when {
                failLine.contains("crashed", ignoreCase = true) ||
                    failLine.contains("process not found", ignoreCase = true) ||
                    !isProcessAlive(pid) ->
                    return InjectionResult(
                        false,
                        "Target app crashed during injection — reopen it, then tap Activate again"
                    )
                failLine.contains("timeout", ignoreCase = true) ||
                    failLine.contains("did not stop", ignoreCase = true) ->
                    return InjectionResult(
                        false,
                        "Target did not pause for injection — close other debug tools and retry"
                    )
                failLine.contains("32-bit", ignoreCase = true) ->
                    return InjectionResult(false, "Target is 32-bit — use a 64-bit app")
                failLine.contains("not readable", ignoreCase = true) ->
                    return InjectionResult(false, "Library path not readable — reinstall androCE")
                failLine.contains("ptrace", ignoreCase = true) ->
                    return InjectionResult(
                        false,
                        "Ptrace blocked — disable other game tools, ensure root is allowed for androCE"
                    )
            }
        }

        if (!isProcessAlive(pid)) {
            return InjectionResult(
                false,
                "Target app crashed during injection — reopen it, then tap Activate again"
            )
        }

        val detail = failLine?.substringAfter("FAIL")?.trim()?.ifBlank { null }
        return InjectionResult(
            success = false,
            error = detail?.let { "Could not load hook: $it" }
                ?: android15InjectHint()
        )
    }

    private fun android15InjectHint(): String =
        "Native inject failed on this device. Open Settings → Status and tap Install next to Frida, then retry."

    private fun tryFridaFallback(context: Context, pid: Int, packageName: String): InjectionResult {
        Log.i(TAG, "Trying Frida fallback pid=$pid")
        val speed = SpeedControl.state.value.speedMultiplier
        if (FridaSpeedHack.attach(context, pid, packageName, speed)) {
            Log.i(TAG, "OK Frida speedhack pid=$pid (hooks via Termux, not libspeedhook.so)")
            return InjectionResult(true)
        }
        return InjectionResult(
            false,
            "Native inject failed. ${FridaSpeedHack.termuxSetupHint()}"
        )
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

    suspend fun validateActiveInjection(): Boolean = withContext(Dispatchers.IO) {
        val state = SpeedControl.state.value
        if (state.state != com.androce.core.SpeedHackState.ACTIVE) return@withContext true

        val pid = state.targetPid
        if (FridaSpeedHack.isAttached(pid) && isProcessAlive(pid)) return@withContext true
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
        val pid = SpeedControl.state.value.targetPid
        if (FridaSpeedHack.isAttached(pid)) {
            FridaSpeedHack.updateSpeed(pid, speed)
        } else {
            writeSpeedToShm(speed, currentHookMode())
        }
    }

    fun reset() {
        FridaSpeedHack.clear()
        try {
            // Write default speed (1.0f) to SHM instead of deleting it
            // This keeps the mmap valid if hook library is still loaded
            writeSpeedToShm(1.0f, lastHookMode)
            stagedLibPaths.toList().forEach { path ->
                Shell.cmd("rm -f '$path'").exec()
            }
            stagedLibPaths.clear()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cleanup shared memory", e)
        }
        SpeedControl.reset()
    }
}
