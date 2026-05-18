package com.androce.core

import android.content.Context
import android.content.Intent
import android.os.Build
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Paths
import java.util.zip.ZipFile

/**
 * Handles detection and installation of dependencies (Python) across different Linux environments.
 * Supports Termux, Magisk modules, and various Android Linux distributions.
 */
object DependencyInstaller {

    private const val TAG = "DependencyInstaller"
    private val bootstrapInstallMutex = Mutex()
    private val pythonSetupMutex = Mutex()

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
     * Root-accessible copy of Termux. Android blocks root from executing binaries inside
     * /data/data/com.termux, but root can run the same tree from /data/local/tmp.
     */
    private const val ROOT_MIRROR_DIR = "/data/local/tmp/androce"
    private const val ROOT_MIRROR_PREFIX = "$ROOT_MIRROR_DIR/usr"
    private const val ROOT_MIRROR_HOME = "$ROOT_MIRROR_DIR/home"

    fun buildRootPythonCommand(pythonBin: String = "$ROOT_MIRROR_PREFIX/bin/python3"): String =
        "LD_LIBRARY_PATH=$ROOT_MIRROR_PREFIX/lib PYTHONHOME=$ROOT_MIRROR_PREFIX $pythonBin"

    private fun rootPythonVersion(pythonBin: String): String? {
        val check = Shell.cmd("${buildRootPythonCommand(pythonBin)} --version 2>&1").exec()
        return check.out.firstOrNull { it.trim().startsWith("Python") }?.trim()
    }

    private fun findRootMirroredPython(): String? {
        for (name in listOf("python3", "python", "python3.12", "python3.11", "python3.10")) {
            val bin = "$ROOT_MIRROR_PREFIX/bin/$name"
            val exists = Shell.cmd("[ -f $bin ] && [ -s $bin ] && echo OK || echo NO").exec()
            if (exists.out.any { it.trim() == "OK" } && rootPythonVersion(bin) != null) return bin
        }
        return null
    }

    private fun applyRootPython(pythonBin: String): Boolean {
        if (rootPythonVersion(pythonBin) == null) return false
        MemoryReader.configurePython(buildRootPythonCommand(pythonBin))
        return true
    }

    private suspend fun mirrorTermuxPrefixToRoot(sourcePrefix: String, log: suspend (String) -> Unit): Boolean {
        log("Copying Termux → $ROOT_MIRROR_DIR (root-accessible)...")
        // After copying, fix shebangs that point to the original Termux path so scripts
        // (especially pkg) work when executed from the root mirror, patch pkg to remove
        // its root-UID block, and neuter the bootstrap second-stage profile.d hook.
        val script = """
            ${termuxDebMergeShellSnippet()}

            rm -rf "$ROOT_MIRROR_DIR"
            mkdir -p "$ROOT_MIRROR_HOME/tmp"
            cp -a "$sourcePrefix" "$ROOT_MIRROR_PREFIX"
            chmod -R 755 "$ROOT_MIRROR_DIR"

            # Rewrite shebangs from original Termux path → root mirror path
            OLDPFX="$sourcePrefix"
            for f in "$ROOT_MIRROR_PREFIX/bin/"*; do
              [ -f "${'$'}f" ] || continue
              head -c 2 "${'$'}f" | grep -q '#!' || continue
              sed -i "1s|${'$'}OLDPFX|$ROOT_MIRROR_PREFIX|g" "${'$'}f" 2>/dev/null || true
            done
            # Also fix any shebang referencing the /data/data/com.termux default
            for f in "$ROOT_MIRROR_PREFIX/bin/"*; do
              [ -f "${'$'}f" ] || continue
              head -c 2 "${'$'}f" | grep -q '#!' || continue
              sed -i "1s|/data/data/com\.termux/files/usr|$ROOT_MIRROR_PREFIX|g" "${'$'}f" 2>/dev/null || true
            done

            # Replace pkg with a wrapper that downloads and extracts debs via dpkg-deb.
            # apt has a compiled-in C++ root check (getuid()==0) that cannot be bypassed.
            if [ -f "$ROOT_MIRROR_PREFIX/bin/pkg" ]; then
              mv "$ROOT_MIRROR_PREFIX/bin/pkg" "$ROOT_MIRROR_PREFIX/bin/pkg.original"
              cat > "$ROOT_MIRROR_PREFIX/bin/pkg" << 'PKGEOF'
#!/data/local/tmp/androce/usr/bin/bash
set -e
CMD="${'$'}1"
shift || true
case "${'$'}CMD" in
  install|reinstall)
    export PATH="$ROOT_MIRROR_PREFIX/bin:${'$'}PATH"
    export LD_LIBRARY_PATH="$ROOT_MIRROR_PREFIX/lib"
    export PREFIX="$ROOT_MIRROR_PREFIX"
    for pkgname in "${'$'}@"; do
      case "${'$'}pkgname" in -*) continue ;; esac
      TMPDEB="$ROOT_MIRROR_HOME/tmp/${'$'}pkgname.deb"
      rm -f "${'$'}TMPDEB"
      # Try to download from Termux repo
      for url in \
        "https://packages.termux.dev/apt/termux-main/pool/main/p/${'$'}pkgname/${'$'}pkgname" \
        "https://packages.termux.dev/apt/termux-main/pool/main/${'$'}pkgname/${'$'}pkgname"; do
        if curl -fL --connect-timeout 15 --max-time 120 -o "${'$'}TMPDEB" "${'$'}url" 2>/dev/null && [ -s "${'$'}TMPDEB" ]; then
          break
        fi
        # Try wildcard for versioned debs
        DEBURL="${'$'}url"_*.deb
        if curl -fL --connect-timeout 15 --max-time 120 -o "${'$'}TMPDEB" "${'$'}DEBURL" 2>/dev/null && [ -s "${'$'}TMPDEB" ]; then
          break
        fi
      done
      if [ -s "${'$'}TMPDEB" ]; then
        merge_termux_deb "${'$'}TMPDEB" "$ROOT_MIRROR_PREFIX" "$ROOT_MIRROR_HOME/tmp/pkg-extract"
        rm -f "${'$'}TMPDEB"
      else
        echo "Warning: could not download ${'$'}pkgname"
      fi
    done
    ;;
  *)
    echo "pkg wrapper: only 'install' is supported" >&2
    exit 1
    ;;
esac
PKGEOF
              chmod 755 "$ROOT_MIRROR_PREFIX/bin/pkg"
            fi

            # Fix absolute-path symlinks that point to the original Termux location
            for link in "$ROOT_MIRROR_PREFIX/bin/"* "$ROOT_MIRROR_PREFIX/lib/"* "$ROOT_MIRROR_PREFIX/libexec/"*; do
              [ -L "${'$'}link" ] || continue
              target=$(readlink "${'$'}link") || continue
              case "${'$'}target" in
                /data/data/com.termux/files/usr/*)
                  newtarget=$(echo "${'$'}target" | sed "s|/data/data/com\.termux/files/usr|$ROOT_MIRROR_PREFIX|")
                  rm -f "${'$'}link"
                  ln -sf "${'$'}newtarget" "${'$'}link"
                  ;;
              esac
            done

            # Neuter bootstrap second-stage so bash -l doesn't hit permission errors
            for d in "$ROOT_MIRROR_PREFIX/etc/profile.d" "$ROOT_MIRROR_PREFIX/etc/termux/bootstrap"; do
              if [ -d "${'$'}d" ]; then
                find "${'$'}d" -type f -name '*.sh' -exec sh -c 'echo "# neutered by androCE" > "${'$'}1"' _ {} \;
              fi
            done

            [ -x "$ROOT_MIRROR_PREFIX/bin/bash" ] && echo OK || echo FAIL
        """.trimIndent()
        val (ok, out) = execLongShell(script, timeoutSeconds = 300)
        val success = ok && out.contains("OK")
        if (!success) log("Copy failed: ${out.lines().lastOrNull { it.isNotBlank() } ?: "unknown"}")
        return success
    }

