package com.castivio.data.networking

import okhttp3.Dns
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * Name lookups that remember what they found, and fall back on it when a lookup fails.
 *
 * A provider's address is looked up again for every connection the client opens, and a catalogue import opens
 * hundreds. A router's name server that drops one answer in a hundred -- or a Wi-Fi link that is gone for a second --
 * then turns into `UnknownHostException`, and the import that had read sixteen thousand rows a moment ago ends as
 * "could not reach your provider". A host that resolved a minute ago is not a host that does not exist: its address is
 * the one that answered, and it is what to try when the lookup cannot say.
 *
 * The memory is used **only when the lookup throws**. Whenever the system's answer comes, it is the one used and it
 * replaces what was remembered, so a provider that moves is followed as before. A host never resolved has nothing
 * remembered and fails as it always did, which is what a mistyped address should do.
 *
 * Kept in memory, a few dozen names at most: the provider's own, and the hosts of the posters.
 */
class RememberingDns(
    private val delegate: Dns = Dns.SYSTEM,
    private val capacity: Int = CAPACITY,
) : Dns {

    init {
        require(capacity > 0) { "capacity $capacity must be positive" }
    }

    // Least recently used first, so that a long list of poster hosts cannot push the provider's own out.
    private val known = object : LinkedHashMap<String, List<InetAddress>>(INITIAL_SIZE, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<InetAddress>>): Boolean =
            size > capacity
    }

    override fun lookup(hostname: String): List<InetAddress> {
        try {
            val found = delegate.lookup(hostname)
            if (found.isNotEmpty()) synchronized(known) { known[hostname] = found }
            return found
        } catch (e: UnknownHostException) {
            val remembered = synchronized(known) { known[hostname] } ?: throw e
            return remembered
        }
    }

    private companion object {
        const val CAPACITY = 64
        const val INITIAL_SIZE = 16
        const val LOAD_FACTOR = 0.75f
    }
}
