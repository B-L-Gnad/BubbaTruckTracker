package com.bubbaman.bubbatrucktracker

import android.app.Application
import android.util.Log
import timber.log.Timber

class BubbaTruckTrackerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Timber.plant(object : Timber.DebugTree() {
            override fun isLoggable(tag: String?, priority: Int): Boolean =
                BuildConfig.DEBUG || priority >= Log.WARN
        })
    }
}
