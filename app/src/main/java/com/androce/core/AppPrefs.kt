package com.androce.core

import android.content.Context
import android.content.SharedPreferences

object AppPrefs {

    private const val PREFS_NAME = "androce_prefs"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // --- Scan Settings ---

    /** Auto-refresh interval in milliseconds. 0 = off. Default 2000. */
    var autoRefreshIntervalMs: Long
        get() = prefs.getLong("auto_refresh_interval_ms", 2000L)
        set(value) = prefs.edit().putLong("auto_refresh_interval_ms", value).apply()

    /** Maximum number of results to keep. Default 500_000. */
    var maxResults: Int
        get() = prefs.getInt("max_results", 500_000)
        set(value) = prefs.edit().putInt("max_results", value).apply()

    /** Default region filter key: "all", "heap_stack_anon". Default "all". */
    var defaultRegionFilter: String
        get() = prefs.getString("default_region_filter", "all") ?: "all"
        set(value) = prefs.edit().putString("default_region_filter", value).apply()

    /** Scan engine: "auto", "python", "native". Default "auto". */
    var scanEngine: String
        get() = prefs.getString("scan_engine", "auto") ?: "auto"
        set(value) = prefs.edit().putString("scan_engine", value).apply()

    // --- Freeze Settings ---

    /** Freeze write interval in milliseconds. Default 100. */
    var freezeIntervalMs: Long
        get() = prefs.getLong("freeze_interval_ms", 100L)
        set(value) = prefs.edit().putLong("freeze_interval_ms", value).apply()

    // --- Speed Hack Settings ---

    /** Default speed multiplier. Default 1.0 (normal speed). */
    var defaultSpeedMultiplier: Float
        get() = prefs.getFloat("default_speed_multiplier", 1.0f)
        set(value) = prefs.edit().putFloat("default_speed_multiplier", value).apply()

    /** Auto-enable speed hack when selecting a process. Default false. */
    var autoEnableSpeedHack: Boolean
        get() = prefs.getBoolean("auto_enable_speed_hack", false)
        set(value) = prefs.edit().putBoolean("auto_enable_speed_hack", value).apply()

    // --- Floating Icon Settings ---

    /** Show floating icon overlay. Default true. */
    var floatingIconEnabled: Boolean
        get() = prefs.getBoolean("floating_icon_enabled", true)
        set(value) = prefs.edit().putBoolean("floating_icon_enabled", value).apply()

    /** Last X position of floating icon. Default -1 (unset). */
    var floatingIconX: Int
        get() = prefs.getInt("floating_icon_x", -1)
        set(value) = prefs.edit().putInt("floating_icon_x", value).apply()

    /** Last Y position of floating icon. Default -1 (unset). */
    var floatingIconY: Int
        get() = prefs.getInt("floating_icon_y", -1)
        set(value) = prefs.edit().putInt("floating_icon_y", value).apply()

    // --- Scan State for Floating Icon ---

    /** Whether a scan is currently running. Default false. */
    var isScanning: Boolean
        get() = prefs.getBoolean("is_scanning", false)
        set(value) = prefs.edit().putBoolean("is_scanning", value).apply()

    /** Scan progress percentage (0-100). Default 0. */
    var scanProgress: Int
        get() = prefs.getInt("scan_progress", 0)
        set(value) = prefs.edit().putInt("scan_progress", value).apply()

    /** User dismissed the first-launch Python setup prompt. */
    var pythonSetupPromptDismissed: Boolean
        get() = prefs.getBoolean("python_setup_prompt_dismissed", false)
        set(value) = prefs.edit().putBoolean("python_setup_prompt_dismissed", value).apply()
}
