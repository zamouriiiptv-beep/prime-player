package com.castivio.playback.api

/**
 * **Temporary, for development.** One response in the chain an HTTP open followed to reach the stream: each redirect the
 * servers answered with, then the answer that carried the stream.
 *
 * A provider's address often answers with a redirect to the server that has the stream, and the time the first server
 * takes to say where to go is spent before the stream's own server is even asked. The opening's figure for the server
 * -- when it answered -- is the end of the whole chain, and its transport figures are either a sum over every hop (the
 * lookups and the connections) or the last hop's alone (the wait for an answer). A slow first hop is then only the
 * difference between them, which nothing names. This names it.
 *
 * Every instant is milliseconds after the engine was asked to open, like [OpeningMarks]'s own. **No address is kept or
 * told**: whether a hop went to another host than the hop before it, and whether its host is written as a number, are
 * the only things said about where it went.
 */
data class HttpHop(
    /** 1 for the address that was asked for; 2 and up for the ones a redirect led to. */
    val number: Int,
    /** The hop began: the first thing done for it -- a lookup, a connection, or a request on a connection kept open. */
    val startedMs: Long,
    /** The name lookup made for this hop. Null when none was: a numeric host, or a connection kept open. */
    val dnsMs: Long? = null,
    /**
     * The connection made for this hop, its TLS handshake included, and the time of any that failed before it: the
     * whole of what getting a connection cost the hop. Null when a connection kept open was used.
     */
    val connectMs: Long? = null,
    /** The TLS handshake alone, which is part of [connectMs]. */
    val tlsMs: Long? = null,
    /** Connections that were begun for this hop and failed before one succeeded. */
    val failedConnects: Int = 0,
    /** A connection kept open from before was used: nothing was looked up or connected for this hop. */
    val reusedConnection: Boolean = false,
    /** The request's headers were sent. */
    val sentMs: Long? = null,
    /** The server's own time on this hop: from the request sent to its answer beginning. */
    val waitMs: Long? = null,
    /** The answer's headers were read. Null for a hop that has not had one, or never did. */
    val answeredMs: Long? = null,
    /** The status the server answered with. */
    val status: Int? = null,
    /** This hop went to another host than the hop before it. Never which. */
    val otherHost: Boolean = false,
    /** The host is written as a numeric address, so there was no name to look up. */
    val numericHost: Boolean = false,
    /** Asked over HTTPS. */
    val secure: Boolean = false,
    /** How the hop failed, by the name of what was thrown, when it did. Never a message: a message can carry an address. */
    val failure: String? = null,
) {

    /** The server said to go elsewhere. */
    val redirect: Boolean get() = status != null && status in REDIRECT_FIRST..REDIRECT_LAST

    /** The hop from its beginning to its answer: what it added to the opening. Null until it was answered. */
    val tookMs: Long? get() = answeredMs?.let { (it - startedMs).coerceAtLeast(0) }

    private companion object {
        const val REDIRECT_FIRST = 300
        const val REDIRECT_LAST = 399
    }
}

/**
 * Where the chain ended, against where it ended the last time the same address was opened in this process: whether the
 * address a redirect leads to stays the same from one open to the next, which is what decides whether it could be
 * opened directly the next time. Neither address is kept, only a fingerprint of each.
 */
data class FinalAddress(
    /** Whether the chain ended where it ended last time. Null on the first open of the address since the process began. */
    val sameAsLast: Boolean?,
    /** How long before this answer the last one was. Null with [sameAsLast]. */
    val lastSeenAgoMs: Long?,
)

/** The chain of one open's latest try at the address, as it stood when it was read. */
data class HttpHops(
    val hops: List<HttpHop> = emptyList(),
    /** Hops beyond the number kept, counted and not described. */
    val more: Int = 0,
    /** Where the chain ended, once it has: see [FinalAddress]. */
    val finalAddress: FinalAddress? = null,
) {

    val isEmpty: Boolean get() = hops.isEmpty() && more == 0

    /** How many responses the chain has had so far, those not kept included. */
    val count: Int get() = hops.size + more

    /** How many of the kept hops were redirects. */
    val redirects: Int get() = hops.count { it.redirect }

    companion object {
        val NONE = HttpHops()
    }
}

/**
 * The record of one open's chain, written by the HTTP client's events for the call that opens the address.
 *
 * The client reports a call's events on the thread that runs the call, one after another, and the panel reads the
 * record from another thread ten times a second, so every change and every read takes [lock]: an open has a dozen
 * events, and none of them is on a path that reads the body.
 *
 * A hop begins with the first event after the hop before it was answered -- a lookup, a connection, or the connection
 * kept open being taken -- because that is the first moment anything is done for it. A new call is a new try at the
 * address and begins a new chain: what is told is the chain of the latest try.
 *
 * Nothing here decides anything about an open, and nothing in an engine reads it back: it only remembers.
 */
