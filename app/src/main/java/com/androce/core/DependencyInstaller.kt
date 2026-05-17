package com.androce.core

import android.content.Context
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
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
        val termuxPrefix = "/data/data/com.termux/files/usr"
        val termuxPython = "$termuxPrefix/bin/python3"
        val termuxCheck = Shell.cmd("[ -f $termuxPython ] && echo EXISTS || echo MISSING").exec()
        if (termuxCheck.out.any { it.contains("EXISTS") }) {
            val fullCmd = "nsenter -t 1 -m -- /system/bin/env LD_LIBRARY_PATH=$termuxPrefix/lib PYTHONHOME=$termuxPrefix $termuxPython"
            sources.add(PythonSource(
                name = "Termux Python",
                path = fullCmd,
                description = "Python installed in Termux environment",
                installCommand = null,
                isAvailable = true
            ))
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
        val termuxCheck = Shell.cmd("[ -d /data/data/com.termux/files ] && echo EXISTS || echo MISSING").exec()
        if (!termuxCheck.out.any { it.contains("EXISTS") }) {
            log("Termux is not installed.")
            log("Install Termux from F-Droid, open it once, then tap Setup Python again.")
            return@withContext InstallResult(
                success = false,
                message = "Termux required. Install from F-Droid (com.termux), open it once, then run Setup Python again."
            )
        }

        log("Termux found. Installing or verifying Python...")
        val installResult = checkAndInstallPythonViaTermux()
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
                "Python was installed but could not be loaded from root. Open Termux, run: pkg install python, then tap Setup Python again."
            )
        } else {
            installResult
        }
    }

    /**
     * Checks if Python is available in Termux and installs it automatically if missing.
     */
    suspend fun checkAndInstallPythonViaTermux(): InstallResult = withContext(Dispatchers.IO) {
        // First check if Termux is installed
        val termuxCheck = Shell.cmd("[ -d /data/data/com.termux/files ] && echo EXISTS || echo MISSING").exec()
        if (!termuxCheck.out.any { it.contains("EXISTS") }) {
            return@withContext InstallResult(
                success = false,
                message = "Termux is not installed. Please install Termux from F-Droid or GitHub first.",
                requiresReboot = false
            )
        }

        // Check if Python is already installed
        val pythonCheck = Shell.cmd("/data/data/com.termux/files/usr/bin/python3 --version 2>&1").exec()
        if (pythonCheck.out.any { it.trim().startsWith("Python") }) {
            return@withContext InstallResult(
                success = true,
                message = "Python is already installed in Termux: ${pythonCheck.out.firstOrNull()}",
                requiresReboot = false
            )
        }

        // Try specific version paths
        val versions = listOf("3.12", "3.11", "3.10", "3.9", "3.8")
        for (ver in versions) {
            val verCheck = Shell.cmd("/data/data/com.termux/files/usr/bin/python$ver --version 2>&1").exec()
            if (verCheck.out.any { it.trim().startsWith("Python") }) {
                return@withContext InstallResult(
                    success = true,
                    message = "Python $ver is already installed in Termux",
                    requiresReboot = false
                )
            }
        }

        // Python not found - try to install automatically using root shell
        AppLogger.d(TAG, "Python not found, attempting automatic installation via Termux")
        
        // Set up Termux environment and run pkg install
        val installCmd = """
            export PATH=/data/data/com.termux/files/usr/bin:\${'$'}PATH
            export HOME=/data/data/com.termux/files/home
            export PREFIX=/data/data/com.termux/files/usr
            export LD_LIBRARY_PATH=/data/data/com.termux/files/usr/lib
            /data/data/com.termux/files/usr/bin/pkg install -y python 2>&1
        """.trimIndent()

        val result = Shell.cmd(installCmd).exec()
        val output = result.out.joinToString("\n")
        
        AppLogger.d(TAG, "Python install output: $output")

        // Verify installation
        val verify = Shell.cmd("/data/data/com.termux/files/usr/bin/python3 --version 2>&1").exec()
        if (verify.out.any { it.trim().startsWith("Python") }) {
            return@withContext InstallResult(
                success = true,
                message = "Python installed successfully: ${verify.out.firstOrNull()}",
                requiresReboot = false
            )
        }

        // Installation failed
        return@withContext InstallResult(
            success = false,
            message = """
Automatic installation failed.

Please install manually:
1. Open Termux app
2. Run: pkg install python
3. Return to androCE and tap Refresh

Error output:
$output
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
        val termuxCheck = Shell.cmd("[ -d /data/data/com.termux/files ] && echo EXISTS || echo MISSING").exec()
        if (termuxCheck.out.any { it.contains("EXISTS") }) {
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
