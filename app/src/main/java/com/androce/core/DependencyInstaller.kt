package com.androce.core

import android.content.Context
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Handles detection and installation of dependencies (Python) across different Linux environments.
 * Supports Termux, Magisk modules, and various Android Linux distributions.
 */
object DependencyInstaller {

    private const val TAG = "DependencyInstaller"

    data class InstallResult(
        val success: Boolean,
        val message: String,
        val requiresReboot: Boolean = false
    )

    data class PythonSource(
        val name: String,
        val path: String,
        val description: String,
        val installCommand: String?,
        val isAvailable: Boolean
    )

    data class TermuxDetection(
        val packageInstalled: Boolean,
        val packageName: String?,
        val dataDir: String?,
        val filesDir: String?,
        val prefix: String?,
        val bootstrapped: Boolean,
        val detail: String
    ) {
        val isReadyForPkg: Boolean get() = packageInstalled && bootstrapped && prefix != null
    }

    private val TERMUX_PACKAGE_CANDIDATES = listOf(
        "com.termux",
        "com.termux.api"
    )

    private val LEGACY_TERMUX_FILES = "/data/data/com.termux/files"
    private val LEGACY_TERMUX_PREFIX = "$LEGACY_TERMUX_FILES/usr"

    /**
     * Detects Termux via PackageManager (installed APK) and root shell (data paths / bootstrap).
     */
    suspend fun detectTermux(): TermuxDetection = withContext(Dispatchers.IO) {
        val pmPackage = TERMUX_PACKAGE_CANDIDATES.firstOrNull { isTermuxPackageInstalled(it) }

        var shellPackage: String? = null
        for (candidate in TERMUX_PACKAGE_CANDIDATES) {
            val listed = Shell.cmd("pm list packages $candidate 2>/dev/null").exec()
            if (listed.out.any { it.trim() == "package:$candidate" }) {
                shellPackage = candidate
                break
            }
        }
        if (shellPackage == null) {
            val grep = Shell.cmd("pm list packages 2>/dev/null | grep -E '^package:com\\.termux'").exec()
            shellPackage = grep.out.firstOrNull()
                ?.removePrefix("package:")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        }

        val packageName = pmPackage ?: shellPackage
        if (packageName == null) {
            return@withContext TermuxDetection(
                packageInstalled = false,
                packageName = null,
                dataDir = null,
                filesDir = null,
                prefix = null,
                bootstrapped = false,
                detail = "Termux package not found"
            )
        }

        val resolved = resolveTermuxPaths(packageName)
        val bootstrapped = isTermuxBootstrapped(resolved.prefix)

        TermuxDetection(
            packageInstalled = true,
            packageName = packageName,
            dataDir = resolved.dataDir,
            filesDir = resolved.filesDir,
            prefix = resolved.prefix,
            bootstrapped = bootstrapped,
            detail = when {
                resolved.prefix == null ->
                    "Termux installed ($packageName) but data folder not found — open Termux once"
                !bootstrapped ->
                    "Termux installed ($packageName) — open the app and wait for setup to finish"
                else ->
                    "Termux ready at ${resolved.prefix}"
            }
        )
    }

