package com.androce

import android.app.Application
import com.androce.core.AppLogger
import com.androce.core.MemoryReader
import com.topjohnwu.superuser.Shell

class AndroCEApp : Application() {

    override fun onCreate() {
        super.onCreate()
        AppLogger.init(this)
        Shell.enableVerboseLogging = false
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setTimeout(30)
        )
        MemoryReader.init(this)
    }
}
