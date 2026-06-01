package com.androce

import android.app.Application
import android.content.Context
import com.androce.core.AppLogger
import com.androce.core.AppPrefs
import com.androce.core.MemoryReader
import com.androce.core.virtual.VirtualEngineFacade
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AndroCEApp : Application() {

    companion object {
        lateinit var instance: AndroCEApp
            private set
    }

    override fun attachBaseContext(base: Context) {
        VirtualEngineFacade.attachBaseContext(base)
        super.attachBaseContext(base)
    }

    override fun onCreate() {
        super.onCreate()
        VirtualEngineFacade.onCreate()
        instance = this
        AppLogger.init(this)
        AppPrefs.init(this)
        Shell.enableVerboseLogging = false
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setTimeout(30)
        )
        // Extract memscan early so the BlackBox guest can copy it for non-root scans.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            MemoryReader.init(this@AndroCEApp)
        }
    }
}
