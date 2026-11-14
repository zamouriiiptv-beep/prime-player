package com.castivio.tv.probe.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.os.Process
import com.castivio.tv.probe.core.NetworkSnapshot
import com.castivio.tv.probe.core.ProbeEnvironment

/**
 * The two things the probe asks of the device: how many bytes this application has received, and which network it is on.
 *
 * Read-only. The network's handle is only to tell one network from another and is never printed.
 */
class AndroidProbeEnvironment(context: Context) : ProbeEnvironment {

    private val connectivity: ConnectivityManager? =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    override fun uidRxBytes(): Long? {
        val bytes = TrafficStats.getUidRxBytes(Process.myUid())
        return if (bytes == TrafficStats.UNSUPPORTED.toLong()) null else bytes
    }

    override fun network(): NetworkSnapshot {
        val manager = connectivity ?: return NetworkSnapshot(null, "unknown", null)
        val active = manager.activeNetwork ?: return NetworkSnapshot(null, "none", null)
        val capabilities = manager.getNetworkCapabilities(active)
            ?: return NetworkSnapshot(active.toString(), "unknown", null)
        val transport = when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            else -> "other"
        }
        val metered = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        return NetworkSnapshot(active.toString(), transport, metered)
    }
}