class HopLog(
    /** The engine's clock when it was asked to open: every figure the log gives is after this. */
    private val epochMs: Long,
    /** The engine's own monotonic clock, in milliseconds. */
    private val clock: () -> Long,
    /** The address that was asked for. Only its fingerprint is kept, as the key its final address is remembered under. */
    source: String,
    private val memory: FinalAddressMemory = FinalAddressMemory.PROCESS,
) {

    private class Entry(val number: Int, val startedAt: Long) {
        var dnsStartedAt = NOT_YET
        var dnsTotal = NOT_YET
        var connectStartedAt = NOT_YET
        var connectTotal = NOT_YET
        var tlsStartedAt = NOT_YET
        var tlsTotal = NOT_YET
        var failedConnects = 0
        var reused = false
        var sentAt = NOT_YET
        var waitMs = NOT_YET
        var answeredAt = NOT_YET
        var status: Int? = null
        var otherHost = false
        var numericHost = false
        var secure = false
        var failure: String? = null

        val done: Boolean get() = answeredAt != NOT_YET || failure != null
        val connected: Boolean get() = connectStartedAt != NOT_YET || connectTotal != NOT_YET
    }

    private val sourceKey = fingerprint(source)
    private val lock = Any()
    private val entries = ArrayList<Entry>(INITIAL_CAPACITY)
    private var more = 0
    private var current: Entry? = null
    private var previousHost: Long? = null
    private var finalAddress: FinalAddress? = null

    /** A new call at the address: a new try, and the chain is begun again. */
    fun callStarted() {
        synchronized(lock) {
            entries.clear()
            more = 0
            current = null
            previousHost = null
            finalAddress = null
        }
    }

    fun dnsStarted() {
        synchronized(lock) { hop().dnsStartedAt = clock() }
    }

    fun dnsEnded() {
        synchronized(lock) {
            val entry = current ?: return
            entry.dnsTotal = added(entry.dnsTotal, entry.dnsStartedAt)
            entry.dnsStartedAt = NOT_YET
        }
    }

    fun connectStarted() {
        synchronized(lock) { hop().connectStartedAt = clock() }
    }

    fun secureStarted() {
        synchronized(lock) { current?.tlsStartedAt = clock() }
    }

    fun secureEnded() {
        synchronized(lock) {
            val entry = current ?: return
            entry.tlsTotal = added(entry.tlsTotal, entry.tlsStartedAt)
            entry.tlsStartedAt = NOT_YET
        }
    }

    fun connectEnded() {
        synchronized(lock) {
            val entry = current ?: return
            entry.connectTotal = added(entry.connectTotal, entry.connectStartedAt)
            entry.connectStartedAt = NOT_YET
        }
    }

    /** A connection begun for the hop failed. The client may try another address of the host for the same hop. */
    fun connectFailed() {
        synchronized(lock) {
            val entry = current ?: return
            entry.connectTotal = added(entry.connectTotal, entry.connectStartedAt)
            entry.connectStartedAt = NOT_YET
            entry.tlsStartedAt = NOT_YET
            entry.failedConnects++
        }
    }

    /** The hop has its connection. One that was not made for it was kept open from before. */
    fun connectionAcquired() {
        synchronized(lock) {
            val entry = hop()
            if (!entry.connected) entry.reused = true
        }
    }

    /** The request's headers were sent, to [host], over HTTPS when [secure]. The host is compared and then forgotten. */
    fun requestSent(host: String, secure: Boolean) {
        synchronized(lock) {
            val entry = hop()
            entry.sentAt = clock()
            entry.secure = secure
            entry.numericHost = isNumericHost(host)
            val key = fingerprint(host.lowercase())
            entry.otherHost = previousHost != null && previousHost != key
            previousHost = key
        }
    }

    fun responseBegan() {
        synchronized(lock) {
            val entry = current ?: return
            if (entry.sentAt != NOT_YET && entry.waitMs == NOT_YET) entry.waitMs = (clock() - entry.sentAt).coerceAtLeast(0)
        }
    }

    /**
     * The hop's answer was read: [status], for the request to [url]. An answer that is not a redirect ends the chain,
     * and where it ended is compared with where it ended last time; the address itself is only fingerprinted.
     */
    fun answered(status: Int, url: String) {
        synchronized(lock) {
            val entry = current ?: return
            val now = clock()
            entry.answeredAt = now
            entry.status = status
            if (status !in REDIRECT_FIRST..REDIRECT_LAST) finalAddress = memory.remember(sourceKey, fingerprint(url), now)
        }
    }

    /** The hop failed before it was answered, as [reason] -- the name of what was thrown, never its message. */
    fun failed(reason: String) {
        synchronized(lock) {
            val entry = current ?: return
            if (entry.answeredAt == NOT_YET && entry.failure == null) entry.failure = reason
        }
    }

    /** The chain as it stands, in the order the hops began. */
    fun snapshot(): HttpHops = synchronized(lock) {
        HttpHops(hops = entries.map { it.asHop() }, more = more, finalAddress = finalAddress)
    }

    /** The hop the next event belongs to: the one under way, or a new one when the last was answered or failed. */
    private fun hop(): Entry {
        val under = current
        if (under != null && !under.done) return under
        val entry = Entry(number = entries.size + more + 1, startedAt = clock())
        // Counted, and described no further: a chain this long is a loop the client will give up on by itself.
        if (entries.size >= MAX_KEPT) more++ else entries += entry
        current = entry
        return entry
    }

    private fun added(total: Long, startedAt: Long): Long {
        if (startedAt == NOT_YET) return total
        val elapsed = (clock() - startedAt).coerceAtLeast(0)
        return if (total == NOT_YET) elapsed else total + elapsed
    }

    private fun Entry.asHop(): HttpHop = HttpHop(
        number = number,
        startedMs = (startedAt - epochMs).coerceAtLeast(0),
        dnsMs = dnsTotal.takeIf { it != NOT_YET },
        connectMs = connectTotal.takeIf { it != NOT_YET },
        tlsMs = tlsTotal.takeIf { it != NOT_YET },
        failedConnects = failedConnects,
        reusedConnection = reused,
        sentMs = after(sentAt),
        waitMs = waitMs.takeIf { it != NOT_YET },
        answeredMs = after(answeredAt),
        status = status,
        otherHost = otherHost,
        numericHost = numericHost,
        secure = secure,
        failure = failure,
    )

    private fun after(at: Long): Long? = if (at == NOT_YET) null else (at - epochMs).coerceAtLeast(0)

    internal companion object {
        /** "Not yet": a clock never reads it, and a fake one in a test can start at zero. */
        const val NOT_YET = -1L

        /** More hops than this in one chain is a loop; the rest are counted. */
        const val MAX_KEPT = 6

        const val INITIAL_CAPACITY = 2
        const val REDIRECT_FIRST = 300
        const val REDIRECT_LAST = 399

        /**
         * Whether [host] is written as an address rather than a name: four decimal numbers of at most 255, or anything
         * with a colon in it, which only an IPv6 address has in a host. A name that only starts with digits is a name.
         */
        fun isNumericHost(host: String): Boolean {
            val bare = host.removePrefix("[").removeSuffix("]")
            if (bare.isEmpty()) return false
            if (':' in bare) return true
            val parts = bare.split('.')
            return parts.size == IPV4_PARTS && parts.all { part ->
                part.isNotEmpty() && part.length <= IPV4_PART_DIGITS && part.all { it in '0'..'9' } && part.toInt() <= IPV4_PART_MAX
            }
        }

        /**
         * A 64-bit FNV-1a fingerprint of [text]: equal texts have equal fingerprints, and a fingerprint gives nothing of
         * its text back. It is what is kept of an address, so that two can be compared without either being held.
         */
        fun fingerprint(text: String): Long {
            var hash = FNV_OFFSET
            for (char in text) {
                hash = hash xor char.code.toLong()
                hash *= FNV_PRIME
            }
            return hash
        }

        private const val IPV4_PARTS = 4
        private const val IPV4_PART_DIGITS = 3
        private const val IPV4_PART_MAX = 255
        private const val FNV_OFFSET = -0x340d631b7bdddcdbL
        private const val FNV_PRIME = 0x100000001b3L
    }
}

