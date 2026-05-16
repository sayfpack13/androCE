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
}
