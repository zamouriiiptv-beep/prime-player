package com.castivio.tv.probe.core

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.concurrent.thread

class ConcurrencyProfileTest {

    @Test
    fun the_timeline_counts_bytes_per_connection_and_per_window() {
        val flow = FlowTimeline(250L)
        flow.add(1, 0L, 100)
        flow.add(1, 100L, 50)
        flow.add(1, 300L, 10)
        flow.add(2, 260L, 5)
        flow.add(-1, 0L, 5)
        flow.add(3, 0L, 0)
        val snapshot = flow.snapshot()
        assertEquals(setOf(1, 2), snapshot.keys)
        assertEquals(150L, snapshot.getValue(1).getValue(0L))
        assertEquals(10L, snapshot.getValue(1).getValue(1L))
        assertEquals(5L, snapshot.getValue(2).getValue(1L))
    }

    @Test
    fun a_time_before_the_start_belongs_to_the_first_window() {
        val flow = FlowTimeline(250L)
        flow.add(1, -5L, 10)
        assertEquals(10L, flow.snapshot().getValue(1).getValue(0L))
    }

    @Test
    fun the_timeline_is_safe_to_fill_from_many_threads() {
        val flow = FlowTimeline(250L)
        val threads = (1..8).map { connection ->
            thread {
                repeat(1_000) { flow.add(connection, it.toLong(), 1) }
            }
        }
        for (worker in threads) worker.join()
        val snapshot = flow.snapshot()
        assertEquals(8, snapshot.size)
        for (connection in 1..8) assertEquals(1_000L, snapshot.getValue(connection).values.sum())
    }

    @Test
    fun three_connections_receiving_in_the_same_windows_are_full_width() {
        val flow = HashMap<Int, Map<Long, Long>>()
        flow[1] = mapOf(0L to 10L, 1L to 10L, 2L to 10L, 3L to 10L)
        flow[2] = mapOf(0L to 10L, 1L to 10L, 2L to 10L, 3L to 10L)
        flow[3] = mapOf(1L to 10L, 2L to 10L, 3L to 10L)
        val summary = ConcurrencyProfile.analyse(flow, workers = 3, bucketMs = 250L)
        assertEquals(3, summary.peakActive)
        assertEquals(750L, summary.msAtFullWidth)
        assertEquals(4, summary.activeBuckets)
        assertEquals(3, summary.connectionsWithBytes)
        assertEquals(2.75, summary.meanActive, 1e-9)
    }

    @Test
    fun connections_taking_turns_are_never_parallel() {
        val flow = HashMap<Int, Map<Long, Long>>()
        flow[1] = mapOf(0L to 10L, 1L to 10L)
        flow[2] = mapOf(2L to 10L, 3L to 10L)
        flow[3] = mapOf(4L to 10L, 5L to 10L)
        val summary = ConcurrencyProfile.analyse(flow, workers = 3, bucketMs = 250L)
        assertEquals(1, summary.peakActive)
        assertEquals(0L, summary.msAtFullWidth)
        assertEquals(3, summary.connectionsWithBytes)
    }

    @Test
    fun a_window_with_no_bytes_does_not_count_as_active() {
        val flow = HashMap<Int, Map<Long, Long>>()
        flow[1] = mapOf(0L to 0L, 1L to 5L)
        val summary = ConcurrencyProfile.analyse(flow, workers = 1, bucketMs = 250L)
        assertEquals(1, summary.connectionsWithBytes)
        assertEquals(1, summary.peakActive)
        assertEquals(1, summary.activeBuckets)
    }

    @Test
    fun nothing_received_is_no_parallelism() {
        val summary = ConcurrencyProfile.analyse(emptyMap(), workers = 3, bucketMs = 250L)
        assertEquals(0, summary.peakActive)
        assertEquals(0L, summary.msAtFullWidth)
        assertEquals(0, summary.activeBuckets)
        assertEquals(0, summary.connectionsWithBytes)
        assertEquals(0.0, summary.meanActive, 0.0)
    }

    // -------------------------------------------------------------------------------- the verdict

    private fun evidence(
        workers: Int = 3,
        withBytes: Int = 3,
        peak: Int = 3,
        fullMs: Long = 2_000L,
        protocols: List<String> = listOf("http/1.1"),
    ): ParallelEvidence = ParallelEvidence(
        workers = workers,
        connectionsWithBytes = withBytes,
        peakActiveConnections = peak,
        msAtFullWorkers = fullMs,
        protocols = protocols,
    )

    private fun verdict(evidence: ParallelEvidence) = ParallelProof.verdict(evidence, 1_000L)

    @Test
    fun one_worker_has_nothing_to_show() {
        assertEquals(ParallelVerdict.NOT_APPLICABLE, verdict(evidence(workers = 1, withBytes = 1, peak = 1)))
    }

    @Test
    fun every_worker_receiving_at_once_for_long_enough_is_shown() {
        assertEquals(ParallelVerdict.PROVEN, verdict(evidence()))
    }

    @Test
    fun the_exact_minimum_time_is_enough() {
        assertEquals(ParallelVerdict.PROVEN, verdict(evidence(fullMs = 1_000L)))
    }

    @Test
    fun too_short_at_full_width_is_not_shown() {
        assertEquals(ParallelVerdict.NOT_PROVEN, verdict(evidence(fullMs = 999L)))
    }

    @Test
    fun fewer_connections_with_bytes_than_workers_is_not_shown() {
        assertEquals(ParallelVerdict.NOT_PROVEN, verdict(evidence(withBytes = 2)))
    }

    @Test
    fun never_all_receiving_together_is_not_shown() {
        assertEquals(ParallelVerdict.NOT_PROVEN, verdict(evidence(peak = 2)))
    }

    @Test
    fun requests_sharing_one_http2_connection_are_not_shown_however_many_were_in_flight() {
        val shared = evidence(withBytes = 1, peak = 1, protocols = listOf("h2"))
        assertEquals(ParallelVerdict.NOT_PROVEN, verdict(shared))
        val numbersAlone = evidence(protocols = listOf("h2"))
        assertEquals(ParallelVerdict.NOT_PROVEN, verdict(numbersAlone))
    }

    @Test
    fun one_connection_that_is_not_http11_spoils_it() {
        assertEquals(ParallelVerdict.NOT_PROVEN, verdict(evidence(protocols = listOf("h2", "http/1.1"))))
    }

    @Test
    fun without_protocol_information_the_numbers_decide() {
        assertEquals(ParallelVerdict.PROVEN, verdict(evidence(protocols = emptyList())))
    }
}
