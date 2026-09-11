package com.castivio.data.networking

import android.util.Log
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.HttpUrl
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * What the app actually asked the network for, counted and timed.
 *
 * ## The question this exists to answer
 *
 * Phase A established that opening a section costs `1 + N` sequential round trips, where
 * `N` is how many categories the provider has — and that `N` was **the only unknown in
 * the whole analysis**. Every estimate of first-load latency is a guess until somebody
 * counts. Guessing it would have been the easiest number in the report to invent and the
 * least defensible.
 *
 * So this counts. It does not change a single request, a header, a timeout or the cache:
 * an OkHttp [EventListener] is told what happened and is given no way to alter it, which
 * is precisely why it is the right instrument for a phase that is forbidden to optimise.
 *
 * ## What is recorded
 *
 * One row per **action** rather than per URL. An Xtream URL carries the credentials and
 * the category id in its query string, so a per-URL tally would be a thousand rows of
 * one call each and a log nobody can read — and it would put a password in a logcat
 * line. The action is the `action=` parameter, which is the provider's own name for the
 * kind of request, and the category id is deliberately dropped.
 *
 * ## Credentials never appear here
 *
 * [label] reads one query parameter and nothing else. No URL, no host, no username, no
 * password reaches a counter, a log line or a report. That is a hard rule rather than a
 * precaution: this is the one object in the app whose entire purpose is to be printed.
 */
object CallMetrics {

    /** One action's tally. Every field is a plain sum; the arithmetic is done on read. */
    class Tally internal constructor(val action: String) {
        internal val calls = AtomicLong()
        internal val failures = AtomicLong()
        internal val totalNanos = AtomicLong()
        internal val slowestNanos = AtomicLong()
        internal val bytes = AtomicLong()

        val count: Long get() = calls.get()
        val failed: Long get() = failures.get()
        val totalMs: Double get() = totalNanos.get() / 1_000_000.0
        val slowestMs: Double get() = slowestNanos.get() / 1_000_000.0
        val meanMs: Double get() = if (count == 0L) 0.0 else totalMs / count
        val kilobytes: Long get() = bytes.get() / 1024

        override fun toString(): String = "%s: %d calls, %d failed, mean %.0f ms, slowest %.0f ms, %d KB"
            .format(action, count, failed, meanMs, slowestMs, kilobytes)
    }

    private val tallies = ConcurrentHashMap<String, Tally>()

    /** Wall-clock span of the window, so "12 s of requests" can be read against it. */
    private val firstCallAtNanos = AtomicLong(0)
    private val lastCallAtNanos = AtomicLong(0)

    /**
     * Everything counted since the last [reset], ordered by how much time each action
     * cost in total — which is the order somebody looking for a bottleneck wants.
     */
    fun snapshot(): List<Tally> = tallies.values.sortedByDescending { it.totalNanos.get() }

    /** Total calls across every action: the `1 + N` of Phase A, measured. */
    fun totalCalls(): Long = tallies.values.sumOf { it.count }

    /**
     * How long the provider took, summed across every call.
     *
     * The number to set beside "time to first content": one is how long the server
     * spent, the other is how long the viewer waited, and a report that gives only the
     * second cannot say whose fault it is.
     */
    fun callTimeMs(): Long = (tallies.values.sumOf { it.totalNanos.get() } / 1_000_000)

    /**
     * How many of those calls OkHttp answered without going to the network.
     *
     * Counted from the events OkHttp raises per call, so "this was served from the
     * cache" is something the client **said**, not something inferred from a timing
     * that happened to be short. That distinction is why a cache hit can be reported
     * at all: an inferred one would be a guess dressed as a measurement.
     */
    fun cacheHits(): Long = cacheServed.get()

    /**
     * How long the whole window took, first request opened to last one finished.
     *
     * Not the sum of the calls. Sequential requests sum to roughly the span and
     * concurrent ones sum to much more than it, so the two numbers read together say
     * whether anything was actually overlapped — which is the specific claim Phase A
     * made about the import and could not prove.
     */
    fun spanMs(): Double {
        val first = firstCallAtNanos.get()
        val last = lastCallAtNanos.get()
        return if (first == 0L || last <= first) 0.0 else (last - first) / 1_000_000.0
    }

    fun reset() {
        tallies.clear()
        firstCallAtNanos.set(0)
        lastCallAtNanos.set(0)
        cacheServed.set(0)
    }

    private val cacheServed = AtomicLong()

    internal fun recordCacheHit() {
        cacheServed.incrementAndGet()
    }