    private suspend fun installPythonInRootMirror(log: suspend (String) -> Unit): Pair<Boolean, String> {
        log("Installing Python with root...")
        return installPythonViaDebDownload(log)
    }

    private const val TERMUX_REPO_BASE = "https://packages.termux.dev/apt/termux-main"

    /** Termux debs unpack to data/data/com.termux/files/usr — merge that tree into PREFIX. */
    private fun termuxDebMergeShellSnippet(): String = """
        merge_termux_deb() {
          local deb="${'$'}1"
          local dest="${'$'}2"
          local work="${'$'}3"
          export PATH="$ROOT_MIRROR_PREFIX/bin:${'$'}PATH"
          export LD_LIBRARY_PATH="$ROOT_MIRROR_PREFIX/lib"
          rm -rf "${'$'}work"
          mkdir -p "${'$'}work"
          if ! dpkg-deb -x "${'$'}deb" "${'$'}work" 2>&1; then
            echo "merge_termux_deb: dpkg-deb failed for ${'$'}deb" >&2
            return 1
          fi
          local usr=""
          if [ -d "${'$'}work/data/data/com.termux/files/usr" ]; then
            usr="${'$'}work/data/data/com.termux/files/usr"
          elif [ -d "${'$'}work/usr" ]; then
            usr="${'$'}work/usr"
          else
            echo "merge_termux_deb: no usr tree in ${'$'}deb" >&2
            return 1
          fi
          cp -a "${'$'}usr/." "${'$'}dest/" || return 1
          rm -rf "${'$'}work"
        }
    """.trimIndent()

