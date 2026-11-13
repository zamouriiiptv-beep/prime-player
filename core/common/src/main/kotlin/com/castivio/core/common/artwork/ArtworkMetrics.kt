package com.castivio.core.common.artwork

import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** How a picture's call ended. */
enum class ArtworkOutcome {
    /** A picture came. */
    OK,

    /** The call's time ran out before the picture was whole. */
    TIMEOUT,

    /** The host answered, and not with a picture: the status says what. */
    HTTP,

    /** The connection failed: refused, reset, no route, a name that did not resolve. */
    NETWORK,

    /** Nobody wants it any more -- the card left the screen. Not a failure, and not counted as one. */
    CANCELLED,
}

/**
 * Where a picture was asked of, as far as that can be said without saying an address.
 *
 * The one fact worth knowing is whether the host is the provider's own, because a provider's host has the connection's other
 * business -- the import's replies -- to share its slots with. The public picture host the playlists' posters most often name
 * is told apart for the same reason: it is fast and it is not the provider's. Anything else is [OTHER].
 */
enum class ArtworkHost { PROVIDER, TMDB, OTHER }

/**
 * What the image loader's calls have done, counted -- so that a wall of posters that did not appear can be told from one
 * that was refused, one that timed out and one that was never asked for.
 *
 * **For development.** Read by the load strip of a development build and written by an event listener on the image loader's
 * client; neither has any say in what a call does. No address, host name, user or password reaches a counter: a call is
 * filed by the kind of its end and by [ArtworkHost], which is three words.
 *
 * A class, with [ArtworkMetrics] as the one the application writes to, so a test reads a tally of its own.
 */
open class ArtworkTally {

    private val begun = AtomicLong()
    private val ok = AtomicLong()
    private val timeouts = AtomicLong()
    private val network = AtomicLong()
    private val cancelled = AtomicLong()
    private val httpTotal = AtomicLong()
    private val httpByStatus = ConcurrentHashMap<Int, AtomicLong>()
    private val retried = AtomicLong()
    private val bytes = AtomicLong()
    private val bodies = AtomicLong()
    private val timedCalls = AtomicLong()
    private val totalNanos = AtomicLong()
    private val slowestNanos = AtomicLong()
    private val waitedCalls = AtomicLong()
    private val waitNanos = AtomicLong()
    private val fromProvider = AtomicLong()
    private val fromTmdb = AtomicLong()
    private val fromOther = AtomicLong()

    /** A call has begun, to a host of kind [host]. */
    fun started(host: ArtworkHost) {
        begun.incrementAndGet()
        when (host) {
            ArtworkHost.PROVIDER -> fromProvider.incrementAndGet()
            ArtworkHost.TMDB -> fromTmdb.incrementAndGet()
            ArtworkHost.OTHER -> fromOther.incrementAndGet()
        }
    }

    /**
     * A call has ended.
     *
     * @param httpStatus the answer's status for [ArtworkOutcome.HTTP]; ignored for the rest.
     * @param bytes how many the body carried, nought for a call that had none.
     * @param callNanos the call, whole, from the moment it was made.
     * @param waitNanos how long it waited before it had a connection to use -- for a slot in the dispatcher, a name, a
     *   handshake -- or a negative number when it never got one (a call cancelled in the queue, one served from a cache).
     */
    fun ended(
        outcome: ArtworkOutcome,
        httpStatus: Int? = null,
        bytes: Long = 0L,
        callNanos: Long = 0L,
        waitNanos: Long = -1L,
    ) {
        when (outcome) {
            ArtworkOutcome.OK -> ok.incrementAndGet()
            ArtworkOutcome.TIMEOUT -> timeouts.incrementAndGet()
            ArtworkOutcome.NETWORK -> network.incrementAndGet()
            ArtworkOutcome.CANCELLED -> cancelled.incrementAndGet()
            ArtworkOutcome.HTTP -> {
                httpTotal.incrementAndGet()
                statusCounter(httpStatus ?: 0).incrementAndGet()
            }
        }
        if (outcome != ArtworkOutcome.CANCELLED) {
            timedCalls.incrementAndGet()
            totalNanos.addAndGet(callNanos)
            // A plain compare-and-set loop: `updateAndGet` is API 24 and the modules that write here ship to API 23.
            while (true) {
                val current = slowestNanos.get()
                if (callNanos <= current || slowestNanos.compareAndSet(current, callNanos)) break
            }
        }
        if (bytes > 0) {
            this.bytes.addAndGet(bytes)
            bodies.incrementAndGet()
        }
        if (waitNanos >= 0) {
            waitedCalls.incrementAndGet()
            this.waitNanos.addAndGet(waitNanos)
        }
    }

    /** A picture that failed has been asked for again. */
    fun retried() {
        retried.incrementAndGet()
    }

