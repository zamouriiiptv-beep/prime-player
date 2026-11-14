package com.castivio.tv.probe.run

import com.castivio.tv.probe.core.ConcurrencyProfile
import com.castivio.tv.probe.core.Fetch
import com.castivio.tv.probe.core.FlowTimeline
import com.castivio.tv.probe.core.InvalidReason
import com.castivio.tv.probe.core.NetworkSnapshot
import com.castivio.tv.probe.core.OneReplyScan
import com.castivio.tv.probe.core.Outcome
import com.castivio.tv.probe.core.ParallelEvidence
import com.castivio.tv.probe.core.ProbeConfig
import com.castivio.tv.probe.core.RequestKind
import com.castivio.tv.probe.core.RequestRecord
import com.castivio.tv.probe.core.RunOutcome
import com.castivio.tv.probe.core.RunRecord
import com.castivio.tv.probe.core.RunRequest
import com.castivio.tv.probe.core.SafetyGuard
import com.castivio.tv.probe.core.WalkScan
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** A request the probe itself ended: the session was stopped, or the run passed a limit. */
internal class CancelledException : IOException("the probe stopped this request")

internal class OversizeException : IOException("the run passed its data limit")

/** A stream that counts what passes through it and tells [onBytes] before the caller sees it. */
internal class CountingStream(
    private val delegate: InputStream,
    private val onBytes: (Int) -> Unit,
) : InputStream() {

    var total: Long = 0L
        private set

    override fun read(): Int {
        val value = delegate.read()
        if (value >= 0) {
            total++
            onBytes(1)
        }
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val count = delegate.read(buffer, offset, length)
        if (count > 0) {
            total += count
            onBytes(count)
        }
        return count
    }

    override fun available(): Int = delegate.available()

    override fun close() {
        delegate.close()
    }
}

internal class NamedThreads(private val prefix: String) : ThreadFactory {
    private val count = AtomicInteger()

    override fun newThread(task: Runnable): Thread =
        Thread(task, "$prefix-${count.incrementAndGet()}").also { it.isDaemon = true }
}

/** What a run read from the bodies, when it was asked to read any. */
internal class Scanned(
    val categories: List<String>,
    val duplicateCategoryIds: Int,
    val reply: OneReplyScan? = null,
    val walk: WalkScan? = null,
)

/** How one request for films ended, and how many of its rows had no id. */
internal class FilmFetch(
    val ok: Boolean,
    val withoutId: Int,
)

/**
 * One run in progress: every request it makes goes through [fetch], and what each did is kept here.
 *
 * It is shared by the threads of a run, so everything mutable is behind a lock or an atomic.
 */
