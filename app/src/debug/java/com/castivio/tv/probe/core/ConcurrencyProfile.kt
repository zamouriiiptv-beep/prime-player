package com.castivio.tv.probe.core

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Bytes received, by connection and by time window.
 *
 * It answers one question no port number can: **were several connections receiving at the same moment?** A
 * connection is counted in a window when it received at least a byte in it, so a connection that is open and idle,
 * or opened and never used, adds nothing. Filled from the threads that read, so it is safe to add to from all
 * of them.
 */
class FlowTimeline(val bucketMs: Long) {

    init {
        require(bucketMs > 0L) { "bucketMs must be positive" }
    }

    private val perConnection = ConcurrentHashMap<Int, ConcurrentHashMap<Long, AtomicLong>>()

    fun add(connection: Int, atMs: Long, bytes: Int) {
        if (bytes <= 0 || connection < 0) return
        val buckets = perConnection[connection] ?: run {
            val fresh = ConcurrentHashMap<Long, AtomicLong>()
            perConnection.putIfAbsent(connection, fresh) ?: fresh
        }
        val key = if (atMs < 0L) 0L else atMs / bucketMs
        val cell = buckets[key] ?: run {
            val fresh = AtomicLong()
            buckets.putIfAbsent(key, fresh) ?: fresh
        }
        cell.addAndGet(bytes.toLong())
    }

    /** connection -> window -> bytes. */
    fun snapshot(): Map<Int, Map<Long, Long>> {
        val copy = HashMap<Int, Map<Long, Long>>()
        for ((connection, buckets) in perConnection) {
            val inner = HashMap<Long, Long>()
            for ((bucket, cell) in buckets) inner[bucket] = cell.get()
            copy[connection] = inner
        }
        return copy
    }
}

object ConcurrencyProfile {

    class Summary(
        /** The most connections that received bytes in one window. */
        val peakActive: Int,
        /** How long, in whole windows, [workers] connections or more were receiving together. */
        val msAtFullWidth: Long,
        /** Windows in which anything was received. */
        val activeBuckets: Int,
        /** Connections receiving, averaged over the windows in which anything was. */
        val meanActive: Double,
        /** Connections that received at least a byte at any time. */
        val connectionsWithBytes: Int,
    )

    fun analyse(flow: Map<Int, Map<Long, Long>>, workers: Int, bucketMs: Long): Summary {
        val perBucket = HashMap<Long, Int>()
        var connections = 0
        for ((_, buckets) in flow) {
            var carried = false
            for ((bucket, bytes) in buckets) {
                if (bytes <= 0L) continue
                carried = true
                perBucket[bucket] = (perBucket[bucket] ?: 0) + 1
            }
            if (carried) connections++
        }
        var peak = 0
        var full = 0
        var sum = 0L
        for (count in perBucket.values) {
            if (count > peak) peak = count
            if (count >= workers) full++
            sum += count
        }
        val mean = if (perBucket.isEmpty()) 0.0 else sum.toDouble() / perBucket.size
        return Summary(peak, full * bucketMs, perBucket.size, mean, connections)
    }
}

enum class ParallelVerdict {
    /** One worker: there is nothing to show. */
    NOT_APPLICABLE,

    /** Every worker's connection received bytes at once, for long enough, over HTTP/1.1. */
    PROVEN,

    NOT_PROVEN,
}

/**
 * The rule that turns [ParallelEvidence] into a verdict.
 *
 * **Parallelism is shown by bytes, not by sockets.** It is [ParallelVerdict.PROVEN] only when at least `workers`
 * different connections each received bytes, in the same window, and that went on for at least the minimum time.
 * Over HTTP/2 many requests share one connection, so the connections that carried bytes are fewer than the
 * workers and the verdict is [ParallelVerdict.NOT_PROVEN] however many calls were in flight.
 */
object ParallelProof {

    fun verdict(evidence: ParallelEvidence, minFullWidthMs: Long): ParallelVerdict = when {
        evidence.workers <= 1 -> ParallelVerdict.NOT_APPLICABLE
        evidence.protocols.any { it != HTTP_1_1 } -> ParallelVerdict.NOT_PROVEN
        evidence.connectionsWithBytes >= evidence.workers &&
            evidence.peakActiveConnections >= evidence.workers &&
            evidence.msAtFullWorkers >= minFullWidthMs -> ParallelVerdict.PROVEN
        else -> ParallelVerdict.NOT_PROVEN
    }

    private const val HTTP_1_1 = "http/1.1"
}
