package com.androce.core



import android.content.Context

import android.util.Log

import com.topjohnwu.superuser.Shell

import kotlinx.coroutines.Dispatchers

import kotlinx.coroutines.withContext

import java.io.File



/**

 * Speed hack via Frida when native ptrace+dlopen cannot load libspeedhook.so (common on Android 15).

 * Frida is installed automatically into the root mirror ([DependencyInstaller.ROOT_MIRROR_PREFIX]).

 */

object FridaSpeedHack {

    private const val TAG = "FridaSpeedHack"

    private const val ROOT_TMP_SCRIPT = "/data/local/tmp/androce_frida_speedhack.js"



    private var lastAttachedPid: Int = 0

    private var lastFridaCli: String? = null

    private var lastTermuxEnv: String? = null

    private var lastSetupMessage: String? = null



    data class ProbeStatus(

        val termuxInstalled: Boolean,

        val serverRunning: Boolean,

        val serverBinary: String?,

        val cliPath: String?,

        val cliVersion: String?,

        val ready: Boolean

    ) {

        val summary: String

            get() = when {

                ready -> "Ready — frida-server running"

                cliPath != null && !serverRunning ->

                    "Frida installed — tap Start server or Activate speed hack"

                termuxInstalled ->

                    "Not installed — tap Install in Settings → Status (automatic)"

                else ->

                    "Install Termux from F-Droid, then tap Install Frida in Settings → Status"

            }

    }



    fun lastSetupError(): String? = lastSetupMessage



    /** Settings → Status: check Termux + frida-server + frida CLI without attaching. */

    fun probeStatus(): ProbeStatus {

        val termuxInstalled = Shell.cmd(

            "pm list packages 2>/dev/null | grep -E '^package:com\\.termux'"

        ).exec().out.isNotEmpty()



        val serverRunning = isServerRunning()

        val serverBinary = findServerBinary()

        val cliPath = resolveFridaCli()

        val cliVersion = cliPath?.let { path ->

            val env = lastTermuxEnv ?: DependencyInstaller.buildRootFridaEnv()

            Shell.cmd("$env '$path' --version 2>&1").exec().out.joinToString(" ").trim()

                .ifBlank { null }

        }

        val ready = serverRunning && cliPath != null

        return ProbeStatus(

            termuxInstalled = termuxInstalled,

            serverRunning = serverRunning,

            serverBinary = serverBinary,

            cliPath = cliPath,

            cliVersion = cliVersion,

            ready = ready

        )

    }



    /**

     * Ensures Frida CLI + server are installed and running. Runs background setup when needed.

     */

    suspend fun ensureReady(

        context: Context,

        onProgress: ((String) -> Unit)? = null

    ): Boolean = withContext(Dispatchers.IO) {

        if (probeStatus().ready) {

            ensureFridaServer()

            return@withContext true

        }

        val result = DependencyInstaller.runFridaSetup { line ->

            onProgress?.invoke(line)

        }

        lastSetupMessage = result.message

        if (!result.success) {

            Log.e(TAG, "Frida setup failed: ${result.message}")

            return@withContext false

        }

        val ready = probeStatus().ready || (ensureFridaServer() && resolveFridaCli() != null)

        if (!ready) {

            lastSetupMessage = "Frida setup finished but server or CLI is not available"

        }

        ready

    }



    fun startFridaServer(): Boolean = DependencyInstaller.startFridaServerShell()



    fun isServerRunning(): Boolean =
        Shell.cmd("pgrep -f frida-server 2>/dev/null").exec().isSuccess ||
            Shell.cmd("ss -ltn 2>/dev/null | grep -q ':27042'").exec().isSuccess



    fun attachedPid(): Int = lastAttachedPid



    fun clear() {

        lastAttachedPid = 0

    }



    fun isAttached(pid: Int): Boolean = lastAttachedPid == pid



    /**

     * Attach to [pid] and install clock hooks. Returns true if Frida reported success.

     */

    fun attach(context: Context, pid: Int, packageName: String, speed: Float): Boolean {

        if (!ensureFridaServer()) {

            Log.e(TAG, "frida-server not running and could not be started")

            return false

        }



        val frida = resolveFridaCli() ?: run {

            Log.e(TAG, "frida CLI not found — run Install Frida from Settings → Status")

            return false

        }



        if (!stageScript(context, speed)) {

            Log.e(TAG, "failed to stage Frida script")

            return false

        }



        val env = lastTermuxEnv ?: DependencyInstaller.buildRootFridaEnv()

        val cmd = buildString {

            append(env)

            if (env.isNotEmpty()) append(' ')

            append("'$frida' -D -p $pid -l '$ROOT_TMP_SCRIPT' --eternalize -q 2>&1")

        }



        Log.i(TAG, "attach pid=$pid pkg=$packageName speed=$speed")

        Log.i(TAG, "cmd: $cmd")

        val result = Shell.cmd(cmd).exec()

        val lines = result.out + result.err

        lines.filter { it.isNotBlank() }.forEach { Log.i(TAG, it.trim()) }



        val ok = lines.any { line ->

            line.contains("[androce]") &&

                (line.contains("hooked") || line.contains("ready"))

        }

        if (ok) {

            lastAttachedPid = pid

            lastFridaCli = frida

            Log.i(TAG, "OK Frida attached pid=$pid (eternalize)")

        } else {

            Log.e(TAG, "Frida attach failed exit=${result.code}")

        }

        return ok

    }