    /**
     * The tally as lines, for a log, a report or a test.
     *
     * Returned rather than printed so the caller decides where it goes: a benchmark
     * asserts on it, [logSummary] prints it, and neither has to agree with the other
     * about the format.
     */
    fun report(title: String): List<String> = buildList {
        val calls = totalCalls()
        add("── $title ──")
        add("total: $calls call(s) over %.0f ms of wall clock".format(spanMs()))
        add("served from cache: ${cacheHits()} of $calls")
        val sum = tallies.values.sumOf { it.totalNanos.get() } / 1_000_000.0
        add("time in calls: %.0f ms (sum) against %.0f ms (span)".format(sum, spanMs()))
        if (spanMs() > 0) {
            // Above 1.0 means requests overlapped; at or below it they were serial.
            add("overlap factor: %.2f — 1.00 is fully sequential".format(sum / spanMs()))
        }
        snapshot().forEach { add("  $it") }
    }

    /** Prints [report] at INFO, which is what `adb logcat -s CastivioNet:I` picks up. */
    fun logSummary(title: String) {
        report(title).forEach { Log.i(TAG, it) }
    }

    internal fun record(action: String, nanos: Long, bytes: Long, failed: Boolean) {
        val tally = tallies.computeIfAbsent(action, ::Tally)
        tally.calls.incrementAndGet()
        if (failed) tally.failures.incrementAndGet()
        tally.totalNanos.addAndGet(nanos)
        tally.bytes.addAndGet(bytes)
        // A plain compare-and-set loop: `updateAndGet` would be tidier and is API 24,
        // and this module ships to API 21.
        while (true) {
            val current = tally.slowestNanos.get()
            if (nanos <= current || tally.slowestNanos.compareAndSet(current, nanos)) break
        }
    }

    internal fun markStart(atNanos: Long) {
        firstCallAtNanos.compareAndSet(0, atNanos)
    }

    internal fun markEnd(atNanos: Long) {
        while (true) {
            val current = lastCallAtNanos.get()
            if (atNanos <= current || lastCallAtNanos.compareAndSet(current, atNanos)) break
        }
    }

    /**
     * What to file a call under.
     *
     * The Xtream `action` where there is one, the last path segment otherwise — enough
     * to tell a playlist download from a guide download without carrying the address of
     * either. Anything unrecognised is `other`, deliberately: a bucket that cannot be
     * mistaken for a real endpoint is better than a URL nobody meant to print.
     */
    internal fun label(url: HttpUrl): String {
        url.queryParameter("action")?.let { return it }
        if (url.pathSegments.lastOrNull() == "xmltv.php") return "xmltv"
        if (url.queryParameter("username") != null) return "xtream-account"
        return "other"
    }

    const val TAG = "CastivioNet"
}

/**
 * Feeds [CallMetrics], and does nothing else.
 *
 * An [EventListener] is handed events after the fact and has no way to modify a request,
 * a response, the cache or a timeout. That is not a limitation here, it is the reason
 * this is the instrument chosen for a phase whose first rule is to change no behaviour.
 *
 * `callStart` and `callEnd` are the outer bounds of one call including connection reuse,
 * DNS and TLS, which is what a user waits through — rather than `responseBodyEnd`, which
 * would time only the part after the socket was ready.
 */
class CallMetricsListener : EventListener() {

    private val started = ThreadLocal<Long>()

    override fun callStart(call: Call) {
        val now = System.nanoTime()
        started.set(now)
        CallMetrics.markStart(now)
    }

    override fun callEnd(call: Call) = finish(call, failed = false)

    override fun callFailed(call: Call, ioe: IOException) = finish(call, failed = true)

    private fun finish(call: Call, failed: Boolean) {
        val now = System.nanoTime()
        val begin = started.get() ?: now
        started.remove()
        CallMetrics.markEnd(now)
        CallMetrics.record(
            action = CallMetrics.label(call.request().url),
            nanos = now - begin,
            bytes = bytesRead,
            failed = failed,
        )
        bytesRead = 0
    }

    /**
     * How much came back, from the one event that knows.
     *
     * Per listener instance rather than per call because OkHttp creates one listener per
     * call through the factory — see [CallMetricsListener.FACTORY].
     */
    private var bytesRead: Long = 0

    override fun responseBodyEnd(call: Call, byteCount: Long) {
        bytesRead = byteCount
    }

    /**
     * OkHttp answered this call from its own cache and never opened a socket.
     *
     * One of three cache events OkHttp raises. `cacheConditionalHit` counts too: the
     * client asked the server whether its copy was still good and was told yes, so the
     * body was not transferred. `cacheMiss` is the ordinary case and is not counted,
     * because it is what every uncached call already is.
     */
    override fun cacheHit(call: Call, response: Response) = CallMetrics.recordCacheHit()

    override fun cacheConditionalHit(call: Call, cachedResponse: Response) =
        CallMetrics.recordCacheHit()

    override fun responseHeadersEnd(call: Call, response: Response) {
        // A cached response has no body event, so the declared length is the only size
        // signal. Overwritten by `responseBodyEnd` when a body really is read.
        if (bytesRead == 0L) bytesRead = response.body?.contentLength()?.coerceAtLeast(0) ?: 0
    }

    companion object {
        /** One listener per call, which is what makes the per-call fields safe. */
        val FACTORY = EventListener.Factory { CallMetricsListener() }
    }
}