    /** Read control fields via mirrored dpkg-deb (bootstrap has no ar). */
    private fun shellDpkgDebField(debFile: File, field: String): List<String> {
        val result = Shell.cmd(
            """PATH="$ROOT_MIRROR_PREFIX/bin:${'$'}PATH" dpkg-deb -f "${debFile.absolutePath}" $field 2>/dev/null"""
        ).exec()
        return result.out.map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun parseDependsLine(line: String): List<String> =
        line.split(",")
            .map { it.substringBefore("|").trim() }
            .map { it.replace(Regex("\\(.*\\)"), "").trim() }
            .filter { it.isNotEmpty() }

    private fun fixMirroredPrefixPathsShell(): String = """
        for f in "$ROOT_MIRROR_PREFIX/bin/"*; do
          [ -f "${'$'}f" ] || continue
          head -c 2 "${'$'}f" | grep -q '#!' || continue
          sed -i "1s|/data/data/com\.termux/files/usr|$ROOT_MIRROR_PREFIX|g" "${'$'}f" 2>/dev/null || true
        done
        if [ -d "$ROOT_MIRROR_PREFIX/libexec" ]; then
          for f in "$ROOT_MIRROR_PREFIX/libexec/"*; do
            [ -f "${'$'}f" ] || continue
            head -c 2 "${'$'}f" | grep -q '#!' || continue
            sed -i "1s|/data/data/com\.termux/files/usr|$ROOT_MIRROR_PREFIX|g" "${'$'}f" 2>/dev/null || true
          done
        fi
        chmod -R 755 "$ROOT_MIRROR_PREFIX/bin" 2>/dev/null || true
        for f in "$ROOT_MIRROR_PREFIX/bin/"* "$ROOT_MIRROR_PREFIX/lib/"* "$ROOT_MIRROR_PREFIX/libexec/"*; do
          [ -L "${'$'}f" ] || continue
          target=$(readlink "${'$'}f") || continue
          case "${'$'}target" in /data/data/com.termux/files/usr/*)
            newtarget=$(echo "${'$'}target" | sed "s|/data/data/com\.termux/files/usr|$ROOT_MIRROR_PREFIX|")
            rm -f "${'$'}f"
            ln -sf "${'$'}newtarget" "${'$'}f"
            ;;
          esac
        done
    """.trimIndent()

    private fun verifyRootPythonShell(): String = """
        export PATH="$ROOT_MIRROR_PREFIX/bin:${'$'}PATH"
        export LD_LIBRARY_PATH="$ROOT_MIRROR_PREFIX/lib"
        export PYTHONHOME="$ROOT_MIRROR_PREFIX"
        PY="$ROOT_MIRROR_PREFIX/bin/python3"
        if [ ! -e "${'$'}PY" ]; then
          echo "Python binary not found at ${'$'}PY"
          ls -la "$ROOT_MIRROR_PREFIX/bin/python"* 2>/dev/null || true
          exit 4
        fi
        if LD_LIBRARY_PATH=$ROOT_MIRROR_PREFIX/lib PYTHONHOME=$ROOT_MIRROR_PREFIX "${'$'}PY" --version >/dev/null 2>&1; then
          LD_LIBRARY_PATH=$ROOT_MIRROR_PREFIX/lib PYTHONHOME=$ROOT_MIRROR_PREFIX "${'$'}PY" --version 2>&1
          echo "Python installed successfully"
          exit 0
        fi
        echo "Python binary not found or missing libraries"
        ldd "${'$'}PY" 2>&1 | head -20 || true
        exit 4
    """.trimIndent()

    private fun parsePackageNameFromDeb(debFile: File): String? {
        shellDpkgDebField(debFile, "Package").firstOrNull()?.let { return it }
        // Fallback from filename: python_aarch64.deb
        val base = debFile.nameWithoutExtension
        val arch = getBootstrapArch()
        return base.removeSuffix("_$arch").takeIf { it.isNotEmpty() }
    }

    private fun parseDependsFromDeb(debFile: File): List<String> {
        val depends = shellDpkgDebField(debFile, "Depends").flatMap { parseDependsLine(it) }
        val preDepends = shellDpkgDebField(debFile, "Pre-Depends").flatMap { parseDependsLine(it) }
        return (preDepends + depends).distinct()
    }

    private fun termuxPoolDirUrl(packageName: String): String {
        val subdir = when {
            // Termux: lib* packages live under pool/main/lib{X}/ (e.g. libffi → libf, libandroid-support → liba)
            packageName.startsWith("lib") && packageName.length > 3 -> "lib${packageName[3]}"
            else -> packageName.first().lowercase().toString()
        }
        return "$TERMUX_REPO_BASE/pool/main/$subdir/$packageName/"
    }

    /** Download latest version of a Termux package deb (directory index or version probe). */
    private fun downloadTermuxPackageDeb(packageName: String, arch: String, outputFile: File): Boolean {
        outputFile.parentFile?.mkdirs()
        outputFile.delete()

        val dirUrl = termuxPoolDirUrl(packageName)
        try {
            val conn = URL(dirUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 60000
            conn.setRequestProperty("User-Agent", "androCE/1.0")
            conn.instanceFollowRedirects = true
            conn.connect()
            if (conn.responseCode == 200) {
                val html = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                val pattern = Regex("""${Regex.escape(packageName)}_([^"'\s]+)_$arch\.deb""")
                val versions = pattern.findAll(html).map { it.groupValues[1] }.distinct().sorted().toList()
                if (versions.isNotEmpty()) {
                    val ver = versions.last()
                    return downloadUrlToFile("$dirUrl${packageName}_${ver}_$arch.deb", outputFile)
                }
            } else {
                conn.disconnect()
            }
        } catch (e: Exception) {
            AppLogger.d(TAG, "Index fetch failed for $packageName: ${e.message}")
        }

        // Fallback: probe common version patterns (package-specific lists)
        val versions = when (packageName) {
            "python" -> listOf(
                "3.13.13-1", "3.13.12-1", "3.13.11-1", "3.13.10-1", "3.13.9-1",
                "3.12.9-1", "3.12.8-1", "3.12.7-1", "3.11.9-1"
            )
            else -> emptyList()
        }
        for (ver in versions) {
            val url = "${termuxPoolDirUrl(packageName)}${packageName}_${ver}_$arch.deb"
            if (downloadUrlToFile(url, outputFile)) return true
        }
        return false
    }

    private fun downloadUrlToFile(url: String, outputFile: File): Boolean {
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 120000
            conn.setRequestProperty("User-Agent", "androCE/1.0")
            conn.instanceFollowRedirects = true
            conn.connect()
            if (conn.responseCode == 200) {
                conn.inputStream.use { input ->
                    outputFile.outputStream().use { output -> input.copyTo(output) }
                }
                conn.disconnect()
                if (outputFile.length() > 500) {
                    AppLogger.i(TAG, "Downloaded ${outputFile.name} (${outputFile.length()} bytes)")
                    return true
                }
                outputFile.delete()
            } else {
                conn.disconnect()
            }
        } catch (e: Exception) {
            AppLogger.d(TAG, "Download failed for $url: ${e.message}")
        }
        return false
    }

    /** Resolve install order: dependencies first, then the root package. */
    private fun collectTermuxInstallOrder(rootDeb: File, arch: String, cacheDir: File): List<File> {
        val order = mutableListOf<File>()
        val seen = mutableSetOf<String>()

        fun visit(deb: File) {
            val pkg = parsePackageNameFromDeb(deb) ?: return
            if (pkg in seen) return
            for (dep in parseDependsFromDeb(deb)) {
                val depDeb = File(cacheDir, "${dep}_$arch.deb")
                if (!depDeb.isFile || depDeb.length() < 500) {
                    if (!downloadTermuxPackageDeb(dep, arch, depDeb)) {
                        AppLogger.w(TAG, "Could not download dependency: $dep")
                        continue
                    }
                }
                visit(depDeb)
            }
            seen.add(pkg)
            order.add(deb)
        }

        visit(rootDeb)
        return order
    }

    /** Download Python + dependencies, merge into root mirror with correct Termux paths. */
    private suspend fun installPythonViaDebDownload(log: suspend (String) -> Unit): Pair<Boolean, String> {
        val arch = getBootstrapArch()
        val cacheDir = AppLogger.applicationContext()?.cacheDir ?: File("/data/local/tmp")
        val pythonDeb = File(cacheDir, "python_${arch}.deb")

        log("Downloading Python deb...")
        if (!downloadTermuxPackageDeb("python", arch, pythonDeb)) {
            AppLogger.w(TAG, "Failed to download Python deb")
            return false to "Failed to download Python deb"
        }
        log("Python deb downloaded (${pythonDeb.length()} bytes)")

        log("Resolving Python dependencies...")
        val installOrder = collectTermuxInstallOrder(pythonDeb, arch, cacheDir)
        if (installOrder.isEmpty()) {
            AppLogger.e(TAG, "No packages to install after dependency resolution")
            return false to "Failed to resolve Python packages (mirror dpkg-deb unavailable?)"
        }
        val depCount = installOrder.size - 1
        if (depCount > 0) log("Installing $depCount dependency package(s)...")
        AppLogger.i(TAG, "Install order: ${installOrder.map { parsePackageNameFromDeb(it) ?: it.name }}")

        val mirrorTmp = "$ROOT_MIRROR_HOME/tmp"
        Shell.cmd("mkdir -p $mirrorTmp").exec()

        val debPathsOnDevice = mutableListOf<String>()
        for (deb in installOrder) {
            val pkg = parsePackageNameFromDeb(deb) ?: deb.nameWithoutExtension
            if (pkg != "python") {
                log("  → $pkg")
            }
            val mirrorDeb = "$mirrorTmp/${pkg}.deb"
            val copy = Shell.cmd(
                "cp ${deb.absolutePath} $mirrorDeb && chmod 644 $mirrorDeb"
            ).exec()
            if (!copy.isSuccess) {
                return false to "Failed to stage $pkg deb"
            }
            debPathsOnDevice.add(mirrorDeb)
        }

        val mergeCalls = debPathsOnDevice.mapIndexed { index, deb ->
            val pkg = deb.substringAfterLast("/").removeSuffix(".deb")
            """merge_termux_deb "$deb" "$ROOT_MIRROR_PREFIX" "$mirrorTmp/deb-work-$index" || { echo "Failed to merge $pkg"; exit 5; }"""
        }.joinToString("\n")

        val script = """
            ${termuxDebMergeShellSnippet()}

            export PREFIX="$ROOT_MIRROR_PREFIX"
            export PATH="$ROOT_MIRROR_PREFIX/bin:${'$'}PATH"
            export LD_LIBRARY_PATH="$ROOT_MIRROR_PREFIX/lib"
            export PYTHONHOME="$ROOT_MIRROR_PREFIX"

            $mergeCalls

            rm -f $mirrorTmp/*.deb 2>/dev/null || true

            ${fixMirroredPrefixPathsShell()}

            ${verifyRootPythonShell()}
        """.trimIndent()
        return execLongShell(script, timeoutSeconds = 300)
    }

    /** Root-simple Python setup: mirror Termux to /data/local/tmp, run pkg/python from there. */
    private suspend fun setupPythonViaRootMirror(
        termuxPrefix: String,
        log: suspend (String) -> Unit
    ): InstallResult {
        findRootMirroredPython()?.let { py ->
            val ver = rootPythonVersion(py) ?: "Python"
            applyRootPython(py)
            return InstallResult(true, "Python ready at $ROOT_MIRROR_DIR ($ver)", requiresReboot = false)
        }

        if (!mirrorTermuxPrefixToRoot(termuxPrefix, log)) {
            return InstallResult(
                false,
                "Could not copy Termux files to $ROOT_MIRROR_DIR. Check root access.",
                requiresReboot = false
            )
        }

        findRootMirroredPython()?.let { py ->
            val ver = rootPythonVersion(py) ?: "Python"
            applyRootPython(py)
            return InstallResult(true, "Python ready ($ver)", requiresReboot = false)
        }

        val (installOk, output) = installPythonInRootMirror(log)
        AppLogger.i(TAG, "Root mirror pkg install ok=$installOk output=${output.take(2000)}")

        findRootMirroredPython()?.let { py ->
            val ver = rootPythonVersion(py) ?: "Python"
            applyRootPython(py)
            return InstallResult(true, "Python installed ($ver)", requiresReboot = false)
        }

        return InstallResult(
            success = false,
            message = """
Python install failed.

${output.ifBlank { "(no output)" }.take(1500)}

Ensure internet is on, then tap Setup Python again.
            """.trimIndent(),
            requiresReboot = false
        )
    }

    /** App cache is writable without root; root shell installs from here into Termux data. */
    private fun bootstrapWorkDir(): File {
        val ctx = AppLogger.applicationContext()
        return ctx?.cacheDir ?: File("/data/local/tmp")
    }

    private fun bootstrapZipFile(): File = File(bootstrapWorkDir(), "androce-termux-bootstrap.zip")

    private fun bootstrapStagingDir(): File = File(bootstrapWorkDir(), "androce-termux-staging")

    private fun termuxApkPath(packageName: String): String? {
        val ctx = AppLogger.applicationContext()
        if (ctx != null) {
            try {
                return ctx.packageManager.getApplicationInfo(packageName, 0).sourceDir
            } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
                // Installed but not visible to this app (e.g. parallel / hidden) — use shell pm path
            }
        }
        val paths = Shell.cmd("pm path $packageName 2>/dev/null").exec().out
            .map { it.removePrefix("package:").trim() }
            .filter { it.isNotEmpty() }
        return paths.firstOrNull { it.contains("base.apk") }
            ?: paths.firstOrNull()
    }

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
        val activePrefix = findBootstrappedPrefix(packageName) ?: resolved.prefix
        val bootstrapped = isBootstrapInstalled(packageName)
        val filesDir = activePrefix?.removeSuffix("/usr") ?: resolved.filesDir
        val dataDir = filesDir?.removeSuffix("/files") ?: resolved.dataDir