/**
 * Where each address's chain ended last, by fingerprint, for the addresses opened most recently in the process: what
 * [HopLog] compares a chain's end with. Bounded, so a long evening of zapping does not grow it.
 */
class FinalAddressMemory(private val capacity: Int = DEFAULT_CAPACITY) {

    private class Seen(val finalKey: Long, val atMs: Long)

    private val seen = object : LinkedHashMap<Long, Seen>(INITIAL_CAPACITY, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Seen>?): Boolean = size > capacity
    }

    /** Remembers that [sourceKey]'s chain ended at [finalKey] at [nowMs], and says how that compares with last time. */
    @Synchronized
    fun remember(sourceKey: Long, finalKey: Long, nowMs: Long): FinalAddress {
        val last = seen[sourceKey]
        seen[sourceKey] = Seen(finalKey, nowMs)
        return if (last == null) {
            FinalAddress(sameAsLast = null, lastSeenAgoMs = null)
        } else {
            FinalAddress(sameAsLast = last.finalKey == finalKey, lastSeenAgoMs = (nowMs - last.atMs).coerceAtLeast(0))
        }
    }

    @Synchronized
    fun size(): Int = seen.size

    companion object {
        /** The process's memory: an engine built again for the next channel still compares with the last one's answers. */
        val PROCESS = FinalAddressMemory()

        private const val DEFAULT_CAPACITY = 128
        private const val INITIAL_CAPACITY = 16
        private const val LOAD_FACTOR = 0.75f
    }
}
