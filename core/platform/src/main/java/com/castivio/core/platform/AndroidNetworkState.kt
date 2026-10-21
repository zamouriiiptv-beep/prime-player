package com.castivio.core.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build

/**
 * The connectivity service's answer, read at the moment it is asked.
 *
 * `NET_CAPABILITY_INTERNET` rather than `VALIDATED`: validation is the platform probing a
 * server of its own, and networks that block that probe -- some countries, some hotels --
 * would read as offline while every provider on them answers. Not being sure is "online",
 * as [NetworkState.isOnline] promises.
 */
class AndroidNetworkState(private val context: Context) : NetworkState {

    override fun isOnline(): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        // `activeNetwork` is API 23. minSdk is 21, and the boxes on 21 and 22 are exactly
        // the ones most likely to be on a weak network, so the legacy path is kept.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = manager.activeNetwork ?: return false
            val capabilities = manager.getNetworkCapabilities(network) ?: return true
            return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
        @Suppress("DEPRECATION")
        return manager.activeNetworkInfo?.isConnected == true
    }
}
