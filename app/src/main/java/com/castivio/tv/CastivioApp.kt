package com.castivio.tv

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Deliberately does no work in [onCreate]. Startup on a 2 GB TV stick is
 * dominated by what the Application class touches, so capability detection,
 * playlist loading and cache warming all happen lazily, off the main thread.
 */
@HiltAndroidApp
class CastivioApp : Application()