        TermuxDetection(
            packageInstalled = true,
            packageName = packageName,
            dataDir = dataDir,
            filesDir = filesDir,
            prefix = activePrefix,
            bootstrapped = bootstrapped,
            detail = when {
                activePrefix == null ->
                    "Termux installed ($packageName) — preparing environment in background"
                !bootstrapped ->
                    "Termux installed ($packageName) — bootstrap not installed yet (will install automatically)"
                findInstalledTermuxPython(packageName, activePrefix) != null -> {
                    val py = findInstalledTermuxPython(packageName, activePrefix)!!
                    val ver = termuxPythonVersion(packageName, activePrefix, py)
                    "Termux ready with Python at $activePrefix${ver?.let { " ($it)" } ?: ""}"
                }
                else ->
                    "Termux ready at $activePrefix (Python not installed yet)"
            }
        )
    }

    private fun listTermuxPrefixPaths(packageName: String): List<String> {
        val found = Shell.cmd(
            "ls -d /data/data/$packageName/files/usr /data/user/*/$packageName/files/usr 2>/dev/null"
        ).exec().out.map { it.trim() }.filter { it.endsWith("/usr") }
        return (found + listOf(
            LEGACY_TERMUX_PREFIX,
            "/data/data/$packageName/files/usr",
            "/data/user/0/$packageName/files/usr"
        )).distinct()
    }

    private fun findBootstrappedPrefix(packageName: String): String? =
        listTermuxPrefixPaths(packageName).firstOrNull { isTermuxBootstrapped(it) }

    private fun buildTermuxPythonCommand(prefix: String, pythonBin: String): String =
        "LD_LIBRARY_PATH=$prefix/lib PYTHONHOME=$prefix $pythonBin"

    private fun termuxUid(packageName: String): String? {
        val fromPm = Shell.cmd(
            "pm list packages -U $packageName 2>/dev/null | grep '^package:$packageName ' | head -1"
        ).exec().out.firstOrNull()
        fromPm?.substringAfter("uid:")?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        for (dataDir in listOf("/data/data/$packageName", "/data/user/0/$packageName")) {
            val uid = Shell.cmd("stat -c %u $dataDir 2>/dev/null").exec().out.firstOrNull()?.trim()
            if (!uid.isNullOrEmpty()) return uid
        }
        return null
    }

    private fun termuxEnvExports(prefix: String, homeDir: String): String = """
        export PATH=$prefix/bin:${'$'}PATH
        export HOME=$homeDir
        export PREFIX=$prefix
        export LD_LIBRARY_PATH=$prefix/lib
        export PYTHONHOME=$prefix
        export TMPDIR=$homeDir/tmp
    """.trimIndent()

    /** Run a command through Termux bash as Termux UID (avoids SELinux exec denials from /system/bin/sh). */
    private fun execAsTermuxUser(
        packageName: String,
        filesDir: String,
        prefix: String,
        body: String,
        timeoutSeconds: Int = 300
    ): Pair<Boolean, String> {
        val uid = termuxUid(packageName) ?: return false to "Termux UID not found"
        val bash = "$prefix/bin/bash"
        val homeDir = "$filesDir/home"
        val check = Shell.cmd("[ -x $bash ] && echo OK || echo NO").exec()
        if (!check.out.any { it.trim() == "OK" }) {
            return false to "Termux bash not found at $bash"
        }
        val inner = """
            mkdir -p "$homeDir/tmp" 2>/dev/null
            cd "$homeDir" 2>/dev/null || cd "$filesDir"
            ${termuxEnvExports(prefix, homeDir)}
            $body
        """.trimIndent()
        val escaped = inner.replace("'", "'\"'\"'")
        val script = "su \"$uid\" \"$bash\" -lc '$escaped'"
        return execLongShell(script, timeoutSeconds)
    }

    private fun findTermuxPythonBinary(packageName: String, prefix: String): String? {
        for (name in listOf("python3", "python", "python3.12", "python3.11", "python3.10")) {
            val bin = "$prefix/bin/$name"
            val check = Shell.cmd("[ -f $bin ] && [ -s $bin ] && echo EXISTS || echo NO").exec()
            if (check.out.any { it.contains("EXISTS") }) return bin
        }
        return null
    }

    private fun termuxPythonVersion(packageName: String, prefix: String, pythonBin: String): String? {
        val filesDir = prefix.removeSuffix("/usr")
        val (ok, out) = execAsTermuxUser(
            packageName,
            filesDir,
            prefix,
            "\"$pythonBin\" --version 2>&1",
            timeoutSeconds = 30
        )
        if (!ok) return null
        return out.lines().firstOrNull { it.trim().startsWith("Python") }?.trim()
    }

    private fun probeTermuxPython(packageName: String, prefix: String): String? {
        val candidate = findTermuxPythonBinary(packageName, prefix) ?: return null
        val filesDir = prefix.removeSuffix("/usr")
        val (ok, out) = execAsTermuxUser(
            packageName,
            filesDir,
            prefix,
            "\"$candidate\" --version 2>&1",
            timeoutSeconds = 45
        )
        if (!ok && !out.contains("Python", ignoreCase = true)) return null
        return if (out.contains("Python", ignoreCase = true)) candidate else null
    }

    private fun findInstalledTermuxPython(packageName: String, prefix: String): String? =
        probeTermuxPython(packageName, prefix)

    /** Wrapper script so MemoryReader can invoke Termux Python from root shell. */
    private fun ensureTermuxPythonWrapper(
        packageName: String,
        prefix: String,
        filesDir: String,
        pythonBin: String
    ): String? {
        val uid = termuxUid(packageName) ?: return null
        val ctx = AppLogger.applicationContext() ?: return null
        val wrapper = File(ctx.cacheDir, "androce-termux-python.sh")
        val homeDir = "$filesDir/home"
        val bash = "$prefix/bin/bash"
        wrapper.writeText(
            """
            #!/system/bin/sh
            exec su "$uid" "$bash" -lc 'export PATH=$prefix/bin:${'$'}PATH; export HOME=$homeDir; export PREFIX=$prefix; export LD_LIBRARY_PATH=$prefix/lib; export PYTHONHOME=$prefix; exec $pythonBin "${'$'}1"' _ "${'$'}1"
            """.trimIndent()
        )
        Shell.cmd("chmod 755 ${wrapper.absolutePath}").exec()
        return wrapper.absolutePath
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
        val script = """
            PKG="$packageName"
            DATA=""
            for d in /data/data/${'$'}PKG /data/user/*/${'$'}PKG; do
              [ -d "${'$'}d" ] && DATA="${'$'}d" && break
            done
            if [ -z "${'$'}DATA" ]; then
              DATA="/data/user/0/${'$'}PKG"
            fi
            UID=${'$'}(pm list packages -U 2>/dev/null | grep "^package:${'$'}PKG " | head -1 | sed 's/.*uid://' | tr -d ' ')
            mkdir -p "${'$'}DATA/files/home" "${'$'}DATA/files/usr-staging"
            if [ -n "${'$'}UID" ]; then
              chown -R "${'$'}UID:${'$'}UID" "${'$'}DATA" 2>/dev/null || true
            fi
            echo "${'$'}DATA"
        """.trimIndent()
        val dataDir = Shell.cmd(script).exec().out.lastOrNull()?.trim()?.takeIf { it.startsWith("/") }
            ?: "/data/user/0/$packageName"

        var filesDir = "$dataDir/files"
        var prefix = "$filesDir/usr"

        if (!isTermuxBootstrapped(prefix) && isTermuxBootstrapped(LEGACY_TERMUX_PREFIX)) {
            filesDir = LEGACY_TERMUX_FILES
            prefix = LEGACY_TERMUX_PREFIX
        }

        return TermuxPaths(dataDir, filesDir, prefix)
    }

    private fun isTermuxBootstrapped(prefix: String?): Boolean {
        if (prefix.isNullOrBlank()) return false
        // Root cannot pass -x on mode-700 files owned by Termux; check presence + size instead.
        val check = Shell.cmd(
            """
            for f in $prefix/bin/pkg $prefix/bin/bash $prefix/bin/python3; do
              if [ -f "${'$'}f" ] && [ -s "${'$'}f" ]; then echo BOOTSTRAPPED; exit 0; fi
            done
            echo NO
            """.trimIndent()
        ).exec()
        return check.out.any { it.contains("BOOTSTRAPPED") }
    }

    /** Finds Termux prefix + python binary (works when user already set up Termux manually). */
    private fun discoverTermuxPython(packageName: String): Pair<String, String>? {
        for (prefix in listTermuxPrefixPaths(packageName)) {
            probeTermuxPython(packageName, prefix)?.let { return prefix to it }
        }
        return null
    }

    private fun isBootstrapInstalled(packageName: String): Boolean =
        findBootstrappedPrefix(packageName) != null

    /** Applies detected Termux Python to MemoryReader for scanning. */
    private fun applyTermuxPythonToMemoryReader(
        packageName: String,
        prefix: String,
        pythonBin: String
    ): Boolean {
        if (termuxPythonVersion(packageName, prefix, pythonBin) == null) return false
        val filesDir = prefix.removeSuffix("/usr")
        val runner = ensureTermuxPythonWrapper(packageName, prefix, filesDir, pythonBin) ?: return false
        MemoryReader.configurePython(runner)
        return true
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

    private fun apkAbiDirs(arch: String): List<String> = when (arch) {
        "aarch64" -> listOf("arm64-v8a", "arm64")
        "arm" -> listOf("armeabi-v7a", "armeabi")
        "x86_64" -> listOf("x86_64")
        "i686" -> listOf("x86")
        else -> listOf("arm64-v8a")
    }

    private fun bootstrapDownloadUrls(arch: String): List<String> = listOf(
        "https://github.com/termux/termux-packages/releases/download/bootstrap-2024.09.15-r1%2Bapt-android-7/bootstrap-$arch.zip",
        "https://github.com/termux/termux-packages/releases/download/bootstrap-2022.04.28-r5%2Bapt-android-7/bootstrap-$arch.zip",
        "https://packages.termux.dev/apt/bootstrap-$arch.zip",
        "https://packages.termux.dev/bootstrap/bootstrap-$arch.zip",
        "https://termux.net/bootstrap/bootstrap-$arch.zip"
    )

    private suspend fun downloadBootstrapViaHttp(arch: String): Boolean = withContext(Dispatchers.IO) {
        val dest = bootstrapZipFile()
        dest.parentFile?.mkdirs()
        dest.delete()
        for (url in bootstrapDownloadUrls(arch)) {
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 25_000
                    readTimeout = 300_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "androCE/1.0")
                }
                val code = conn.responseCode
                if (code !in 200..299) {
                    AppLogger.w(TAG, "Bootstrap HTTP $code for $url")
                    conn.disconnect()
                    continue
                }
                conn.inputStream.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                conn.disconnect()
                if (dest.length() > 10_000L) {
                    AppLogger.i(TAG, "Bootstrap downloaded via HTTP (${dest.length()} bytes) from $url")
                    return@withContext true
                }
                dest.delete()
            } catch (e: Exception) {
                AppLogger.w(TAG, "Bootstrap HTTP failed for $url: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        false
    }

    private fun extractBootstrapFromApkKotlin(packageName: String, arch: String, dest: File): Boolean {
        val apkPath = termuxApkPath(packageName)
        if (apkPath.isNullOrBlank()) {
            AppLogger.e(TAG, "Cannot resolve Termux APK path for $packageName")
            return false
        }
        AppLogger.i(TAG, "Reading Termux APK at $apkPath")
        dest.parentFile?.mkdirs()
        dest.delete()
        try {
            ZipFile(apkPath).use { zip ->
                for (abi in apkAbiDirs(arch)) {
                    val entry = zip.getEntry("lib/$abi/libtermux-bootstrap.so") ?: continue
                    zip.getInputStream(entry).use { input ->
                        dest.outputStream().use { output -> input.copyTo(output) }
                    }
                    if (dest.length() > 0L) {
                        AppLogger.i(TAG, "Extracted libtermux-bootstrap.so ($abi) from Termux APK")
                        return true
                    }
                }
                val soEntry = zip.entries().asSequence().firstOrNull {
                    it.name.contains("libtermux-bootstrap") && it.name.endsWith(".so")
                }
                if (soEntry != null) {
                    zip.getInputStream(soEntry).use { input ->
                        dest.outputStream().use { output -> input.copyTo(output) }
                    }
                    if (dest.length() > 0L) {
                        AppLogger.i(TAG, "Extracted ${soEntry.name} from Termux APK")
                        return true
                    }
                }
                val zipEntry = zip.entries().asSequence().firstOrNull {
                    it.name.contains("bootstrap-$arch") && it.name.endsWith(".zip")
                }
                if (zipEntry != null) {
                    zip.getInputStream(zipEntry).use { input ->
                        dest.outputStream().use { output -> input.copyTo(output) }
                    }
                    if (dest.length() > 0L) {
                        AppLogger.i(TAG, "Extracted ${zipEntry.name} from Termux APK")
                        return true
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Kotlin APK bootstrap extract failed", e)
        }
        AppLogger.w(TAG, "No bootstrap payload found in Termux APK for arch=$arch")
        return false
    }

    private fun prepareBootstrapStaging(stagingDir: File) {
        val symlinks = File(stagingDir, "SYMLINKS.txt")
        if (symlinks.isFile) {
            symlinks.readLines().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach
                val arrow = trimmed.indexOf('\u2190') // ←
                if (arrow <= 0) return@forEach
                val old = trimmed.substring(0, arrow)
                val rel = trimmed.substring(arrow + 1)
                val linkPath = File(stagingDir, rel)
                linkPath.parentFile?.mkdirs()
                try {
                    Files.deleteIfExists(linkPath.toPath())
                    Files.createSymbolicLink(linkPath.toPath(), Paths.get(old))
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Symlink failed for $rel: ${e.message}")
                }
            }
            symlinks.delete()
        }
        listOf("bin", "libexec").forEach { dirName ->
            val dir = File(stagingDir, dirName)
            if (!dir.isDirectory) return@forEach
            dir.walkTopDown().maxDepth(20).filter { it.isFile }.forEach { file ->
                file.setReadable(true, false)
                file.setWritable(true, false)
                file.setExecutable(true, false)
            }
        }
    }

    private fun unzipBootstrapArchive(zipFile: File, stagingDir: File): Boolean {
        try {
            stagingDir.deleteRecursively()
            stagingDir.mkdirs()
            ZipFile(zipFile).use { zip ->
                val entries = zip.entries().asSequence().toList()
                val rodata = entries.firstOrNull {
                    !it.isDirectory && (it.name == ".rodata" || it.name.endsWith(".rodata"))
                }
                if (rodata != null && entries.size <= 3) {
                    val inner = File(zipFile.parentFile, "androce-bootstrap-inner.zip")
                    zip.getInputStream(rodata).use { input ->
                        inner.outputStream().use { output -> input.copyTo(output) }
                    }
                    val ok = unzipBootstrapArchive(inner, stagingDir)
                    inner.delete()
                    return ok
                }
                for (entry in entries) {
                    if (entry.isDirectory) {
                        File(stagingDir, entry.name).mkdirs()
                        continue
                    }
                    val outFile = File(stagingDir, entry.name)
                    outFile.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        outFile.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }
            if (!File(stagingDir, "bin").isDirectory && !File(stagingDir, "SYMLINKS.txt").isFile) {
                return false
            }
            prepareBootstrapStaging(stagingDir)
            return File(stagingDir, "bin").isDirectory
        } catch (e: Exception) {
            AppLogger.e(TAG, "unzipBootstrapArchive failed", e)
            return false
        }
    }

    private suspend fun finalizeBootstrapInstall(
        packageName: String,
        filesDir: String,
        log: suspend (String) -> Unit
    ): Boolean {
        val prefix = "$filesDir/usr"
        val stagingPath = bootstrapStagingDir().absolutePath
        if (!File(stagingPath, "bin").isDirectory) {
            log("Bootstrap staging missing bin/ — unpack failed")
            AppLogger.e(TAG, "finalizeBootstrapInstall: no bin/ in $stagingPath")
            return false
        }
        log("Copying bootstrap into Termux (${File(stagingPath).walkTopDown().count()} files)...")
        val script = """
            PKG="$packageName"
            FILES_DIR="$filesDir"
            SRC="$stagingPath"
            PREFIX="$prefix"
            UID=${'$'}(pm list packages -U 2>/dev/null | grep "^package:${'$'}PKG " | head -1 | sed 's/.*uid://' | tr -d ' ')
            echo "[androce] copy bootstrap to Termux"
            rm -rf "${'$'}PREFIX" "${'$'}FILES_DIR/usr-staging"
            mkdir -p "${'$'}FILES_DIR/home"
            cp -a "${'$'}SRC" "${'$'}FILES_DIR/usr-staging" || exit 10
            mv "${'$'}FILES_DIR/usr-staging" "${'$'}PREFIX" || exit 11
            if [ -n "${'$'}UID" ]; then
              chown -R "${'$'}UID:${'$'}UID" "${'$'}FILES_DIR" 2>/dev/null || true
            fi
            chmod -R u+rwX "${'$'}FILES_DIR" 2>/dev/null || true
            echo "[androce] check binaries"
            ls -la "${'$'}PREFIX/bin/pkg" "${'$'}PREFIX/bin/bash" 2>&1 || true
            if [ -f "${'$'}PREFIX/bin/pkg" ] && [ -s "${'$'}PREFIX/bin/pkg" ]; then echo "[androce] pkg ok"; exit 0; fi
            if [ -f "${'$'}PREFIX/bin/bash" ] && [ -s "${'$'}PREFIX/bin/bash" ]; then echo "[androce] bash ok"; exit 0; fi
            echo "[androce] pkg/bash missing in prefix"
            exit 12
        """.trimIndent()
        val (shellOk, output) = execLongShell(script, timeoutSeconds = 180)
        val bootstrapped = isTermuxBootstrapped(prefix)
        val ok = shellOk || bootstrapped
        AppLogger.i(TAG, "finalizeBootstrapInstall shellOk=$shellOk bootstrapped=$bootstrapped output=${output.take(2000)}")
        if (!ok) {
            val hint = output.lines().lastOrNull { it.isNotBlank() }
                ?: "root could not write Termux data (exit without output)"
            log("Bootstrap install failed: $hint")
        } else if (!shellOk && bootstrapped) {
            log("Termux bootstrap installed successfully.")
        }
        return ok && bootstrapped
    }

    private suspend fun installBootstrapFromLocalZip(
        packageName: String,
        filesDir: String,
        zipFile: File,
        log: suspend (String) -> Unit
    ): Boolean {
        log("Unpacking bootstrap archive...")
        AppLogger.i(TAG, "installBootstrapFromLocalZip size=${zipFile.length()}")
        val staging = bootstrapStagingDir()
        staging.parentFile?.mkdirs()
        if (!unzipBootstrapArchive(zipFile, staging)) {
            log("Kotlin unpack failed, trying shell unzip...")
            AppLogger.w(TAG, "Kotlin unpack failed; falling back to shell unzip")
            return installBootstrapZipShell(packageName, filesDir, log)
        }
        log("Installing bootstrap into Termux data...")
        finalizeBootstrapInstall(packageName, filesDir, log)
        return isBootstrapInstalled(packageName)
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
                    if (!result.isSuccess || output.isBlank()) {
                        AppLogger.w(TAG, "shell exit code=${result.code} success=${result.isSuccess}")
                    }
                    result.isSuccess to output
                }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Long shell command failed", e)
            false to (e.message ?: "shell error")
        }
    }

    /** Shell-only bootstrap install when Kotlin unzip is unavailable. */
    private suspend fun installBootstrapZipShell(
        packageName: String,
        filesDir: String,
        log: suspend (String) -> Unit
    ): Boolean {
        val dataDir = filesDir.removeSuffix("/files")
        val prefix = "$filesDir/usr"
        val staging = "$filesDir/usr-staging"
        val script = """
            PKG="$packageName"
            DATA_DIR="$dataDir"
            FILES_DIR="$filesDir"
            STAGING="$staging"
            PREFIX="$prefix"
            TMPZIP="${bootstrapZipFile().absolutePath}"
            rm -rf "$staging" "$prefix" "${'$'}TMPZIP"
            mkdir -p "$staging" "${'$'}FILES_DIR/home"
            UID=${'$'}(pm list packages -U 2>/dev/null | grep "^package:${'$'}PKG " | head -1 | sed 's/.*uid://' | tr -d ' ')
            command -v unzip >/dev/null 2>&1 || exit 12
            test -s "${'$'}TMPZIP" || exit 13
            unzip -qo "${'$'}TMPZIP" -d "$staging"
            if [ -f "$staging/SYMLINKS.txt" ]; then
              while IFS= read -r line || [ -n "${'$'}line" ]; do
                case "${'$'}line" in ''|\#*) continue;; esac
                old="${'$'}{line%%←*}"
                rel="${'$'}{line#*←}"
                mkdir -p "$staging/${'$'}(dirname "${'$'}rel")"
                ln -sf "${'$'}old" "$staging/${'$'}rel"
              done < "$staging/SYMLINKS.txt"
              rm -f "$staging/SYMLINKS.txt"
            fi
            if [ -n "${'$'}UID" ]; then
              chown -R "${'$'}UID:${'$'}UID" "${'$'}FILES_DIR" 2>/dev/null || true
            fi
            find "$staging/bin" -type f -exec chmod 700 {} + 2>/dev/null || true
            find "$staging/libexec" -type f -exec chmod 700 {} + 2>/dev/null || true
            mv "$staging" "$prefix"
            chmod -R u+rwX,g+rwX "${'$'}FILES_DIR" 2>/dev/null || true
            rm -f "${'$'}TMPZIP"
            [ -x "$prefix/bin/pkg" ] || [ -x "$prefix/bin/bash" ]
        """.trimIndent()
        val (ok, output) = execLongShell(script, timeoutSeconds = 360)
        AppLogger.i(TAG, "installBootstrapZipShell ok=$ok output=${output.take(1500)}")
        if (!ok) {
            log("Bootstrap install failed: ${output.lines().lastOrNull() ?: "unknown"}")
        }
        return ok && isTermuxBootstrapped(prefix)
    }

    private suspend fun bootstrapTermuxFromDownload(
        termux: TermuxDetection,
        log: suspend (String) -> Unit
    ): Boolean {
        val packageName = termux.packageName ?: return false
        val paths = resolveTermuxPaths(packageName)
        val filesDir = paths.filesDir ?: return false
        val arch = getBootstrapArch()
        log("Downloading Termux bootstrap ($arch)...")
        AppLogger.i(TAG, "bootstrapTermuxFromDownload arch=$arch")

        val zipFile = bootstrapZipFile()
        zipFile.parentFile?.mkdirs()
        var haveZip = downloadBootstrapViaHttp(arch)
        if (!haveZip) {
            log("App download failed, trying shell curl/wget...")
            val zipPath = zipFile.absolutePath
            val downloadScript = """
                TMPZIP="$zipPath"
                ARCH="$arch"
                rm -f "${'$'}TMPZIP"
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
                test -s "${'$'}TMPZIP"
            """.trimIndent()
            val (downloaded, dlOut) = execLongShell(downloadScript, timeoutSeconds = 360)
            haveZip = downloaded
            if (!downloaded) {
                log("Download failed: ${dlOut.lines().lastOrNull() ?: "check internet"}")
                AppLogger.i(TAG, "shell bootstrap download failed: ${dlOut.take(500)}")
            }
        }
        if (!haveZip) return false
        log("Download complete. Installing bootstrap...")
        installBootstrapFromLocalZip(packageName, filesDir, zipFile, log)
        return isBootstrapInstalled(packageName)
    }

    private suspend fun bootstrapTermuxFromApk(
        termux: TermuxDetection,
        log: suspend (String) -> Unit
    ): Boolean {
        val packageName = termux.packageName ?: return false
        val paths = resolveTermuxPaths(packageName)
        val filesDir = paths.filesDir ?: return false
        val arch = getBootstrapArch()
        log("Extracting bootstrap from Termux APK ($arch)...")
        AppLogger.i(TAG, "bootstrapTermuxFromApk arch=$arch")

        val dest = bootstrapZipFile()
        dest.parentFile?.mkdirs()
        if (extractBootstrapFromApkKotlin(packageName, arch, dest)) {
            log("Bootstrap archive extracted from APK. Installing...")
            return installBootstrapFromLocalZip(packageName, filesDir, dest, log)
        }

        log("Kotlin APK read failed, trying shell extraction...")
        val zipPath = dest.absolutePath
        val script = """
            PKG="$packageName"
            ARCH="$arch"
            APK=${'$'}(pm path "${'$'}PKG" 2>/dev/null | head -1 | cut -d: -f2)
            TMPZIP="$zipPath"
            rm -f "${'$'}TMPZIP"
            test -n "${'$'}APK" || exit 1
            for abi in arm64-v8a armeabi-v7a x86_64 x86; do
              unzip -p "${'$'}APK" "lib/${'$'}abi/libtermux-bootstrap.so" > "${'$'}TMPZIP" 2>/dev/null && break
            done
            if [ ! -s "${'$'}TMPZIP" ]; then
              ASSET=${'$'}(unzip -l "${'$'}APK" 2>/dev/null | awk '/bootstrap-'"$arch"'\\.zip/ {print ${'$'}4; exit}')
              test -n "${'$'}ASSET" || exit 2
              unzip -p "${'$'}APK" "${'$'}ASSET" > "${'$'}TMPZIP"
            fi
            test -s "${'$'}TMPZIP"
        """.trimIndent()
        val (extracted, out) = execLongShell(script, timeoutSeconds = 120)
        if (!extracted) {
            log("APK bootstrap extract failed: ${out.lines().lastOrNull() ?: "not in APK"}")
            AppLogger.i(TAG, "shell APK bootstrap extract failed: ${out.take(500)}")
            return false
        }
        log("Bootstrap archive extracted from APK. Installing...")
        return installBootstrapFromLocalZip(packageName, filesDir, dest, log)
    }

    /**
     * Ensures Termux prefix exists (bootstrap + pkg). Fully automatic — no user interaction.
     */
    suspend fun ensureTermuxReady(
        termux: TermuxDetection,
        onProgress: suspend (String) -> Unit
    ): TermuxDetection = withContext(Dispatchers.IO) {
        bootstrapInstallMutex.withLock {
            suspend fun log(msg: String) {
                AppLogger.i(TAG, msg)
                withContext(Dispatchers.Main) { onProgress(msg) }
            }

            val packageName = termux.packageName ?: return@withLock termux
            if (termux.isReadyForPkg) return@withLock termux

            if (isBootstrapInstalled(packageName)) {
                log("Termux is already set up.")
                return@withLock detectTermux()
            }

            log("Installing Termux bootstrap (background)...")
            if (bootstrapTermuxFromDownload(detectTermux(), ::log)) {
                log("Termux bootstrap installed.")
                return@withLock detectTermux()
            }

            // APK bootstrap fallback (not in modern Termux APKs)
            if (!isBootstrapInstalled(packageName)) {
                log("Trying alternate bootstrap source (Termux APK)...")
                if (bootstrapTermuxFromApk(detectTermux(), ::log)) {
                    log("Termux bootstrap installed from APK.")
                    return@withLock detectTermux()
                }
            }

            detectTermux()
        }
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

        findRootMirroredPython()?.let { py ->
            sources.add(
                PythonSource(
                    name = "Root Python",
                    path = buildRootPythonCommand(py),
                    description = "Python at $ROOT_MIRROR_DIR (root)",
                    installCommand = null,
                    isAvailable = true
                )
            )
        }

        // Check Termux Python mirror / install location
        val termux = detectTermux()
        if (termux.packageInstalled && termux.packageName != null) {
            for (prefix in listTermuxPrefixPaths(termux.packageName)) {
                if (findTermuxPythonBinary(termux.packageName, prefix) != null) {
                    sources.add(
                        PythonSource(
                            name = "Termux Python",
                            path = buildRootPythonCommand(),
                            description = "Termux at $prefix (use Setup Python to mirror for root)",
                            installCommand = null,
                            isAvailable = findRootMirroredPython() != null
                        )
                    )
                    break
                }
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
            "$ROOT_MIRROR_PREFIX/bin/python3",
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
        pythonSetupMutex.withLock {
        suspend fun log(msg: String) {
            AppLogger.i(TAG, msg)
            withContext(Dispatchers.Main) { onProgress(msg) }
        }

        log("Setting up Python (root)...")

        // Fast path: already mirrored and working from a previous run
        findRootMirroredPython()?.let { py ->
            val ver = rootPythonVersion(py) ?: "Python"
            applyRootPython(py)
            log("Python already ready: $ver")
            return@withLock InstallResult(true, "Python ready ($ver) at $ROOT_MIRROR_DIR")
        }

        val termux = detectTermux()
        log(termux.detail)

        if (!termux.packageInstalled) {
            return@withLock InstallResult(
                false,
                "Install Termux from F-Droid (com.termux), then tap Setup Python again."
            )
        }

        val packageName = termux.packageName ?: "com.termux"

        if (!isBootstrapInstalled(packageName)) {
            log("Downloading Termux base (one-time)...")
            val ready = ensureTermuxReady(termux, ::log)
            if (!isBootstrapInstalled(packageName)) {
                return@withLock InstallResult(
                    false,
                    "Termux bootstrap failed. Check internet and root, then retry."
                )
            }
            log("Termux base ready.")
        }

        val prefix = findBootstrappedPrefix(packageName)
            ?: return@withLock InstallResult(false, "Termux prefix not found after bootstrap.")

        val installResult = setupPythonViaRootMirror(prefix, ::log)

        MemoryReader.refreshPythonStatus()
        return@withLock if (MemoryReader.isPythonAvailable) {
            log("Setup complete — Python is ready.")
            InstallResult(true, installResult.message)
        } else {
            installResult
        }
        }
    }

    /** Ask Termux app to run a command in its own process (enable "Allow external apps" in Termux). */
    private fun runTermuxRunCommand(packageName: String, command: String): Boolean {
        val ctx = AppLogger.applicationContext()
        if (ctx != null) {
            try {
                val intent = Intent().apply {
                    setClassName(packageName, "com.termux.app.RunCommandService")
                    action = "com.termux.RUN_COMMAND"
                    putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/$packageName/files/usr/bin/bash")
                    putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-lc", command))
                    putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/$packageName/files/home")
                    putExtra("com.termux.RUN_COMMAND_BACKGROUND", false)
                    putExtra("com.termux.RUN_COMMAND_WAIT", true)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx.startForegroundService(intent)
                } else {
                    ctx.startService(intent)
                }
                AppLogger.i(TAG, "Termux RUN_COMMAND intent sent: $command")
                return true
            } catch (e: Exception) {
                AppLogger.w(TAG, "RUN_COMMAND intent failed: ${e.message}")
            }
        }
        val bash = "/data/data/$packageName/files/usr/bin/bash"
        val home = "/data/data/$packageName/files/home"
        val result = Shell.cmd(
            "am startservice -n $packageName/com.termux.app.RunCommandService " +
                "-a com.termux.RUN_COMMAND " +
                "--es com.termux.RUN_COMMAND_PATH '$bash' " +
                "--esa com.termux.RUN_COMMAND_ARGUMENTS '-lc' " +
                "--esa com.termux.RUN_COMMAND_ARGUMENTS '$command' " +
                "--es com.termux.RUN_COMMAND_WORKDIR '$home' " +
                "--ez com.termux.RUN_COMMAND_BACKGROUND false " +
                "--ez com.termux.RUN_COMMAND_WAIT true 2>&1"
        ).exec()
        val text = (result.out + result.err).joinToString("\n")
        AppLogger.i(TAG, "Termux RUN_COMMAND shell: $text")
        return result.isSuccess ||
            text.contains("Starting service", ignoreCase = true) ||
            !text.contains("Error: Not found", ignoreCase = true)
    }

    /**
     * Checks if Python is available in Termux and installs it automatically if missing.
     */
    suspend fun checkAndInstallPythonViaTermux(
        termux: TermuxDetection? = null,
        onProgress: (suspend (String) -> Unit)? = null
    ): InstallResult = withContext(Dispatchers.IO) {
        suspend fun progress(msg: String) {
            onProgress?.invoke(msg) ?: AppLogger.i(TAG, msg)
        }
        val termuxState = termux ?: detectTermux()
        if (!termuxState.packageInstalled) {
            return@withContext InstallResult(
                success = false,
                message = "Termux is not installed. Install from F-Droid (com.termux) or GitHub.",
                requiresReboot = false
            )
        }
        val packageName = termuxState.packageName ?: "com.termux"
        if (!isBootstrapInstalled(packageName)) {
            ensureTermuxReady(termuxState, ::progress)
        }
        val prefix = findBootstrappedPrefix(packageName) ?: termuxState.prefix
            ?: return@withContext InstallResult(
                false,
                message = "Termux not ready. Tap Setup Python again.",
                requiresReboot = false
            )
        setupPythonViaRootMirror(prefix, ::progress)
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