    private fun isTermuxPackageInstalled(packageName: String): Boolean {
        val ctx = AppLogger.applicationContext() ?: return false
        return try {
            ctx.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    private data class TermuxPaths(val dataDir: String?, val filesDir: String?, val prefix: String?)

    private fun resolveTermuxPaths(packageName: String): TermuxPaths {
        var dataDir: String? = null

        val dump = Shell.cmd("dumpsys package $packageName 2>/dev/null | grep -m1 'dataDir='").exec()
        dataDir = dump.out.firstOrNull()
            ?.substringAfter("dataDir=")
            ?.trim()
            ?.takeIf { it.startsWith("/") }

        if (dataDir == null) {
            val ls = Shell.cmd(
                "ls -d /data/data/$packageName /data/user/*/$packageName 2>/dev/null | head -1"
            ).exec()
            dataDir = ls.out.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        }

        var filesDir = dataDir?.let { "$it/files" }
        var prefix = filesDir?.let { "$it/usr" }

        if (!isTermuxBootstrapped(prefix)) {
            if (isTermuxBootstrapped(LEGACY_TERMUX_PREFIX)) {
                filesDir = LEGACY_TERMUX_FILES
                prefix = LEGACY_TERMUX_PREFIX
                if (dataDir == null) dataDir = "/data/data/com.termux"
            }
        }

        return TermuxPaths(dataDir, filesDir, prefix)
    }

    private fun isTermuxBootstrapped(prefix: String?): Boolean {
        if (prefix.isNullOrBlank()) return false
        val check = Shell.cmd(
            "if [ -x $prefix/bin/pkg ] || [ -x $prefix/bin/python3 ]; then echo BOOTSTRAPPED; else echo NO; fi"
        ).exec()
        return check.out.any { it.contains("BOOTSTRAPPED") }
    }

    private fun findInstalledTermuxPython(prefix: String): String? {
        val python3 = "$prefix/bin/python3"
        val check = Shell.cmd("$python3 --version 2>&1").exec()
        if (check.out.any { it.trim().startsWith("Python") }) return python3

        for (ver in listOf("3.12", "3.11", "3.10", "3.9", "3.8")) {
            val path = "$prefix/bin/python$ver"
            val verCheck = Shell.cmd("$path --version 2>&1").exec()
            if (verCheck.out.any { it.trim().startsWith("Python") }) return path
        }
        return null
    }

    private fun getBootstrapArch(): String {
        val abiList = Shell.cmd("getprop ro.product.cpu.abilist 2>/dev/null").exec()
            .out.firstOrNull()?.lowercase().orEmpty()
        val abi = abiList.split(",").firstOrNull().orEmpty()
        return when {
            abi.contains("arm64") || abi.contains("aarch64") -> "aarch64"
            abi.contains("armeabi") || abi == "arm" -> "arm"
            abi.contains("x86_64") -> "x86_64"
            abi.contains("x86") || abi.contains("i686") -> "i686"
            else -> "aarch64"
        }
    }

    private fun execLongShell(script: String, timeoutSeconds: Int = 300): Pair<Boolean, String> {
        return try {
            Shell.Builder.create()
                .setTimeout(timeoutSeconds.toLong())
                .build()
                .use { remote ->
                    val out = java.util.ArrayList<String>()
                    val err = java.util.ArrayList<String>()
                    val result = remote.newJob().add(script).to(out, err).exec()
                    val output = (out + err).joinToString("\n").trim()
                    result.isSuccess to output
                }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Long shell command failed", e)
            false to (e.message ?: "shell error")
        }
    }

    private fun prepareTermuxEnvironment(termux: TermuxDetection): Boolean {
        val dataDir = termux.dataDir ?: return false
        val filesDir = termux.filesDir ?: "$dataDir/files"
        val uidGid = Shell.cmd("stat -c '%u %g' $dataDir 2>/dev/null").exec()
            .out.firstOrNull()?.trim()?.split(" ")
            ?: return false
        val uid = uidGid.getOrNull(0) ?: return false
        val gid = uidGid.getOrNull(1) ?: uid
        val prep = """
            mkdir -p $filesDir/home $filesDir/usr-staging
            chown -R $uid:$gid $filesDir 2>/dev/null
            chmod 0711 $dataDir 2>/dev/null
            chmod 0711 $filesDir 2>/dev/null
        """.trimIndent()
        return Shell.cmd(prep).exec().isSuccess
    }

    /**
     * Downloads official Termux bootstrap and installs it to the Termux prefix (root, no UI).
     */
    private suspend fun bootstrapTermuxFromDownload(
        termux: TermuxDetection,
        log: suspend (String) -> Unit
    ): Boolean {
        val filesDir = termux.filesDir ?: termux.dataDir?.let { "$it/files" } ?: return false
        val dataDir = termux.dataDir ?: return false
        val arch = getBootstrapArch()
        log("Downloading Termux bootstrap ($arch)...")

        val script = """
            set -e
            FILES_DIR="$filesDir"
            DATA_DIR="$dataDir"
            STAGING="${'$'}FILES_DIR/usr-staging"
            PREFIX="${'$'}FILES_DIR/usr"
            TMPZIP="/data/local/tmp/androce-termux-bootstrap.zip"
            ARCH="$arch"
            UID=${'$'}(stat -c '%u' "${'$'}DATA_DIR")
            GID=${'$'}(stat -c '%g' "${'$'}DATA_DIR")

            rm -rf "${'$'}STAGING" "${'$'}PREFIX" "${'$'}TMPZIP"
            mkdir -p "${'$'}STAGING" "${'$'}FILES_DIR/home"

            download() {
              for url in "${'$'}@"; do
                if command -v curl >/dev/null 2>&1; then
                  curl -fL --connect-timeout 25 --max-time 300 -o "${'$'}TMPZIP" "${'$'}url" && return 0
                fi
                if command -v wget >/dev/null 2>&1; then
                  wget -q -O "${'$'}TMPZIP" "${'$'}url" && return 0
                fi
              done
              return 1
            }

            download \
              "https://packages.termux.dev/apt/bootstrap-${'$'}ARCH.zip" \
              "https://packages.termux.dev/bootstrap/bootstrap-${'$'}ARCH.zip" \
              "https://termux.net/bootstrap/bootstrap-${'$'}ARCH.zip" \
              "https://github.com/termux/termux-packages/releases/download/bootstrap-2024.09.15-r1%2Bapt-android-7/bootstrap-${'$'}ARCH.zip" \
              "https://github.com/termux/termux-packages/releases/download/bootstrap-2022.04.28-r5%2Bapt-android-7/bootstrap-${'$'}ARCH.zip" \
              || exit 11

            command -v unzip >/dev/null 2>&1 || exit 12
            unzip -qo "${'$'}TMPZIP" -d "${'$'}STAGING"

            if [ -f "${'$'}STAGING/SYMLINKS.txt" ]; then
              while IFS= read -r line || [ -n "${'$'}line" ]; do
                case "${'$'}line" in ''|\#*) continue;; esac
                old="${'$'}{line%%←*}"
                rel="${'$'}{line#*←}"
                mkdir -p "${'$'}STAGING/$(dirname "${'$'}rel")"
                ln -sf "${'$'}old" "${'$'}STAGING/${'$'}rel"
              done < "${'$'}STAGING/SYMLINKS.txt"
              rm -f "${'$'}STAGING/SYMLINKS.txt"
            fi

            if [ -d "${'$'}STAGING/bin" ]; then
              find "${'$'}STAGING/bin" -type f -exec chmod 700 {} + 2>/dev/null || true
            fi
            if [ -d "${'$'}STAGING/libexec" ]; then
              find "${'$'}STAGING/libexec" -type f -exec chmod 700 {} + 2>/dev/null || true
            fi

            mv "${'$'}STAGING" "${'$'}PREFIX"
            chown -R "${'$'}UID:${'$'}GID" "${'$'}FILES_DIR"
            chmod -R u+rwX,g+rwX "${'$'}FILES_DIR" 2>/dev/null || true
            rm -f "${'$'}TMPZIP"
            [ -x "${'$'}PREFIX/bin/pkg" ] || [ -x "${'$'}PREFIX/bin/bash" ]
        """.trimIndent()

        val (ok, output) = execLongShell(script, timeoutSeconds = 360)
        AppLogger.i(TAG, "bootstrapTermuxFromDownload ok=$ok output=${output.take(1500)}")
        if (!ok) {
            log("Bootstrap download failed: ${output.lines().lastOrNull() ?: "unknown error"}")
        }
        return ok && isTermuxBootstrapped("$filesDir/usr")
    }

    /** Starts Termux launcher in background so its built-in installer can run. */
    private suspend fun bootstrapTermuxViaActivity(packageName: String, log: suspend (String) -> Unit) {
        log("Starting Termux bootstrap service in background...")
        val launchScript = """
            PKG="$packageName"
            COMP=$(cmd package resolve-activity --brief -c android.intent.category.LAUNCHER ${'$'}PKG 2>/dev/null | tail -1)
            if [ -n "${'$'}COMP" ] && [ "${'$'}COMP" != "No activity found" ]; then
              am start -n "${'$'}COMP" >/dev/null 2>&1 || true
            fi
            am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n ${'$'}PKG/com.termux.app.HomeActivity >/dev/null 2>&1 || true
            am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n ${'$'}PKG/.app.TermuxActivity >/dev/null 2>&1 || true
            monkey -p ${'$'}PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
        """.trimIndent()
        Shell.cmd(launchScript).exec()
    }

    /**
     * Ensures Termux prefix exists (bootstrap + pkg). Fully automatic — no user interaction.
     */
    suspend fun ensureTermuxReady(
        termux: TermuxDetection,
        onProgress: suspend (String) -> Unit
    ): TermuxDetection = withContext(Dispatchers.IO) {
        suspend fun log(msg: String) {
            withContext(Dispatchers.Main) { onProgress(msg) }
        }

        var state = termux
        if (state.isReadyForPkg) return@withContext state

        log("Termux needs first-time setup — doing it automatically...")
        prepareTermuxEnvironment(state)

        if (bootstrapTermuxFromDownload(state, ::log)) {
            state = detectTermux()
            if (state.isReadyForPkg) {
                log("Termux bootstrap installed.")
                return@withContext state
            }
        }

        val pkg = state.packageName ?: "com.termux"
        bootstrapTermuxViaActivity(pkg, ::log)

        repeat(90) { attempt ->
            delay(2_000)
            state = detectTermux()
            if (state.isReadyForPkg) {
                log("Termux bootstrap finished.")
                return@withContext state
            }
            if (attempt % 5 == 4) {
                log("Still setting up Termux... (${(attempt + 1) * 2}s)")
            }
        }

        detectTermux()
    }

    /**
     * Detects all available Python sources on the device.
     */
    suspend fun detectPythonSources(): List<PythonSource> = withContext(Dispatchers.IO) {
        val sources = mutableListOf<PythonSource>()

        // Check system Python
        for (cmd in listOf("python3", "python")) {
            val result = Shell.cmd("$cmd --version 2>&1").exec()
            if (result.out.any { it.contains("Python") }) {
                sources.add(PythonSource(
                    name = "System $cmd",
                    path = cmd,
                    description = "System Python available at /system/bin or PATH",
                    installCommand = null,
                    isAvailable = true
                ))
            }
        }

        // Check Termux Python
        val termux = detectTermux()
        val termuxPrefix = termux.prefix
        if (termuxPrefix != null) {
            val termuxPython = findInstalledTermuxPython(termuxPrefix)
            if (termuxPython != null) {
                val fullCmd =
                    "LD_LIBRARY_PATH=$termuxPrefix/lib PYTHONHOME=$termuxPrefix $termuxPython"
                sources.add(
                    PythonSource(
                        name = "Termux Python",
                        path = fullCmd,
                        description = "Python in Termux (${termux.packageName})",
                        installCommand = null,
                        isAvailable = true
                    )
                )
            }
        }

        // Check for Magisk module Python
        val magiskPaths = listOf(
            "/data/adb/modules/python/bin/python3",
            "/data/adb/python/bin/python3",
            "/sbin/.magisk/modules/python/bin/python3"
        )
        for (path in magiskPaths) {
            val check = Shell.cmd("[ -f $path ] && echo EXISTS || echo MISSING").exec()
            if (check.out.any { it.contains("EXISTS") }) {
                sources.add(PythonSource(
                    name = "Magisk Python",
                    path = path,
                    description = "Python from Magisk module at $path",
                    installCommand = null,
                    isAvailable = true
                ))
            }
        }

        // Check for additional common paths
        val additionalPaths = listOf(
            "/data/local/tmp/python/bin/python3",
            "/data/data/com.termux/files/home/.local/bin/python3",
            "/usr/bin/python3",
            "/usr/local/bin/python3"
        )
        for (path in additionalPaths) {
            val check = Shell.cmd("[ -f $path ] && echo EXISTS || echo MISSING").exec()
            if (check.out.any { it.contains("EXISTS") }) {
                val verify = Shell.cmd("$path --version 2>&1").exec()
                if (verify.out.any { it.contains("Python") }) {
                    sources.add(PythonSource(
                        name = "Custom Python (${path.substringAfterLast("/")})",
                        path = path,
                        description = "Python at $path",
                        installCommand = null,
                        isAvailable = true
                    ))
                }
            }
        }

        sources
    }


    /**
     * One-shot Python setup: detect, install via Termux when possible, refresh runtime status.
     * [onProgress] is called on the main thread with status lines for UI display.
     */
    suspend fun runPythonSetup(onProgress: suspend (String) -> Unit): InstallResult = withContext(Dispatchers.IO) {
        suspend fun log(msg: String) {
            withContext(Dispatchers.Main) { onProgress(msg) }
        }

        log("Checking existing Python installations...")
        val sources = detectPythonSources()
        if (sources.isNotEmpty()) {
            log("Found: ${sources.joinToString { it.name }}")
        } else {
            log("No Python detected yet.")
        }

        MemoryReader.refreshPythonStatus()
        if (MemoryReader.isPythonAvailable) {
            val detail = sources.firstOrNull()?.name ?: "Python"
            log("Python is ready for scanning.")
            return@withContext InstallResult(true, "$detail is available and working.")
        }

        log("Checking Termux...")
        val termux = detectTermux()
        AppLogger.i(TAG, "Termux detection: ${termux.detail}")
        log(termux.detail)

        if (!termux.packageInstalled) {
            log("Install Termux from F-Droid, then tap Setup Python again.")
            return@withContext InstallResult(
                success = false,
                message = "Termux not found. Install com.termux from F-Droid or GitHub, then run Setup Python again."
            )
        }

        var termuxReady = termux
        if (!termuxReady.isReadyForPkg) {
            log("Bootstrapping Termux (first-time setup)...")
            termuxReady = ensureTermuxReady(termuxReady, ::log)
            if (!termuxReady.isReadyForPkg) {
                return@withContext InstallResult(
                    success = false,
                    message = "Could not finish Termux setup automatically. Ensure internet access and root, then try Setup Python again."
                )
            }
        }

        log("Installing Python in Termux...")
        val installResult = checkAndInstallPythonViaTermux(termuxReady)
        installResult.message.lines().forEach { line ->
            if (line.isNotBlank()) log(line.trim())
        }

        log("Re-checking Python for memory scanning...")
        MemoryReader.refreshPythonStatus()
        return@withContext if (MemoryReader.isPythonAvailable) {
            log("Setup complete — Python is ready.")
            InstallResult(true, "Python setup complete. You can use Python scan engine in Settings.")
        } else if (installResult.success) {
            InstallResult(
                false,
                "Python was installed in Termux but androCE could not load it. Tap Setup Python again or reboot."
            )
        } else {
            installResult
        }
    }

    /**
     * Checks if Python is available in Termux and installs it automatically if missing.
     */
    suspend fun checkAndInstallPythonViaTermux(
        termux: TermuxDetection? = null
    ): InstallResult = withContext(Dispatchers.IO) {
        var termuxState = termux ?: detectTermux()
        if (!termuxState.packageInstalled) {
            return@withContext InstallResult(
                success = false,
                message = "Termux is not installed. Install from F-Droid (com.termux) or GitHub.",
                requiresReboot = false
            )
        }

        if (!termuxState.isReadyForPkg) {
            termuxState = ensureTermuxReady(termuxState) { AppLogger.i(TAG, it) }
        }

        val prefix = termuxState.prefix
        if (prefix == null || !termuxState.bootstrapped) {
            return@withContext InstallResult(
                success = false,
                message = "Termux setup did not complete. Check internet connection and tap Setup Python again.",
                requiresReboot = false
            )
        }

        val filesDir = termuxState.filesDir ?: "$prefix/.."
        val homeDir = "$filesDir/home"

        findInstalledTermuxPython(prefix)?.let { py ->
            val ver = Shell.cmd("$py --version 2>&1").exec().out.firstOrNull()?.trim()
            return@withContext InstallResult(
                success = true,
                message = "Python is already installed in Termux: $ver",
                requiresReboot = false
            )
        }

        AppLogger.i(TAG, "Installing Python via Termux pkg at $prefix")

        val installCmd = """
            export PATH=$prefix/bin:${'$'}PATH
            export HOME=$homeDir
            export PREFIX=$prefix
            export LD_LIBRARY_PATH=$prefix/lib
            $prefix/bin/pkg install -y python 2>&1
        """.trimIndent()

        val (pkgOk, output) = execLongShell(installCmd, timeoutSeconds = 600)
        AppLogger.i(TAG, "Termux pkg install ok=$pkgOk output: ${output.take(2000)}")

        findInstalledTermuxPython(prefix)?.let { py ->
            val ver = Shell.cmd("$py --version 2>&1").exec().out.firstOrNull()?.trim()
            return@withContext InstallResult(
                success = true,
                message = "Python installed successfully: $ver",
                requiresReboot = false
            )
        }

        return@withContext InstallResult(
            success = false,
            message = """
Automatic Python install failed.

Output:
${output.ifBlank { "(no output)" }}

Tap Setup Python again after checking network access.
            """.trimIndent(),
            requiresReboot = false
        )
    }

    /**
     * Creates a script to install Python via Termux with proper environment setup.
     */
    suspend fun createTermuxInstallScript(context: Context): String = withContext(Dispatchers.IO) {
        """
        #!/data/data/com.termux/files/usr/bin/sh
        # Python Installation Script for Termux
        # Run this in Termux terminal if automatic installation fails
        
        echo "[*] Updating package lists..."
        pkg update -y
        
        echo "[*] Installing Python..."
        pkg install -y python
        
        echo "[*] Verifying installation..."
        python3 --version
        
        if [ \$? -eq 0 ]; then
            echo "[✓] Python installed successfully!"
            echo "[✓] You can now use Python mode in androCE"
        else
            echo "[✗] Installation failed. Check error messages above."
        fi
        """.trimIndent()
    }

    /**
     * Attempts to install Python via package manager on rooted systems with chroot.
     */
    suspend fun installPythonViaChroot(distro: String): InstallResult = withContext(Dispatchers.IO) {
        val (checkCmd, installCmd) = when (distro.lowercase()) {
            "debian", "ubuntu" -> "apt" to "apt-get update && apt-get install -y python3"
            "alpine" -> "apk" to "apk add python3"
            "arch" -> "pacman" to "pacman -Sy python --noconfirm"
            "fedora", "redhat" -> "dnf" to "dnf install -y python3"
            else -> return@withContext InstallResult(
                success = false,
                message = "Unknown distribution: $distro. Supported: debian, ubuntu, alpine, arch, fedora"
            )
        }

        // Check if chroot environment exists
        val chrootCheck = Shell.cmd("[ -d /data/local/chroot ] || [ -d /data/chroot ] && echo EXISTS || echo MISSING").exec()
        if (!chrootCheck.out.any { it.contains("EXISTS") }) {
            return@withContext InstallResult(
                success = false,
                message = "No chroot environment found at /data/local/chroot or /data/chroot"
            )
        }

        val chrootPath = if (Shell.cmd("[ -d /data/local/chroot ] && echo EXISTS").exec().out.any { it.contains("EXISTS") }) {
            "/data/local/chroot"
        } else {
            "/data/chroot"
        }

        val fullCmd = "chroot $chrootPath /bin/sh -c '$installCmd'"
        val result = Shell.cmd(fullCmd).exec()

        return@withContext if (result.isSuccess) {
            InstallResult(
                success = true,
                message = "Python installed via chroot ($distro)"
            )
        } else {
            InstallResult(
                success = false,
                message = "Failed to install. Ensure chroot has internet access."
            )
        }
    }

    /**
     * Gets available installation methods for the current device.
     */
    suspend fun getAvailableInstallMethods(): List<InstallMethod> = withContext(Dispatchers.IO) {
        val methods = mutableListOf<InstallMethod>()

        // Check Termux availability
        if (detectTermux().packageInstalled) {
            methods.add(InstallMethod.TERMUX)
        }

        // Check chroot availability
        val chrootCheck = Shell.cmd("[ -d /data/local/chroot ] || [ -d /data/chroot ] && echo EXISTS || echo MISSING").exec()
        if (chrootCheck.out.any { it.contains("EXISTS") }) {
            methods.add(InstallMethod.CHROOT)
        }

        // Check if we can use system package manager (rare on Android)
        val hasApt = Shell.cmd("which apt 2>/dev/null || which apt-get 2>/dev/null").exec().out.isNotEmpty()
        if (hasApt) methods.add(InstallMethod.SYSTEM_PACKAGE)

        // Manual download option always available
        methods.add(InstallMethod.MANUAL)

        methods
    }

    enum class InstallMethod {
        TERMUX,
        CHROOT,
        SYSTEM_PACKAGE,
        MANUAL
    }

    /**
     * Detects memory swap type (zRAM, regular swap, MemFusion, etc.)
     */
    suspend fun detectSwapType(): SwapInfo = withContext(Dispatchers.IO) {
        val swapsResult = Shell.cmd("cat /proc/swaps 2>/dev/null").exec()
        val swapsLines = swapsResult.out.filter { it.isNotBlank() && !it.startsWith("Filename") }

        if (swapsLines.isEmpty()) {
            return@withContext SwapInfo(
                hasSwap = false,
                swapType = SwapType.NONE,
                devices = emptyList(),
                totalSizeMB = 0
            )
        }

        val devices = swapsLines.map { line ->
            val parts = line.trim().split(Regex("\\s+"))
            SwapDevice(
                filename = parts.getOrNull(0) ?: "",
                type = parts.getOrNull(1) ?: "",
                size = parts.getOrNull(2)?.toLongOrNull() ?: 0L,
                used = parts.getOrNull(3)?.toLongOrNull() ?: 0L,
                priority = parts.getOrNull(4)?.toIntOrNull() ?: 0
            )
        }

        val swapType = when {
            devices.any { it.filename.contains("zram", ignoreCase = true) } -> SwapType.ZRAM
            devices.any { it.filename.contains("block", ignoreCase = true) || it.type == "partition" } -> SwapType.REGULAR
            devices.any { it.filename.contains("file", ignoreCase = true) || it.type == "file" } -> SwapType.SWAP_FILE
            else -> SwapType.UNKNOWN
        }

        val totalSizeMB = devices.sumOf { it.size } / 1024

        SwapInfo(
            hasSwap = true,
            swapType = swapType,
            devices = devices,
            totalSizeMB = totalSizeMB.toInt()
        )
    }

    /**
     * Gets commands to disable swap based on type and device.
     */
    suspend fun getSwapDisableCommands(): List<SwapDisableOption> = withContext(Dispatchers.IO) {
        val options = mutableListOf<SwapDisableOption>()

        // Standard swapoff
        options.add(SwapDisableOption(
            name = "Disable All Swap (Temporary)",
            description = "Turn off all swap until next reboot",
            command = "swapoff -a",
            requiresReboot = false,
            isPersistent = false
        ))

        // Check for zRAM
        val zramCheck = Shell.cmd("ls /sys/block/zram* 2>/dev/null").exec()
        if (zramCheck.out.isNotEmpty()) {
            options.add(SwapDisableOption(
                name = "Disable zRAM (until reboot)",
                description = "Turn off zRAM compression",
                command = "swapoff /dev/block/zram* 2>/dev/null || for z in /dev/block/zram*; do swapoff \$z 2>/dev/null; done",
                requiresReboot = false,
                isPersistent = false
            ))

            // Check for zRAM reset
            options.add(SwapDisableOption(
                name = "Reset & Disable zRAM",
                description = "Reset zRAM devices to prevent reactivation",
                command = """
                    for z in /sys/block/zram*; do
                        [ -f \${'$'}z/reset ] && echo 1 > \${'$'}z/reset 2>/dev/null
                    done
                    swapoff -a
                """.trimIndent(),
                requiresReboot = false,
                isPersistent = false
            ))
        }

        // Check for Magisk module swap
        val magiskSwapCheck = Shell.cmd("[ -d /data/adb/modules/swap ] && echo EXISTS").exec()
        if (magiskSwapCheck.out.any { it.contains("EXISTS") }) {
            options.add(SwapDisableOption(
                name = "Disable Magisk Swap Module",
                description = "Disable swap Magisk module (may require reboot)",
                command = "touch /data/adb/modules/swap/disable && swapoff -a",
                requiresReboot = true,
                isPersistent = true
            ))
        }

        // Check for MIUI/HyperOS MemFusion
        val memfusionCheck = Shell.cmd("getprop ro.miui.ui.version.name 2>/dev/null || getprop ro.hyperos.version 2>/dev/null").exec()
        if (memfusionCheck.out.isNotEmpty()) {
            options.add(SwapDisableOption(
                name = "Disable MIUI/HyperOS Memory Extension",
                description = "Turn off MIUI Memory Extension (requires reboot)",
                command = "settings put secure miui_memc_enabled 0 && reboot",
                requiresReboot = true,
                isPersistent = true
            ))
        }

        // Check for OnePlus RAM Boost
        val ramBoostCheck = Shell.cmd("[ -f /sys/class/ramboost/enabled ] && cat /sys/class/ramboost/enabled").exec()
        if (ramBoostCheck.out.any { it == "1" }) {
            options.add(SwapDisableOption(
                name = "Disable OnePlus RAM Boost",
                description = "Turn off OnePlus RAM Boost feature",
                command = "echo 0 > /sys/class/ramboost/enabled && swapoff -a",
                requiresReboot = false,
                isPersistent = false
            ))
        }

        // Check for Samsung RAM Plus
        val ramPlusCheck = Shell.cmd("[ -f /sys/block/ramzswap0/size ] && echo EXISTS || settings get secure ram_expand_size 2>/dev/null").exec()
        if (ramPlusCheck.out.any { it != "0" && it.isNotEmpty() }) {
            options.add(SwapDisableOption(
                name = "Disable Samsung RAM Plus",
                description = "Turn off Samsung RAM Plus (may require settings change)",
                command = "settings put secure ram_expand_size 0 && swapoff -a",
                requiresReboot = true,
                isPersistent = true
            ))
        }

        options
    }

    data class SwapInfo(
        val hasSwap: Boolean,
        val swapType: SwapType,
        val devices: List<SwapDevice>,
        val totalSizeMB: Int
    )

    data class SwapDevice(
        val filename: String,
        val type: String,
        val size: Long,
        val used: Long,
        val priority: Int
    )

    enum class SwapType {
        NONE,
        ZRAM,
        REGULAR,
        SWAP_FILE,
        UNKNOWN
    }

    data class SwapDisableOption(
        val name: String,
        val description: String,
        val command: String,
        val requiresReboot: Boolean,
        val isPersistent: Boolean
    )

    /**
     * Generates a setup guide based on device state.
     */
    suspend fun generateSetupGuide(context: Context): SetupGuide = withContext(Dispatchers.IO) {
        val pythonSources = detectPythonSources()
        val swapInfo = detectSwapType()
        val installMethods = getAvailableInstallMethods()

        val issues = mutableListOf<SetupIssue>()
        val recommendations = mutableListOf<String>()

        // Python issues
        if (pythonSources.isEmpty()) {
            issues.add(SetupIssue(
                type = SetupIssueType.MISSING_PYTHON,
                severity = Severity.HIGH,
                description = "Python not found. Scanning will use native engine only (less accurate).",
                resolution = if (installMethods.contains(InstallMethod.TERMUX)) {
                    "Install Python via Termux: pkg install python"
                } else {
                    "Install Termux from F-Droid, then run: pkg install python"
                }
            ))
        }

        // Swap issues
        if (swapInfo.hasSwap) {
            val severity = if (swapInfo.swapType == SwapType.ZRAM) Severity.MEDIUM else Severity.LOW
            issues.add(SetupIssue(
                type = SetupIssueType.SWAP_ACTIVE,
                severity = severity,
                description = "${swapInfo.swapType.name} is active (${swapInfo.totalSizeMB}MB). May cause stale memory reads.",
                resolution = "Disable swap in Settings > Status for more accurate scanning"
            ))
            recommendations.add("Consider disabling ${swapInfo.swapType.name} for best scanning results")
        }

        // SELinux check
        val seResult = Shell.cmd("getenforce 2>/dev/null").exec()
        val seStatus = seResult.out.firstOrNull()?.trim() ?: "Unknown"
        if (seStatus.equals("Enforcing", ignoreCase = true)) {
            issues.add(SetupIssue(
                type = SetupIssueType.SELINUX_ENFORCING,
                severity = Severity.MEDIUM,
                description = "SELinux is Enforcing. Some memory operations may be restricted.",
                resolution = "Set SELinux to Permissive in Settings > Status"
            ))
        }

        // Root check
        val root = Shell.isAppGrantedRoot()
        if (root != true) {
            issues.add(SetupIssue(
                type = SetupIssueType.NO_ROOT,
                severity = Severity.CRITICAL,
                description = "Root access not granted. App cannot function.",
                resolution = "Grant root permission via Magisk/KernelSU"
            ))
        }

        SetupGuide(
            isReady = issues.none { it.severity == Severity.CRITICAL } && pythonSources.isNotEmpty(),
            issues = issues,
            recommendations = recommendations,
            pythonSources = pythonSources,
            swapInfo = swapInfo,
            installMethods = installMethods
        )
    }

    data class SetupGuide(
        val isReady: Boolean,
        val issues: List<SetupIssue>,
        val recommendations: List<String>,
        val pythonSources: List<PythonSource>,
        val swapInfo: SwapInfo,
        val installMethods: List<InstallMethod>
    )

    data class SetupIssue(
        val type: SetupIssueType,
        val severity: Severity,
        val description: String,
        val resolution: String
    )

    enum class SetupIssueType {
        MISSING_PYTHON,
        SWAP_ACTIVE,
        SELINUX_ENFORCING,
        NO_ROOT,
        MISSING_NATIVE_HELPER
    }

    enum class Severity {
        LOW, MEDIUM, HIGH, CRITICAL
    }
}
