package com.castivio.tv

import android.app.Application
import android.os.StrictMode
import dagger.hilt.android.HiltAndroidApp

/**
 * Deliberately does no work.
 *
 * Cold start on a 2 GB stick is dominated by what Application touches, so
 * capability detection, database opening, playlist import and cache warming
 * are all lazy and off the main thread. Adding eager initialisation here is
 * the easiest way to make Castivio slow — don't.
 */
@HiltAndroidApp
class CastivioApp : Application() {

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) enableStrictMode()
    }

    /**
     * Fails loudly on main-thread disk or network access during development,
     * so a stall is caught here rather than shipped and reported as "laggy".
     */
    private fun enableStrictMode() {
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .penaltyLog()
                .build(),
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectLeakedClosableObjects()
                .penaltyLog()
                .build(),
        )
    }
}