internal class RunState(
    val request: RunRequest,
    val events: ProbeEvents,
    val client: OkHttpClient,
    private val clock: () -> Long,
    bucketMs: Long,
) {
    private val startNs: Long = clock()

    val flow = FlowTimeline(bucketMs)

    /** Bytes counted in every body of the run. */
    val bytes = AtomicLong()

    /** A status that ends the session (403, 429, 5xx), or 0. */
    val stopStatus = AtomicInteger(0)

    /** A refusal of the categories list or of the one reply, or 0. */
    val fatalStatus = AtomicInteger(0)

    @Volatile
    var unexpected: String? = null

    private val lock = Any()
    private val records = ArrayList<RequestRecord>()
    private val inFlight = ArrayList<Call>()
    private val cancel = AtomicReference<InvalidReason?>(null)

    val cancelReason: InvalidReason? get() = cancel.get()

    val cancelled: Boolean get() = cancel.get() != null || request.control.cancelled

    fun nowMs(): Long = (clock() - startNs) / NANOS_PER_MS

    fun requestCount(): Int = synchronized(lock) { records.size }

    fun inFlightCount(): Int = synchronized(lock) { inFlight.size }

    fun records(): List<RequestRecord> = synchronized(lock) { ArrayList(records) }

    /** Ends every request in flight and refuses new ones. The first reason given is the one kept. */
    fun cancelAll(reason: InvalidReason) {
        cancel.compareAndSet(null, reason)
        val calls = synchronized(lock) { ArrayList(inFlight) }
        for (call in calls) call.cancel()
    }

    /**
     * One request, from the call to the last byte [consume] reads, recorded whatever its end.
     *
     * [consume] reads the body from a stream that counts it. It is where a verification run reads two fields of each
     * film, and where a timed run reads nothing at all.
     *
     * @return whether it ended well.
     */
    fun fetch(kind: RequestKind, category: String?, url: String, consume: (InputStream) -> Unit): Boolean {
        val startMs = nowMs()
        var outcome = Outcome.OK
        var status: Int? = null
        var counting: CountingStream? = null
        var call: Call? = null
        var trace: CallTrace? = null
        try {
            if (cancelled) throw CancelledException()
            val built = client.newCall(Request.Builder().url(url).get().build())
            val callTrace = events.trace(built)
            call = built
            trace = callTrace
            register(built)
            val response = built.execute()
            try {
                status = response.code
                if (!response.isSuccessful) {
                    outcome = Outcome.HTTP
                    reportStatus(kind, response.code)
                } else {
                    val body = response.body
                    if (body == null) {
                        outcome = Outcome.TRUNCATED
                    } else {
                        val stream = CountingStream(body.byteStream()) { count -> onBytes(callTrace, count) }
                        counting = stream
                        consume(stream)
                        val declared = body.contentLength()
                        if (declared >= 0L && declared != stream.total) outcome = Outcome.TRUNCATED
                    }
                }
            } finally {
                response.close()
            }
        } catch (e: Exception) {
            outcome = if (cancelReason != null && e !is OversizeException) Outcome.CANCELLED else FailureClass.of(e)
        } finally {
            val finished = call
            if (finished != null) unregister(finished)
        }
        val record = RequestRecord(
            kind = kind,
            category = category,
            startMs = startMs,
            endMs = nowMs(),
            firstByteMs = trace?.firstByteMs(),
            bytes = counting?.total ?: 0L,
            outcome = outcome,
            status = status,
            connection = trace?.connection?.takeIf { it >= 0 },
            protocol = trace?.protocol,
        )
        synchronized(lock) { records += record }
        return outcome == Outcome.OK
    }

    private fun register(call: Call) {
        synchronized(lock) { inFlight += call }
        if (cancelled) call.cancel()
    }

    private fun unregister(call: Call) {
        synchronized(lock) { inFlight.remove(call) }
    }

    /** Counts a chunk of body for the run and the connection that brought it, and stops the run if it must be stopped. */
    private fun onBytes(trace: CallTrace, count: Int) {
        val total = bytes.addAndGet(count.toLong())
        flow.add(trace.connection, nowMs(), count)
        if (cancelled) throw CancelledException()
        if (total > request.limits.maxBytes) {
            cancelAll(InvalidReason.OVERSIZE)
            throw OversizeException()
        }
    }

    private fun reportStatus(kind: RequestKind, code: Int) {
        if (SafetyGuard.stopsSession(code)) {
            stopStatus.compareAndSet(0, code)
            cancelAll(InvalidReason.PROVIDER_STOP)
        } else if (kind != RequestKind.CATEGORY) {
            fatalStatus.compareAndSet(0, code)
        }
    }

    /** The run as a record, with everything the report says about it. */
    fun outcome(
        scanned: Scanned?,
        rxBefore: Long?,
        rxAfter: Long?,
        networkBefore: NetworkSnapshot,
        networkAfter: NetworkSnapshot,
        monitor: RunMonitor,
        config: ProbeConfig,
    ): RunOutcome {
        val step = request.step
        val all = records()
        val counted = bytes.get()
        val summary = ConcurrencyProfile.analyse(flow.snapshot(), step.arm.workers, config.bucketMs)
        val evidence = ParallelEvidence(
            workers = step.arm.workers,
            connectionsUsed = all.mapNotNull { it.connection }.toSet().size,
            connectionsWithBytes = summary.connectionsWithBytes,
            newConnections = events.connects.get(),
            remoteEndpoints = events.remoteEndpoints,
            protocols = all.mapNotNull { it.protocol }.toSortedSet().toList(),
            peakRunningCalls = monitor.peakRunningCalls,
            peakPoolConnections = monitor.peakPoolConnections,
            peakActiveConnections = summary.peakActive,
            msAtFullWorkers = summary.msAtFullWidth,
            activeBuckets = summary.activeBuckets,
            meanActive = summary.meanActive,
            bucketMs = config.bucketMs,
        )

        val wire = if (rxBefore != null && rxAfter != null) rxAfter - rxBefore else null
        val networkChanged = monitor.networkChanged || !networkBefore.sameNetworkAs(networkAfter)

        val invalid = LinkedHashSet<InvalidReason>()
        if (all.any { it.outcome != Outcome.OK } || unexpected != null) invalid += InvalidReason.FAILED_REQUESTS
        val reason = cancelReason
        if (reason != null) invalid += reason
        if (request.control.cancelled) invalid += InvalidReason.CANCELLED
        if (stopStatus.get() != 0) invalid += InvalidReason.PROVIDER_STOP
        if (wire != null) {
            val allowed = maxOf(config.contaminationFloorBytes, (counted * config.contaminationShare).toLong())
            if (wire - counted > allowed) invalid += InvalidReason.CONTAMINATED
        }
        if (networkChanged) invalid += InvalidReason.NETWORK_CHANGED
        val noCategories = step.arm.fetch == Fetch.BY_CATEGORY && (scanned?.categories?.isEmpty() ?: true)
        if (counted == 0L || noCategories) invalid += InvalidReason.NO_DATA

        val categoryBytes = LinkedHashMap<String, Long>()
        for (record in all) {
            val id = record.category
            if (record.kind == RequestKind.CATEGORY && id != null) categoryBytes[id] = record.bytes
        }

        val record = RunRecord(
            step = step,
            totalMs = all.maxOfOrNull { it.endMs } ?: nowMs(),
            requests = all,
            bytes = counted,
            evidence = evidence,
            wireBytes = wire,
            categoriesListed = scanned?.categories?.size,
            duplicateCategoryIds = scanned?.duplicateCategoryIds ?: 0,
            categoryBytes = categoryBytes,
            network = networkBefore.note(networkChanged),
            invalid = invalid.toList(),
            stopStatus = stopStatus.get().takeIf { it != 0 },
            fatalStatus = fatalStatus.get().takeIf { it != 0 },
        )
        return RunOutcome(record, scanned?.reply, scanned?.walk)
    }

    private companion object {
        const val NANOS_PER_MS = 1_000_000L
    }
}
