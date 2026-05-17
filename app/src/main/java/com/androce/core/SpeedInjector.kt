package com.androce.core

import android.util.Log
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object SpeedInjector {
    private const val TAG = "SpeedInjector"
    private const val SHM_PATH = "/data/local/tmp/speedhack_shm"
    private const val LIB_NAME = "libspeedhook.so"

    private var libraryPath: String? = null
    private var injectorPath: String? = null

    fun init(context: android.content.Context) {
        val filesDir = context.filesDir
        val libDir = File(filesDir.parentFile, "lib")
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val abi = android.os.Build.SUPPORTED_ABIS[0] ?: "arm64-v8a"
        
        Log.d(TAG, "Looking for library... ABI=$abi, nativeLibDir=$nativeLibDir")
        
        val possiblePaths = mutableListOf(
            File(nativeLibDir, LIB_NAME),
            File(libDir, "$abi/$LIB_NAME"),
            File(filesDir, "../lib/$abi/$LIB_NAME"),
            File("/data/data/com.androce/lib/$abi/$LIB_NAME"),
            File("/data/app/com.androce*/lib/$abi/$LIB_NAME"),
            File(filesDir, LIB_NAME)
        )
        
        // Also check all files in nativeLibraryDir
        File(nativeLibDir).listFiles()?.forEach { file ->
            Log.d(TAG, "Found in nativeLibDir: ${file.name}")
            if (file.name.contains("speedhook")) {
                possiblePaths.add(0, file) // Add to front if matches
            }
        }
        
        libraryPath = possiblePaths.firstOrNull { it.exists() }?.absolutePath
        Log.d(TAG, "Library path: $libraryPath (exists=${libraryPath != null})")
        
        // If library not found in standard locations, copy it from APK
        if (libraryPath == null) {
            Log.w(TAG, "Library not found in standard paths, trying to extract from APK")
            extractLibraryFromApk(context)
        }
        
        // Find or extract injector binary
        findOrExtractInjector(context)
    }
    
    private fun findOrExtractInjector(context: android.content.Context) {
        val abi = android.os.Build.SUPPORTED_ABIS[0] ?: "arm64-v8a"
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val filesDir = context.filesDir
        
        // Check if injector exists in native lib dir
        val possibleInjectorPaths = listOf(
            File(nativeLibDir, "speedinjector"),
            File(filesDir, "speedinjector"),
            File("/data/local/tmp/speedinjector")
        )
        
        injectorPath = possibleInjectorPaths.firstOrNull { it.exists() }?.absolutePath
        
        if (injectorPath == null) {
            // Extract from APK
            val apkPath = context.applicationInfo.sourceDir
            val targetFile = File(filesDir, "speedinjector")
            val libPathInApk = "lib/$abi/speedinjector"
            
            Log.d(TAG, "Extracting injector from APK: $libPathInApk")
            
            val result = Shell.cmd(
                "unzip -p '$apkPath' $libPathInApk > '${targetFile.absolutePath}' 2>/dev/null && chmod 755 '${targetFile.absolutePath}'"
            ).exec()
            
            if (result.isSuccess && targetFile.exists()) {
                injectorPath = targetFile.absolutePath
                Log.d(TAG, "Injector extracted to: $injectorPath")
                
                // Also copy to /data/local/tmp for easier access
                Shell.cmd("cp '${targetFile.absolutePath}' /data/local/tmp/speedinjector && chmod 755 /data/local/tmp/speedinjector").exec()
            } else {
                Log.e(TAG, "Failed to extract injector: ${result.err}")
            }
        } else {
            Log.d(TAG, "Found existing injector at: $injectorPath")
        }
    }
    
    private fun extractLibraryFromApk(context: android.content.Context): Boolean {
        return try {
            val abi = android.os.Build.SUPPORTED_ABIS[0] ?: "arm64-v8a"
            val apkPath = context.applicationInfo.sourceDir
            val extractDir = File(context.filesDir, "libs").apply { mkdirs() }
            val targetFile = File(extractDir, LIB_NAME)
            
            Log.d(TAG, "Extracting from APK: $apkPath -> ${targetFile.absolutePath}")
            
            // Use unzip to extract the library from APK
            val libPathInApk = "lib/$abi/$LIB_NAME"
            val result = Shell.cmd(
                "unzip -p '$apkPath' $libPathInApk > '${targetFile.absolutePath}' && chmod 755 '${targetFile.absolutePath}'"
            ).exec()
            
            if (result.isSuccess && targetFile.exists()) {
                libraryPath = targetFile.absolutePath
                Log.d(TAG, "Library extracted successfully to: $libraryPath")
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
            
            val libPath = libraryPath ?: run {
                val error = "Speed hook library not found"
                Log.e(TAG, error)
                SpeedControl.setFailed(error)
                return@withContext false
            }

            setupSharedMemory()
            
            val result = performInjection(pid, libPath)
            
            if (result) {
                verifyInjection(pid)
                SpeedControl.setActive(pid, processName)
                Log.d(TAG, "Speed hack activated for $processName (pid=$pid)")
            } else {
                SpeedControl.setFailed("Injection failed")
            }
            
            result
        } catch (e: Exception) {
            Log.e(TAG, "Injection failed", e)
            SpeedControl.setFailed(e.message ?: "Unknown error")
            false
        }
    }

    private fun setupSharedMemory(): Boolean {
        try {
            Shell.cmd("rm -f $SHM_PATH").exec()
            
            val result = Shell.cmd(
                "dd if=/dev/zero of=$SHM_PATH bs=4096 count=1 2>/dev/null && chmod 666 $SHM_PATH"
            ).exec()
            
            if (!result.isSuccess) {
                Log.w(TAG, "Failed to create shared memory file, using fallback")
            }
            
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
            
            val hexBytes = speedBytes.joinToString("") { "%02x".format(it) }
            Shell.cmd("printf '$hexBytes' | xxd -r -p > $SHM_PATH").exec()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write speed to shared memory", e)
        }
    }

    private fun performInjection(pid: Int, libPath: String): Boolean {
        // Try multiple injection methods in order
        return injectViaNativeBinary(pid, libPath)
            || injectViaDebugger(pid, libPath) 
            || injectViaEnvOverride(pid, libPath)
            || injectViaProcessVmWritev(pid, libPath)
    }
    
    private fun injectViaNativeBinary(pid: Int, libPath: String): Boolean {
        val injector = injectorPath ?: return false
        val lib = libraryPath ?: return false
        
        Log.d(TAG, "Attempting injection via native binary: $injector")
        
        // Copy library to accessible location
        val targetLib = "/data/local/tmp/libspeedhook.so"
        Shell.cmd("cp '$lib' '$targetLib' && chmod 755 '$targetLib'").exec()
        
        // Run injector
        val result = Shell.cmd("'$injector' $pid '$targetLib' 2>&1").exec()
        
        val output = result.out.joinToString("\n")
        Log.d(TAG, "Injector output: $output")
        Log.d(TAG, "Injector exit code: ${result.code}")
        
        // Check if injection succeeded
        val verify = Shell.cmd("grep -q libspeedhook /proc/$pid/maps 2>/dev/null && echo 'OK'").exec()
        if (verify.out.contains("OK")) {
            Log.d(TAG, "Native binary injection successful")
            return true
        }
        
        Log.w(TAG, "Native binary injection failed or not fully implemented")
        return false
    }

    private fun injectViaDebugger(pid: Int, libPath: String): Boolean {
        // Use gdb or lldb if available for injection
        val gdbCheck = Shell.cmd("which gdb gdbserver 2>/dev/null").exec()
        val hasGdb = gdbCheck.out.isNotEmpty()
        
        if (hasGdb) {
            Log.d(TAG, "Attempting injection via GDB")
            
            val gdbScript = """
                set timeout 5
                attach $pid
                call (void*)dlopen("$libPath", 2)
                detach
                quit
            """.trimIndent()
            
            val result = Shell.cmd("echo '$gdbScript' | gdb -q 2>/dev/null").exec()
            
            // Check if injection succeeded
            val verify = Shell.cmd("grep -q libspeedhook /proc/$pid/maps 2>/dev/null && echo 'OK'").exec()
            if (verify.out.contains("OK")) {
                Log.d(TAG, "GDB injection successful")
                return true
            }
        }
        return false
    }

    private fun injectViaEnvOverride(pid: Int, libPath: String): Boolean {
        // Get the process package name from cmdline
        val cmdlineResult = Shell.cmd("cat /proc/$pid/cmdline 2>/dev/null | tr '\\0' ' ' | awk '{print ${'$'}1}'").exec()
        val packageName = cmdlineResult.out.firstOrNull()?.trim() ?: return false
        
        // Check if it's a debuggable app we can restart with LD_PRELOAD
        val debuggableCheck = Shell.cmd("run-as '$packageName' echo 'OK' 2>/dev/null").exec()
        val isDebuggable = debuggableCheck.out.contains("OK")
        
        if (isDebuggable) {
            Log.d(TAG, "Package $packageName is debuggable, could use run-as")
            // Note: We can't actually restart the app from here, but we could
            // set up for LD_PRELOAD on next launch
        }
        
        // Try to use wrap property for debuggable apps
        val wrapProp = "wrap.$packageName"
        val currentWrap = Shell.cmd("getprop $wrapProp 2>/dev/null").exec().out.firstOrNull() ?: ""
        
        if (currentWrap.isNotEmpty() || isDebuggable) {
            Log.d(TAG, "Setting LD_PRELOAD wrap property")
            Shell.cmd("setprop $wrapProp 'LD_PRELOAD=$libPath'").exec()
            // User would need to restart the app
            return false // Can't inject live process this way
        }
        
        return false
    }

    private fun injectViaProcessVmWritev(pid: Int, libPath: String): Boolean {
        Log.d(TAG, "Attempting injection via /proc/pid/mem writing")
        
        // Create a simple loader script that will be executed in target context
        val loaderPath = "/data/local/tmp/speedhook_loader.sh"
        val loaderScript = """
            #!/system/bin/sh
            export LD_LIBRARY_PATH=/system/lib64:/system/lib:/vendor/lib64:/vendor/lib
            export _SPEEDHACK_LIB="$libPath"
            # Signal that hook should load
            echo "1" > /data/local/tmp/speedhack_active
        """.trimIndent()
        
        // Write loader script
        Shell.cmd("echo '$loaderScript' > $loaderPath && chmod 755 $loaderPath").exec()
        
        // Try to trigger library load via various methods
        val injectionResult = Shell.cmd("""
            pid=$pid
            lib="$libPath"
            
            # Check if already loaded
            if grep -q "libspeedhook" /proc/${'$'}pid/maps 2>/dev/null; then
                echo "ALREADY_LOADED"
                exit 0
            fi
            
            # Method 1: Try using app_process to trigger dlopen
            # This works on some Android versions
            
            # Method 2: Check if we can write to process memory via /proc/pid/mem
            if [ -r /proc/${'$'}pid/mem ] && [ -w /proc/${'$'}pid/maps ]; then
                echo "CAN_ACCESS_MEM"
                
                # Attempt to find a code cave or use existing library
                # This is a simplified placeholder - real implementation would:
                # 1. Parse /proc/pid/maps to find executable region
                # 2. Write shellcode to call dlopen
                # 3. Use process_vm_writev or /proc/pid/mem
                
                # For now, try using the app's own thread creation
            fi
            
            # Method 3: Use debuggable property if available
            if [ "$(getprop ro.debuggable)" = "1" ]; then
                echo "DEBUG_BUILD"
                # On debug builds we have more options
            fi
            
            echo "INJECTION_NOT_COMPLETE"
        """.trimIndent()).exec()
        
        val output = injectionResult.out.joinToString(" ")
        
        when {
            output.contains("ALREADY_LOADED") -> {
                Log.d(TAG, "Library already loaded")
                return true
            }
            output.contains("CAN_ACCESS_MEM") -> {
                Log.d(TAG, "Can access process memory, but injection not implemented")
                // Would need actual shellcode injection here
            }
            output.contains("DEBUG_BUILD") -> {
                Log.d(TAG, "Debug build detected, more options available")
            }
        }
        
        return false
    }

    private fun verifyInjection(pid: Int): Boolean {
        val result = Shell.cmd("grep libspeedhook /proc/$pid/maps 2>/dev/null").exec()
        val injected = result.out.isNotEmpty()
        Log.d(TAG, "Injection verified: $injected")
        return injected
    }

    suspend fun updateSpeed(speed: Float) = withContext(Dispatchers.IO) {
        writeSpeedToShm(speed)
        Log.d(TAG, "Speed updated to ${speed}x")
    }

    fun reset() {
        try {
            Shell.cmd("rm -f $SHM_PATH").exec()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cleanup shared memory", e)
        }
        SpeedControl.reset()
    }

    // Native library is loaded via injection, not via System.loadLibrary
    // The library hooks time functions when injected into target process
}