    /** Everything counted, as it stands, for a line to say. */
    fun figures(): ArtworkFigures {
        val started = begun.get()
        val finished = ok.get() + timeouts.get() + network.get() + cancelled.get() + httpTotal.get()
        val timed = timedCalls.get()
        val waited = waitedCalls.get()
        return ArtworkFigures(
            started = started,
            ok = ok.get(),
            timeouts = timeouts.get(),
            network = network.get(),
            cancelled = cancelled.get(),
            httpFailures = httpByStatus.entries.map { it.key to it.value.get() }.sortedBy { it.first }.toMap(),
            inFlight = (started - finished).coerceAtLeast(0L),
            retried = retried.get(),
            bytes = bytes.get(),
            bodies = bodies.get(),
            meanMs = if (timed == 0L) 0L else totalNanos.get() / timed / NANOS_PER_MS,
            slowestMs = slowestNanos.get() / NANOS_PER_MS,
            meanBeforeConnectionMs = if (waited == 0L) 0L else waitNanos.get() / waited / NANOS_PER_MS,
            fromProvider = fromProvider.get(),
            fromTmdb = fromTmdb.get(),
            fromOther = fromOther.get(),
        )
    }

    /** Starts the count again: a load begins, and its pictures are the ones to read. */
    fun reset() {
        begun.set(0)
        ok.set(0)
        timeouts.set(0)
        network.set(0)
        cancelled.set(0)
        httpTotal.set(0)
        httpByStatus.clear()
        retried.set(0)
        bytes.set(0)
        bodies.set(0)
        timedCalls.set(0)
        totalNanos.set(0)
        slowestNanos.set(0)
        waitedCalls.set(0)
        waitNanos.set(0)
        fromProvider.set(0)
        fromTmdb.set(0)
        fromOther.set(0)
    }

    /**
     * The counter of one status, or of the statuses past the eighth, which are filed together under nought: a host that
     * answers with a different code every call cannot grow the table without end.
     */
    private fun statusCounter(status: Int): AtomicLong {
        httpByStatus[status]?.let { return it }
        val key = if (httpByStatus.size >= MAX_STATUSES) 0 else status
        return httpByStatus.getOrPut(key) { AtomicLong() }
    }

    private companion object {
        const val NANOS_PER_MS = 1_000_000L
        const val MAX_STATUSES = 8
    }
}

/** The tally the application's image loader writes to and the load strip reads. */
object ArtworkMetrics : ArtworkTally()

/**
 * [ArtworkTally] as it stood, and the two lines the strip draws from it.
 *
 * Two lines because the strip clips a line at three rows of a phone's width. The first answers *why is the wall empty?* --
 * how many calls, and how each that failed failed, and how many are still on their way and how many were asked again. The
 * second answers *is it the size or the host?* -- the bytes a picture takes, how long a call lasts, how long it waits for a
 * connection before it can begin, and whose host it is.
 */
data class ArtworkFigures(
    val started: Long,
    val ok: Long,
    val timeouts: Long,
    val network: Long,
    val cancelled: Long,
    /** The statuses the hosts refused with and how many times each; nought stands for every status past the eighth. */
    val httpFailures: Map<Int, Long>,
    val inFlight: Long,
    val retried: Long,
    val bytes: Long,
    /** How many calls carried a body: the bytes are shared among these. */
    val bodies: Long,
    val meanMs: Long,
    val slowestMs: Long,
    val meanBeforeConnectionMs: Long,
    val fromProvider: Long,
    val fromTmdb: Long,
    val fromOther: Long,
) {

    /** The lines for the strip and the log; none for a window in which no picture was asked for. Western digits always. */
    fun describe(): List<String> {
        if (started == 0L) return emptyList()
        val http = if (httpFailures.isEmpty()) {
            "none"
        } else {
            httpFailures.entries.joinToString(" ") { (status, count) -> "${if (status == 0) "other" else status.toString()}×$count" }
        }
        val size = if (bodies > 0) "${megabytes(bytes)} (${bytes / bodies / BYTES_PER_KB} KB each)" else "no body yet"
        return listOf(
            "artwork: $started calls · ok $ok · timeout $timeouts · http $http · network $network" +
                " · cancelled $cancelled · in flight $inFlight · asked again $retried",
            "artwork: $size · call (s) mean ${seconds(meanMs)} slowest ${seconds(slowestMs)}" +
                " · before a connection (s) mean ${seconds(meanBeforeConnectionMs)}" +
                " · hosts provider $fromProvider · tmdb $fromTmdb · other $fromOther",
        )
    }

    private fun megabytes(bytes: Long): String = String.format(Locale.ROOT, "%.1f MB", bytes / (BYTES_PER_KB * BYTES_PER_KB).toDouble())

    private fun seconds(ms: Long): String = String.format(Locale.ROOT, "%.2f", ms / MS_PER_S)

    private companion object {
        const val BYTES_PER_KB = 1_024L
        const val MS_PER_S = 1_000.0
    }
}