    fun updateSpeed(pid: Int, speed: Float): Boolean {

        if (lastAttachedPid != pid) return false

        val frida = lastFridaCli ?: resolveFridaCli() ?: return false

        val env = lastTermuxEnv ?: DependencyInstaller.buildRootFridaEnv()

        val cmd = buildString {

            append(env)

            if (env.isNotEmpty()) append(' ')

            append("'$frida' -D -p $pid -e \"rpc.exports.setSpeed($speed)\" -q 2>&1")

        }

        val result = Shell.cmd(cmd).exec()

        return result.isSuccess

    }



    fun termuxSetupHint(): String =

        lastSetupMessage?.takeIf { it.isNotBlank() }

            ?: "Open Settings → Status and tap Install next to Frida (speed hack). Setup runs in the background."



    private fun serverBinaryCandidates(): List<String> {

        val candidates = mutableListOf<String>()

        candidates.add(DependencyInstaller.ROOT_FRIDA_SERVER)

        candidates.add("${DependencyInstaller.ROOT_MIRROR_PREFIX}/lib/frida/frida-server")

        candidates.add("${DependencyInstaller.ROOT_MIRROR_PREFIX}/bin/frida-server")

        termuxPrefixes().forEach { prefix ->

            candidates.add("$prefix/lib/frida/frida-server")

            candidates.add("$prefix/bin/frida-server")

        }

        candidates.addAll(

            listOf(

                "/data/adb/modules/frida/frida-server",

                "/data/adb/modules/frida-server/frida-server",

                "/system/bin/frida-server"

            )

        )

        return candidates.distinct()

    }



    private fun findServerBinary(): String? =

        serverBinaryCandidates().firstOrNull { Shell.cmd("test -x '$it'").exec().isSuccess }



    private fun ensureFridaServer(): Boolean {
        if (isServerRunning()) {
            Log.i(TAG, "frida-server already running")
            return true
        }
        val ok = DependencyInstaller.startFridaServerShell()
        if (!ok) {
            Log.e(
                TAG,
                "frida-server failed — see ${SetupLogger.DEVICE_LOG_PATH} and /data/local/tmp/frida-server.log"
            )
        }
        return ok
    }



    private fun resolveFridaCli(): String? {
        DependencyInstaller.findRootMirroredFridaCli()?.let { path ->
            lastTermuxEnv = DependencyInstaller.buildRootFridaEnv()
            Log.i(TAG, "frida CLI: $path")
            return path
        }

        val tried = mutableSetOf<String>()

        fun tryPath(path: String, env: String): String? {
            if (path in tried) return null
            tried.add(path)
            if (!Shell.cmd("test -f '$path'").exec().isSuccess) return null
            val ver = Shell.cmd("$env '$path' --version 2>&1").exec().out.joinToString(" ")
            if (ver.contains("frida", ignoreCase = true) || ver.matches(Regex(".*\\d+\\.\\d+.*"))) {
                lastTermuxEnv = env.trim().ifBlank { null }
                Log.i(TAG, "frida CLI: $path ($ver)")
                return path
            }
            return null
        }

        val rootEnv = DependencyInstaller.buildRootFridaEnv()
        tryPath("${DependencyInstaller.ROOT_MIRROR_PREFIX}/bin/frida", rootEnv)?.let { return it }
        tryPath("${DependencyInstaller.ROOT_MIRROR_PREFIX}/bin/frida-ps", rootEnv)?.let { return it }



        termuxPrefixes().forEach { prefix ->

            val env = "LD_LIBRARY_PATH=$prefix/lib PYTHONHOME=$prefix PATH=$prefix/bin:\$PATH"

            tryPath("$prefix/bin/frida", env)?.let { return it }

        }



        Shell.cmd("which frida 2>/dev/null || which frida-ps 2>/dev/null").exec()

            .out.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }

            ?.let { path -> tryPath(path, "")?.let { return it } }



        return null

    }



    private fun termuxPrefixes(): List<String> {

        val fromShell = Shell.cmd(

            "ls -d /data/data/com.termux/files/usr " +

                "/data/user/*/com.termux/files/usr 2>/dev/null"

        ).exec().out.map { it.trim() }.filter { it.isNotEmpty() }



        return (fromShell + listOf("/data/data/com.termux/files/usr")).distinct()

    }



    private fun stageScript(context: Context, speed: Float): Boolean {

        return try {

            val cacheFile = File(context.cacheDir, "frida_speedhack.js")

            val raw = context.assets.open("frida/speedhack.js").bufferedReader().use { it.readText() }

            val baked = raw.replace("var SPEED = 1.0;", "var SPEED = $speed;")

            cacheFile.writeText(baked)

            val copy = Shell.cmd(

                "cp '${cacheFile.absolutePath}' '$ROOT_TMP_SCRIPT' && chmod 644 '$ROOT_TMP_SCRIPT'"

            ).exec()

            copy.isSuccess && Shell.cmd("test -s '$ROOT_TMP_SCRIPT'").exec().isSuccess

        } catch (e: Exception) {

            Log.e(TAG, "stageScript", e)

            false

        }

    }

}


