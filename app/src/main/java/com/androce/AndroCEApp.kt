package com.androce

import android.app.Application
import com.topjohnwu.superuser.Shell

class AndroCEApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Shell.enableVerboseLogging = false
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setTimeout(30)
        )
    }
}
