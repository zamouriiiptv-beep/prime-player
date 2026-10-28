package com.castivio.data.networking

import android.util.Log
import com.castivio.core.platform.WireFigures
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import okhttp3.Protocol
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
        internal val waitNanos = AtomicLong()
        internal val slowestNanos = AtomicLong()
        internal val bytes = AtomicLong()

        /** How the answers travelled; see [Wire]. */
        internal val wire = Wire()

        val count: Long get() = calls.get()
        val failed: Long get() = failures.get()
        val totalMs: Double get() = totalNanos.get() / 1_000_000.0
        val slowestMs: Double get() = slowestNanos.get() / 1_000_000.0
        val meanMs: Double get() = if (count == 0L) 0.0 else totalMs / count
        val kilobytes: Long get() = bytes.get() / 1024

        /** The part of [totalMs] before each answer began: the server, not the transfer. */
        val waitMs: Double get() = waitNanos.get() / 1_000_000.0

        /** What the answers carried, exactly, for a reading that wants more than kilobytes. */
        val byteCount: Long get() = bytes.get()

        /** The wire figures for a report, or null when nothing about this action's transport was read. */
        fun wireFigures(): WireFigures? = wire.figures()

        override fun toString(): String = "%s: %d calls, %d failed, mean %.0f ms, slowest %.0f ms, %d KB"
            .format(action, count, failed, meanMs, slowestMs, kilobytes)
    }

    /**
     * What one action's answers were made of and how they came: the evidence for "is the provider compressing, and
     * is the wait the connection's or ours".
     *
     * Plain sums and a few small sets of names. The names are protocol and encoding tokens -- `h2`, `gzip` -- read
     * from headers the provider sent back; nothing here is, or is built from, an address or a credential.
     * Sets are maps because `ConcurrentHashMap.newKeySet` is API 24 and this module ships to API 21.
     */
    internal class Wire {
        private val protocols = ConcurrentHashMap<String, Boolean>()
        private val asked = ConcurrentHashMap<String, Boolean>()
        private val encodings = ConcurrentHashMap<String, Boolean>()
        val encodedCalls = AtomicLong()
        val declaredCalls = AtomicLong()
        val declaredBytes = AtomicLong()
        val metered = AtomicLong()
        val decodedBytes = AtomicLong()
        val sniffedGzipCalls = AtomicLong()
        val setupNanos = AtomicLong()
        val firstByteNanos = AtomicLong()
        val transferNanos = AtomicLong()
        val blockedNanos = AtomicLong()
        private val seen = AtomicLong()

        fun protocol(name: String) {
            protocols.putIfAbsent(name, true)
        }

        fun asked(value: String) {
            asked.putIfAbsent(value, true)
        }

        fun encoding(value: String) {
            encodings.putIfAbsent(value, true)
        }

        /** One call's transport, as its listener saw it. */
        fun call(
            setup: Long,
            firstByte: Long,
            transfer: Long,
        ) {
            seen.incrementAndGet()
            setupNanos.addAndGet(setup)
            firstByteNanos.addAndGet(firstByte)
            transferNanos.addAndGet(transfer)
        }

        fun figures(): WireFigures? {
            if (seen.get() == 0L) return null
            return WireFigures(
                protocols = protocols.keys.sorted(),
                asked = asked.keys.sorted(),
                encodings = encodings.keys.sorted(),
                encodedCalls = encodedCalls.get().toInt(),
                declaredCalls = declaredCalls.get().toInt(),
                declaredBytes = declaredBytes.get(),
                metered = metered.get().toInt(),
                decodedBytes = decodedBytes.get(),
                sniffedGzipCalls = sniffedGzipCalls.get().toInt(),
                setupMs = setupNanos.get() / NANOS_PER_MS,
                firstByteMs = firstByteNanos.get() / NANOS_PER_MS,
                transferMs = transferNanos.get() / NANOS_PER_MS,
                blockedMs = blockedNanos.get() / NANOS_PER_MS,
            )
        }
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
     * How much of [callTimeMs] was spent waiting for the server's first answer: from the
     * call's start to its response headers, summed.
     *
     * The rest of a call is its body arriving and being parsed — a call does not end until
     * its body has been read, and the import reads it as it parses — so the two are
     * different costs. This is the one a request costs merely by being made, and the one
     * that shrinks when the same rows come in fewer requests.
     */
    fun waitTimeMs(): Long = (tallies.values.sumOf { it.waitNanos.get() } / 1_000_000)

    /**
     * How much came back, across every call in the window.
     *
     * Counted per call from `responseBodyEnd` since this object existed, and summed here
     * because the loading screen says it out loud: a viewer watching a progress bar for
     * four minutes is owed the size of what is arriving. Bytes rather than a formatted
     * string — how to spell `7.2 MB` is a presentation decision, and this is a counter.
     */
    fun totalBytes(): Long = tallies.values.sumOf { it.bytes.get() }

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
        add("of which waiting for an answer: %d ms (sum)".format(waitTimeMs()))
        if (spanMs() > 0) {
            // Above 1.0 means requests overlapped; at or below it they were serial.
            add("overlap factor: %.2f — 1.00 is fully sequential".format(sum / spanMs()))
        }
        snapshot().forEach { tally ->
            add("  $tally")
            tally.wireFigures()?.describe(tally.byteCount, tally.count.toInt())?.forEach { add("    $it") }
        }
    }

    /** Prints [report] at INFO, which is what `adb logcat -s CastivioNet:I` picks up. */
    fun logSummary(title: String) {
        report(title).forEach { Log.i(TAG, it) }
    }

    internal fun record(action: String, nanos: Long, bytes: Long, failed: Boolean, waitNanos: Long = 0) {
        val tally = tallies.computeIfAbsent(action, ::Tally)
        tally.calls.incrementAndGet()
        if (failed) tally.failures.incrementAndGet()
        tally.totalNanos.addAndGet(nanos)
        tally.waitNanos.addAndGet(waitNanos)
        tally.bytes.addAndGet(bytes)
        // A plain compare-and-set loop: `updateAndGet` would be tidier and is API 24,
        // and this module ships to API 21.
        while (true) {
            val current = tally.slowestNanos.get()
            if (nanos <= current || tally.slowestNanos.compareAndSet(current, nanos)) break
        }
    }

    /**
     * One call's transport, from its listener: how long before a byte could flow, how long the provider took to
     * start answering, how long the answer then took to arrive, and what it said about itself.
     *
     * [declaredLength] is the `Content-Length` header and null when the answer came chunked. Filed under the same
     * [action] [record] uses, so the two halves of one call are the same row.
     */
    internal fun recordWire(
        action: String,
        protocol: String?,
        asked: String?,
        encoding: String?,
        declaredLength: Long?,
        setupNanos: Long,
        firstByteNanos: Long,
        transferNanos: Long,
    ) {
        val wire = tallies.computeIfAbsent(action, ::Tally).wire
        protocol?.let(wire::protocol)
        asked?.let(wire::asked)
        if (encoding != null) {
            wire.encoding(encoding)
            // `identity` is the provider saying it did not compress: a name, but not an encoding.
            if (!encoding.equals("identity", ignoreCase = true)) wire.encodedCalls.incrementAndGet()
        }
        if (declaredLength != null) {
            wire.declaredCalls.incrementAndGet()
            wire.declaredBytes.addAndGet(declaredLength)
        }
        wire.call(setupNanos, firstByteNanos, transferNanos)
    }

    /**
     * The body one call's parser was handed, from the meter on its reader: how much, and whether the app itself had
     * to unpack it ([sniffedGzip]) because the provider sent gzip and did not say so.
     */
    internal fun recordDecoded(action: String, bytes: Long, sniffedGzip: Boolean) {
        val wire = tallies.computeIfAbsent(action, ::Tally).wire
        wire.metered.incrementAndGet()
        wire.decodedBytes.addAndGet(bytes)
        if (sniffedGzip) wire.sniffedGzipCalls.incrementAndGet()
    }

    /** How long one call's reader sat inside reads waiting for the connection, from the same meter. */
    internal fun recordBlocked(action: String, nanos: Long) {
        tallies.computeIfAbsent(action, ::Tally).wire.blockedNanos.addAndGet(nanos)
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

    private const val NANOS_PER_MS = 1_000_000L
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
        val action = CallMetrics.label(call.request().url)
        CallMetrics.record(
            action = action,
            nanos = now - begin,
            bytes = bytesRead,
            failed = failed,
            // Up to the headers, or the whole call when there were none to wait for.
            waitNanos = (if (headersAt > 0) headersAt else now) - begin,
        )
        // Only a call that was answered has a transport worth reading; a failure before any header has none.
        if (headersAt > 0) {
            CallMetrics.recordWire(
                action = action,
                protocol = protocol,
                asked = asked,
                encoding = encoding,
                declaredLength = declaredLength,
                setupNanos = setupNanos,
                firstByteNanos = if (sentAt > 0 && firstByteAt >= sentAt) firstByteAt - sentAt else 0,
                // To the last byte read, or to now when the body never ended (a call cancelled mid-reply).
                transferNanos = ((if (bodyEndAt > 0) bodyEndAt else now) - finalHeadersAt).coerceAtLeast(0),
            )
        }
        bytesRead = 0
        headersAt = 0
    }

    /**
     * When the response's headers arrived, on the same clock as [started].
     *
     * Per listener instance, like [bytesRead]: one listener per call through [FACTORY].
     * `callStart` is read from a thread local because it was written that way; the
     * headers are reported on the thread that executes the call, which is the same one.
     */
    private var headersAt: Long = 0

    /**
     * How much came back, from the one event that knows.
     *
     * Per listener instance rather than per call because OkHttp creates one listener per
     * call through the factory — see [CallMetricsListener.FACTORY].
     */
    private var bytesRead: Long = 0

    override fun responseBodyEnd(call: Call, byteCount: Long) {
        bytesRead = byteCount
        bodyEndAt = System.nanoTime()
    }

    /*
     * The transport of this one call, read from the events OkHttp raises as it happens and from the headers the
     * provider sent back. Per listener instance, like the fields above: one listener per call.
     *
     * Read, not influenced: none of these events can change what is sent or how the body is read.
     */
    private var protocol: String? = null
    private var asked: String? = null
    private var encoding: String? = null
    private var declaredLength: Long? = null
    private var sentAt: Long = 0
    private var firstByteAt: Long = 0
    private var finalHeadersAt: Long = 0
    private var bodyEndAt: Long = 0
    private var setupNanos: Long = 0
    private var lookupAt: Long = 0
    private var connectAt: Long = 0

    override fun dnsStart(call: Call, domainName: String) {
        lookupAt = System.nanoTime()
    }

    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
        if (lookupAt > 0) setupNanos += System.nanoTime() - lookupAt
        lookupAt = 0
    }

    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
        connectAt = System.nanoTime()
    }

    override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) {
        settleConnect()
    }

    override fun connectFailed(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?,
        ioe: IOException,
    ) {
        settleConnect()
    }

    /** TCP and TLS together, which is what OkHttp's `connectEnd` closes; a failed attempt still cost its time. */
    private fun settleConnect() {
        if (connectAt > 0) setupNanos += System.nanoTime() - connectAt
        connectAt = 0
    }

    override fun requestHeadersEnd(call: Call, request: Request) {
        // The request as it leaves, after the bridge added what it adds: this is where `Accept-Encoding` shows.
        asked = request.header("Accept-Encoding")
        sentAt = System.nanoTime()
    }

    override fun responseHeadersStart(call: Call) {
        // The last one wins: a redirect answers once for the hop and once for the reply that has the body.
        firstByteAt = System.nanoTime()
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
        if (headersAt == 0L) headersAt = System.nanoTime()
        // Raw, as the provider sent them: the bridge that unpacks a gzip body drops these two headers from the
        // response the app is later given, so this is the only place that can still see them.
        finalHeadersAt = System.nanoTime()
        protocol = response.protocol.toString()
        encoding = response.header("Content-Encoding")
        declaredLength = response.header("Content-Length")?.trim()?.toLongOrNull()
        // A cached response has no body event, so the declared length is the only size
        // signal. Overwritten by `responseBodyEnd` when a body really is read.
        if (bytesRead == 0L) bytesRead = response.body?.contentLength()?.coerceAtLeast(0) ?: 0
    }

    companion object {
        /** One listener per call, which is what makes the per-call fields safe. */
        val FACTORY = EventListener.Factory { CallMetricsListener() }
    }
}
