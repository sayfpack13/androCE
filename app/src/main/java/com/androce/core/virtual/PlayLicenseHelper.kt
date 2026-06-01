package com.androce.core.virtual

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.androce.core.AppLogger

/**
 * PairIP (Google Play license) gate on many Play Store APKs.
 * The license screen must be skipped by launching the real main activity — not only finishing the gate.
 */
object PlayLicenseHelper {

    private const val TAG = "PlayLicenseHelper"
    private const val PAIRIP_MARKER = "pairip"

    fun usesPlayLicensing(context: Context, packageName: String): Boolean {
        return findPairipActivities(context, packageName).isNotEmpty()
    }

    fun findPairipActivities(context: Context, packageName: String): List<String> {
        val pm = context.packageManager
        val result = mutableListOf<String>()
        try {
            @Suppress("DEPRECATION")
            val info = pm.getPackageInfo(packageName, PackageManager.GET_ACTIVITIES)
            info.activities?.forEach { ai ->
                if (ai.name.contains(PAIRIP_MARKER, ignoreCase = true)) {
                    result.add(ai.name)
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "findPairipActivities failed for $packageName", e)
        }
        return result
    }

    /**
     * Real entry activity for the app UI (not the PairIP license gate).
     * Many Play builds expose only [LicenseActivity] as LAUNCHER; the Flutter/native main
     * activity is registered without MAIN/LAUNCHER — so we also scan the manifest.
     */
    fun resolveMainLauncher(context: Context, packageName: String): ComponentName? {
        val pm = context.packageManager
        try {
            val mainIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                setPackage(packageName)
            }
            @Suppress("DEPRECATION")
            val queryFlags = PackageManager.MATCH_DEFAULT_ONLY
            val fromLauncher = pm.queryIntentActivities(mainIntent, queryFlags)
                .map { it.activityInfo }
                .filter { it.packageName == packageName }
                .filter { !isLicenseGate(it.name) }
                .maxByOrNull { scoreMainActivity(it.name) }
            if (fromLauncher != null) {
                return ComponentName(packageName, fromLauncher.name)
            }

            @Suppress("DEPRECATION")
            val info = pm.getPackageInfo(packageName, PackageManager.GET_ACTIVITIES)
            val fromManifest = info.activities
                ?.filter { it.packageName == packageName || it.packageName == null }
                ?.filter { isLicenseGate(it.name).not() }
                ?.maxByOrNull { scoreMainActivity(it.name) }
            if (fromManifest != null) {
                AppLogger.i(TAG, "Resolved main via manifest scan: ${fromManifest.name}")
                return ComponentName(packageName, fromManifest.name)
            }
            AppLogger.w(TAG, "No main activity found for $packageName (only PairIP launcher?)")
        } catch (e: Exception) {
            AppLogger.e(TAG, "resolveMainLauncher failed for $packageName", e)
        }
        return null
    }

    private fun isLicenseGate(activityClass: String): Boolean {
        return activityClass.contains(PAIRIP_MARKER, ignoreCase = true) ||
            activityClass.contains("LicenseActivity", ignoreCase = true) ||
            (activityClass.contains("License", ignoreCase = true) &&
                activityClass.contains("licensecheck", ignoreCase = true))
    }

    private fun scoreMainActivity(name: String): Int {
        var score = 0
        if (name.endsWith(".MainActivity")) score += 100
        if (name.contains("FlutterActivity", ignoreCase = true)) score += 90
        if (name.contains("MainActivity", ignoreCase = true)) score += 40
        if (name.contains("Splash", ignoreCase = true)) score -= 5
        return score
    }

    fun launchMainActivity(context: Context, packageName: String): Boolean {
        val component = resolveMainLauncher(context, packageName) ?: return false
        return try {
            // Use explicit class — MainActivity is often not a LAUNCHER export.
            val intent = Intent().apply {
                setClassName(component.packageName, component.className)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            }
            context.startActivity(intent)
            AppLogger.i(TAG, "Started main ${component.className} for $packageName")
            true
        } catch (e: Exception) {
            AppLogger.e(TAG, "launchMainActivity failed for $packageName", e)
            false
        }
    }

    /**
     * If the PairIP gate activity still opens, start the real main UI then close the gate.
     * Do not disable license providers — that breaks Flutter/androidx startup.
     */
    fun registerLicenseActivityBlocker(application: Application, packageName: String) {
        if (!usesPlayLicensing(application, packageName)) return
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: android.os.Bundle?) {
                val name = activity.javaClass.name
                if (!isLicenseGate(name)) return
                val guestPkg = activity.packageName
                AppLogger.i(TAG, "PairIP gate $name — opening main UI for $guestPkg")
                launchMainActivity(activity, guestPkg)
                activity.window.decorView.postDelayed({
                    if (!activity.isFinishing) activity.finish()
                }, 250)
            }

            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: android.os.Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
}
