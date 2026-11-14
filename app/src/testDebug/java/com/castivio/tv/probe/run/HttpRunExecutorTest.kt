package com.castivio.tv.probe.run

import com.castivio.tv.probe.core.InvalidReason
import com.castivio.tv.probe.core.NetworkSnapshot
import com.castivio.tv.probe.core.Outcome
import com.castivio.tv.probe.core.ParallelProof
import com.castivio.tv.probe.core.ParallelVerdict
import com.castivio.tv.probe.core.ProbeArm
import com.castivio.tv.probe.core.ProbeArms
import com.castivio.tv.probe.core.ProbeConfig
import com.castivio.tv.probe.core.ProbeEnvironment
import com.castivio.tv.probe.core.ProbeReport
import com.castivio.tv.probe.core.Reconciliation
import com.castivio.tv.probe.core.RequestKind
import com.castivio.tv.probe.core.RunControl
import com.castivio.tv.probe.core.RunLimits
import com.castivio.tv.probe.core.RunProgress
import com.castivio.tv.probe.core.RunRequest
import com.castivio.tv.probe.core.StepPurpose
import com.castivio.tv.probe.support.FakeFilm
import com.castivio.tv.probe.support.FakePanel
import com.castivio.tv.probe.support.FixedEnvironment
import com.castivio.tv.probe.support.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The runner against a panel that is really there, on the loopback address: real sockets, real HTTP, the real client.
 *
 * What a server alone can say -- how many requests it was serving at the same moment, how many connections it
 * accepted -- is what the client's own evidence is held against.
 */
class HttpRunExecutorTest {

    /** A test that waits for something that never comes must fail, not hold the build. */
    @get:Rule
    val timeout: Timeout = Timeout.seconds(90)

    private val user = "alice9"
    private val secret = "s3cretPass"

    /** Windows of 100 ms for the bytes of each connection, and the monitor looks every 100 ms. */
    private val config = ProbeConfig(bucketMs = 100L)

    private val categories = listOf("1", "2", "3")
    private val films = listOf(FakeFilm(11, "1"), FakeFilm(12, "1"), FakeFilm(21, "2"), FakeFilm(31, "3"))

    private fun credentials(panel: FakePanel) = ProbeCredentials("http://127.0.0.1:${panel.port}", user, secret)

    private fun executor(
        panel: FakePanel,
        environment: ProbeEnvironment = FixedEnvironment,
        settings: ProbeConfig = config,
    ) = HttpRunExecutor(credentials(panel), environment, settings)

    private fun request(
        arm: ProbeArm,
        purpose: StepPurpose = StepPurpose.TIMED,
        control: RunControl = RunControl(),
        maxBytes: Long = 1L shl 40,
        progress: (RunProgress) -> Unit = {},
    ): RunRequest {
        val block = if (purpose == StepPurpose.TIMED) 1 else 0
        return RunRequest(Fixtures.step(arm, purpose, block), control, RunLimits(maxBytes, 60_000L), progress)
    }

    /** [count] categories of [perCategory] films each, so that a walk has something to carry. */
    private fun library(count: Int, perCategory: Int): Pair<List<String>, List<FakeFilm>> {
        val ids = (1..count).map { it.toString() }
        val all = ids.flatMap { category ->
            (1..perCategory).map { FakeFilm(category.toLong() * 1_000L + it, category) }
        }
        return ids to all
    }

    private fun filmRequests(panel: FakePanel): Int = panel.requests.count { it != "categories" }

    // ------------------------------------------------------------------------------- one reply (K1)

    @Test
    fun a_timed_one_reply_run_counts_every_byte_and_reads_nothing() {
        FakePanel(categories, films).use { panel ->
            // Not a list of films at all: a timed run must not look inside it, so it must not mind.
            panel.replyOverride = "{\"user_info\":{\"auth\":0}}"
            val outcome = executor(panel).execute(request(ProbeArms.K1))
            val record = outcome.record
            assertTrue(record.invalid.toString(), record.valid)
            assertEquals(panel.bodyBytes.get(), record.bytes)
            assertEquals(2, record.requests.size)
            assertEquals(setOf(RequestKind.CATEGORIES, RequestKind.REPLY), record.requests.map { it.kind }.toSet())
            assertEquals(3, record.categoriesListed)
            assertEquals(1, record.evidence.workers)
            assertEquals(listOf("http/1.1"), record.evidence.protocols)
            assertNull(outcome.reply)
            assertNull(outcome.walk)
            // One reply for the whole section, and never a category on its own.
            assertEquals(1, panel.requests.count { it == "reply" })
            assertTrue(panel.requests.none { it.startsWith("category:") })
        }
    }

    @Test
    fun a_run_says_which_step_it_ran() {
        FakePanel(categories, films).use { panel ->
            val record = executor(panel).execute(request(ProbeArms.K1)).record
            assertEquals(Fixtures.step(ProbeArms.K1, StepPurpose.TIMED, 1), record.step)
        }
    }

    @Test
    fun a_one_reply_verification_reads_the_id_and_the_category_of_every_film() {
        FakePanel(categories, films + FakeFilm(99, "9")).use { panel ->
            val outcome = executor(panel).execute(request(ProbeArms.K1, StepPurpose.VERIFY))
            val scan = outcome.reply!!
            assertTrue(outcome.record.invalid.toString(), outcome.record.valid)
            assertEquals(setOf("1", "2", "3"), scan.listed)
            assertEquals(listOf(11L, 12L, 21L, 31L, 99L), scan.entries.idsCopy().sorted())
            assertEquals(0, scan.rowsWithoutId)
            // Each film is filed under the category it names, the one under no listed category included.
            val declared = (0 until scan.entries.size).associate { scan.entries.id(it) to scan.entries.declared(it) }
            assertEquals("2", declared[21L])
            assertEquals("9", declared[99L])
        }
    }

    @Test
    fun a_reply_that_is_not_a_list_fails_a_verification_and_does_not_fail_a_timed_run() {
        FakePanel(categories, films).use { panel ->
            panel.replyOverride = "{\"user_info\":{\"auth\":0}}"
            assertTrue(executor(panel).execute(request(ProbeArms.K1)).record.valid)

            val verify = executor(panel).execute(request(ProbeArms.K1, StepPurpose.VERIFY)).record
            assertFalse(verify.valid)
            assertEquals(Outcome.UNREADABLE, verify.requests.first { it.kind == RequestKind.REPLY }.outcome)
            assertTrue(InvalidReason.FAILED_REQUESTS in verify.invalid)
        }
    }

    // ---------------------------------------------------------------------- a walk by category (K3...)

    @Test
    fun a_timed_walk_asks_every_listed_category_once_and_counts_every_byte() {
        FakePanel(categories, films).use { panel ->
            val outcome = executor(panel).execute(request(ProbeArms.K3))
            val record = outcome.record
            assertTrue(record.invalid.toString(), record.valid)
            val asked = record.requests.filter { it.kind == RequestKind.CATEGORY }.mapNotNull { it.category }
            assertEquals(categories, asked.sorted())
            assertEquals(categories.toSet(), record.categoryBytes.keys)
            assertEquals(panel.bodyBytes.get(), record.bytes)
            assertEquals(3, record.categoriesListed)
            assertNull(outcome.walk)
            // The walk never asks for the whole section, and asks for the list once.
            assertEquals(0, panel.requests.count { it == "reply" })
            assertEquals(1, panel.requests.count { it == "categories" })
        }
    }

    @Test
    fun every_arm_holds_as_many_connections_as_it_has_workers_receiving_at_the_same_moment() {
        // Every figure is checked and every miss is told at the end, so that one run of the suite says all that is wrong
        // with the evidence and not the first thing that is.
        val problems = ArrayList<String>()

        fun want(ok: Boolean, otherwise: String) {
            if (!ok) problems.add(otherwise)
        }

        for (arm in ProbeArms.candidates) {
            val (ids, all) = library(count = arm.workers * 2, perCategory = 20)
            FakePanel(ids, all).use { panel ->
                // The first requests wait at the server until `workers` of them are there at once.
                panel.rendezvous = CountDownLatch(arm.workers)
                panel.chunks = 8
                panel.chunkDelayMs = 60L
                val record = executor(panel, settings = ProbeConfig(bucketMs = 200L)).execute(request(arm)).record
                val e = record.evidence
                val name = arm.id
                val verdict = ParallelProof.verdict(e, minFullWidthMs = 200L)

                want(record.valid, "$name invalid: ${record.invalid}")
                // The server's own count, which no client figure can stand in for.
                want(panel.peakActive.get() == arm.workers, "$name at the server ${panel.peakActive.get()}")
                want(panel.accepted.get() >= arm.workers, "$name accepted ${panel.accepted.get()}")
                // The client's evidence, four ways that do not depend on one another: the requests open (the run's own
                // count, to the last byte of the body), the pool, the sockets opened, and the bytes of each connection.
                want(e.peakRunningCalls == arm.workers, "$name requests open ${e.peakRunningCalls}")
                want(e.peakPoolConnections >= arm.workers, "$name pool ${e.peakPoolConnections}")
                want(e.newConnections >= arm.workers, "$name sockets opened ${e.newConnections}")
                want(e.connectionsWithBytes >= arm.workers, "$name with bytes ${e.connectionsWithBytes}")
                want(e.peakActiveConnections >= arm.workers, "$name active at once ${e.peakActiveConnections}")
                want(e.protocols == listOf("http/1.1"), "$name protocols ${e.protocols}")
                want(verdict == ParallelVerdict.PROVEN, "$name verdict $verdict, full width ${e.msAtFullWorkers} ms")
            }
        }
        assertTrue(problems.joinToString("; "), problems.isEmpty())
    }

    @Test
    fun one_worker_never_has_two_requests_at_once() {
        val (ids, all) = library(count = 4, perCategory = 10)
        FakePanel(ids, all).use { panel ->
            panel.chunks = 4
            panel.chunkDelayMs = 20L
            val record = executor(panel).execute(request(ProbeArms.W1)).record
            assertTrue(record.invalid.toString(), record.valid)
            assertEquals(1, panel.peakActive.get())
            assertEquals(1, record.evidence.workers)
            assertEquals(ParallelVerdict.NOT_APPLICABLE, ParallelProof.verdict(record.evidence, 100L))
            assertEquals(4, record.requests.count { it.kind == RequestKind.CATEGORY })
        }
    }

    // -------------------------------------------------------------------------- nothing lost, nothing doubled

    @Test
    fun a_verification_walk_brings_every_film_exactly_once_whatever_the_number_of_workers() {
        val (ids, all) = library(count = 16, perCategory = 25)
        FakePanel(ids, all).use { panel ->
            val reply = executor(panel).execute(request(ProbeArms.K1, StepPurpose.VERIFY)).reply!!
            for (arm in listOf(ProbeArms.W1, ProbeArms.K3, ProbeArms.K4, ProbeArms.K8)) {
                val outcome = executor(panel).execute(request(arm, StepPurpose.VERIFY))
                assertTrue("${arm.id}: ${outcome.record.invalid}", outcome.record.valid)
                val draft = Reconciliation.draft(arm, reply, outcome.walk!!)
                val result = Reconciliation.finish(draft, null)
                assertEquals("${arm.id} films in the reply", 400, draft.replyDistinct)
                assertEquals("${arm.id} rows of the walk", 400, draft.walkRows)
                assertEquals("${arm.id} distinct in the walk", 400, draft.walkDistinct)
                assertEquals("${arm.id} repeated inside a category", 0, draft.repeatsInsideCategory)
                assertEquals("${arm.id} under several categories", 0, draft.idsInSeveralCategories)
                assertEquals("${arm.id} missing", 0, result.missing)
                assertEquals("${arm.id} extra", 0, result.extra)
                assertTrue("${arm.id} arithmetic", result.balanced)
                assertEquals(Reconciliation.Completeness.COMPLETE, result.completeness)
            }
        }
    }

    @Test
    fun a_film_under_no_listed_category_is_found_to_be_out_of_the_walks_reach() {
        FakePanel(categories, films + FakeFilm(99, "9")).use { panel ->
            val reply = executor(panel).execute(request(ProbeArms.K1, StepPurpose.VERIFY)).reply!!
            val walk = executor(panel).execute(request(ProbeArms.K3, StepPurpose.VERIFY)).walk!!
            val draft = Reconciliation.draft(ProbeArms.K3, reply, walk)
            val result = Reconciliation.finish(draft, null)
            assertEquals(5, draft.replyDistinct)
            assertEquals(4, draft.walkDistinct)
            assertEquals(1, draft.missingOrphans)
            assertEquals(0, draft.missingFailed)
            assertEquals(0, result.missingUnexplained)
            assertTrue(result.balanced)
            assertEquals(Reconciliation.Completeness.INCOMPLETE, result.completeness)
        }
    }

    @Test
    fun a_film_listed_under_two_categories_is_seen_in_both_and_counted_once_in_the_reply() {
        val all = films + FakeFilm(41, "1", alsoIn = listOf("2"))
        FakePanel(categories, all).use { panel ->
            val reply = executor(panel).execute(request(ProbeArms.K1, StepPurpose.VERIFY)).reply!!
            val walk = executor(panel).execute(request(ProbeArms.K3, StepPurpose.VERIFY)).walk!!
            val draft = Reconciliation.draft(ProbeArms.K3, reply, walk)
            val result = Reconciliation.finish(draft, null)
            assertEquals(5, draft.replyRows)
            assertEquals(6, draft.walkRows)
            assertEquals(5, draft.walkDistinct)
            assertEquals(1, draft.idsInSeveralCategories)
            assertEquals(1, draft.extraRowsFromSeveralCategories)
            assertEquals(1, draft.declaredMismatchRows)
            assertTrue(result.balanced)
            // A film in two categories is a finding, not a loss.
            assertEquals(Reconciliation.Completeness.COMPLETE, result.completeness)
        }
    }

    @Test
    fun a_category_that_fails_is_named_and_what_it_held_is_told_apart_from_a_loss() {
        FakePanel(categories, films).use { panel ->
            panel.statusFor = { key -> if (key == "category:2") 404 else 200 }
            val reply = executor(panel).execute(request(ProbeArms.K1, StepPurpose.VERIFY)).reply!!
            val outcome = executor(panel).execute(request(ProbeArms.K3, StepPurpose.VERIFY))
            val walk = outcome.walk!!
            assertEquals(setOf("2"), walk.failed)
            // A 404 on one category is a failed request, and not an order to stop.
            assertFalse(outcome.record.valid)
            assertNull(outcome.record.stopStatus)
            assertNull(outcome.record.fatalStatus)
            val draft = Reconciliation.draft(ProbeArms.K3, reply, walk)
            assertEquals(1, draft.missingFailed)
            assertEquals(0, draft.missingOrphans)
            assertEquals(Reconciliation.Completeness.INCOMPLETE, Reconciliation.finish(draft, null).completeness)
        }
    }

    // ---------------------------------------------------------------------------------- what stops a run

    @Test
    fun a_429_ends_the_run_and_is_named_as_the_provider_stopping_it() {
        val (ids, all) = library(count = 12, perCategory = 5)
        FakePanel(ids, all).use { panel ->
            panel.firstByteDelayMs = 80L
            panel.statusFor = { key -> if (key == "category:2") 429 else 200 }
            val record = executor(panel).execute(request(ProbeArms.K3)).record
            assertEquals(429, record.stopStatus)
            assertNull(record.fatalStatus)
            assertFalse(record.valid)
            assertTrue(InvalidReason.PROVIDER_STOP in record.invalid)
            assertTrue(InvalidReason.FAILED_REQUESTS in record.invalid)
            val refused = record.requests.first { it.category == "2" }
            assertEquals(Outcome.HTTP, refused.outcome)
            assertEquals(429, refused.status)
            // It did not go on asking: the rest of the walk was never requested.
            assertTrue("${filmRequests(panel)} category requests", filmRequests(panel) < 12)
        }
    }

    @Test
    fun a_5xx_on_a_category_is_a_provider_stop_too() {
        FakePanel(categories, films).use { panel ->
            panel.statusFor = { key -> if (key == "category:1") 503 else 200 }
            val record = executor(panel).execute(request(ProbeArms.K3)).record
            assertEquals(503, record.stopStatus)
            assertTrue(InvalidReason.PROVIDER_STOP in record.invalid)
        }
    }

    @Test
    fun a_500_on_the_one_reply_is_a_provider_stop_and_not_a_configuration_problem() {
        FakePanel(categories, films).use { panel ->
            panel.statusFor = { key -> if (key == "reply") 500 else 200 }
            val record = executor(panel).execute(request(ProbeArms.K1)).record
            assertEquals(500, record.stopStatus)
            assertNull(record.fatalStatus)
        }
    }

    @Test
    fun a_403_on_the_list_of_categories_is_a_provider_stop_and_the_walk_does_not_start() {
        FakePanel(categories, films).use { panel ->
            panel.statusFor = { key -> if (key == "categories") 403 else 200 }
            val record = executor(panel).execute(request(ProbeArms.K3)).record
            assertEquals(403, record.stopStatus)
            assertEquals(listOf("categories"), panel.requests.toList())
            assertTrue(InvalidReason.PROVIDER_STOP in record.invalid)
        }
    }

    @Test
    fun a_404_on_the_list_of_categories_is_a_configuration_problem_and_the_walk_does_not_start() {
        FakePanel(categories, films).use { panel ->
            panel.statusFor = { key -> if (key == "categories") 404 else 200 }
            val record = executor(panel).execute(request(ProbeArms.K3)).record
            assertEquals(404, record.fatalStatus)
            assertNull(record.stopStatus)
            assertEquals(listOf("categories"), panel.requests.toList())
            assertEquals(0, record.categoriesListed)
            assertTrue(InvalidReason.NO_DATA in record.invalid)
            assertTrue(InvalidReason.FAILED_REQUESTS in record.invalid)
        }
    }

    @Test
    fun a_404_on_the_one_reply_is_a_configuration_problem() {
        FakePanel(categories, films).use { panel ->
            panel.statusFor = { key -> if (key == "reply") 404 else 200 }
            val record = executor(panel).execute(request(ProbeArms.K1)).record
            assertEquals(404, record.fatalStatus)
            assertNull(record.stopStatus)
            assertFalse(record.valid)
        }
    }

    @Test
    fun a_run_that_passes_its_data_limit_is_cut_and_says_so() {
        FakePanel(categories, films).use { panel ->
            val record = executor(panel).execute(request(ProbeArms.K1, maxBytes = 50L)).record
            assertTrue(InvalidReason.OVERSIZE in record.invalid)
            assertFalse(record.valid)
            assertTrue(record.requests.any { it.outcome == Outcome.OVERSIZE })
        }
    }

    @Test
    fun a_stop_from_the_session_ends_the_run_promptly() {
        FakePanel(categories, films).use { panel ->
            // Each category takes four seconds to arrive.
            panel.chunks = 40
            panel.chunkDelayMs = 100L
            val control = RunControl()
            val pool = Executors.newSingleThreadExecutor()
            try {
                val running = pool.submit(Callable { executor(panel).execute(request(ProbeArms.K3, control = control)) })
                Thread.sleep(500L)
                val began = System.nanoTime()
                control.cancel()
                val record = running.get(10, TimeUnit.SECONDS).record
                val tookMs = (System.nanoTime() - began) / 1_000_000L
                assertTrue("took $tookMs ms to stop", tookMs < 3_000L)
                assertTrue(InvalidReason.CANCELLED in record.invalid)
                assertFalse(record.valid)
            } finally {
                pool.shutdownNow()
            }
        }
    }

    @Test
    fun a_run_stopped_before_it_starts_asks_for_nothing() {
        FakePanel(categories, films).use { panel ->
            val control = RunControl()
            control.cancel()
            val record = executor(panel).execute(request(ProbeArms.K1, control = control)).record
            assertTrue(panel.requests.isEmpty())
            assertTrue(InvalidReason.CANCELLED in record.invalid)
            assertTrue(record.requests.all { it.outcome == Outcome.CANCELLED })
        }
    }

    @Test
    fun a_reply_cut_short_is_not_a_success() {
        FakePanel(categories, films).use { panel ->
            panel.truncateReply = true
            val record = executor(panel).execute(request(ProbeArms.K1)).record
            val reply = record.requests.first { it.kind == RequestKind.REPLY }
            assertTrue("${reply.outcome}", reply.outcome in setOf(Outcome.PROTOCOL, Outcome.TRUNCATED, Outcome.RESET))
            assertTrue(InvalidReason.FAILED_REQUESTS in record.invalid)
            assertFalse(record.valid)
        }
    }

    // ------------------------------------------------------------------------------------- what it measures

    @Test
    fun the_first_byte_time_is_what_the_panel_took_to_answer() {
        FakePanel(categories, films).use { panel ->
            panel.firstByteDelayMs = 300L
            val record = executor(panel).execute(request(ProbeArms.K1)).record
            val firstByte = record.requests.first { it.kind == RequestKind.REPLY }.firstByteMs
            assertNotNull(firstByte)
            assertTrue("first byte $firstByte ms", firstByte!! in 250L..5_000L)
        }
    }

    @Test
    fun the_screen_is_told_where_the_run_is_while_it_goes_on() {
        FakePanel(categories, films).use { panel ->
            panel.chunks = 8
            panel.chunkDelayMs = 100L
            val seen = CopyOnWriteArrayList<RunProgress>()
            executor(panel).execute(request(ProbeArms.K1, progress = { seen += it }))
            assertTrue("only ${seen.size} reports", seen.size >= 3)
            assertTrue(seen.all { it.label == "K1 #1" })
            val bytes = seen.map { it.bytes }
            assertEquals(bytes.sorted(), bytes)
            assertTrue(bytes.last() > 0L)
        }
    }

    @Test
    fun other_traffic_over_the_same_time_makes_a_run_contaminated() {
        FakePanel(categories, films).use { panel ->
            val record = executor(panel, MeterEnvironment(extra = 50L * 1_048_576L)).execute(request(ProbeArms.K1)).record
            assertNotNull(record.wireBytes)
            assertTrue(InvalidReason.CONTAMINATED in record.invalid)
            assertFalse(record.valid)
        }
    }

    @Test
    fun a_platform_count_within_the_allowance_is_clean() {
        FakePanel(categories, films).use { panel ->
            // Headers and the like: half a megabyte more than the bodies is inside the megabyte allowed.
            val record = executor(panel, MeterEnvironment(extra = 500_000L)).execute(request(ProbeArms.K1)).record
            assertNotNull(record.wireBytes)
            assertFalse(InvalidReason.CONTAMINATED in record.invalid)
            assertTrue(record.invalid.toString(), record.valid)
        }
    }

    @Test
    fun where_the_platform_does_not_count_bytes_nothing_is_said_about_them() {
        FakePanel(categories, films).use { panel ->
            val record = executor(panel).execute(request(ProbeArms.K1)).record
            assertNull(record.wireBytes)
            assertFalse(InvalidReason.CONTAMINATED in record.invalid)
        }
    }

    @Test
    fun a_run_that_ends_on_another_network_does_not_count() {
        FakePanel(categories, films).use { panel ->
            val record = executor(panel, MovingEnvironment()).execute(request(ProbeArms.K1)).record
            assertTrue(InvalidReason.NETWORK_CHANGED in record.invalid)
            assertTrue(record.network.changed)
            assertFalse(record.valid)
        }
    }

    // ------------------------------------------------------------------------------------ what it leaves

    @Test
    fun the_monitor_thread_ends_with_the_run() {
        FakePanel(categories, films).use { panel ->
            executor(panel).execute(request(ProbeArms.K1))
            val deadline = System.nanoTime() + 2_000_000_000L
            while (monitorAlive() && System.nanoTime() < deadline) Thread.sleep(20L)
            assertFalse("the monitor thread is still alive", monitorAlive())
        }
    }

    private fun monitorAlive(): Boolean =
        Thread.getAllStackTraces().keys.any { it.name == "probe-monitor" && it.isAlive }

    @Test
    fun nothing_that_names_the_account_reaches_a_report() {
        FakePanel(categories, films).use { panel ->
            val record = executor(panel).execute(request(ProbeArms.K3)).record
            val text = ProbeReport.render(Fixtures.state(listOf(record))) + "\n" + record
            val named = credentials(panel).secrets() + listOf(":${panel.port}", "127.0.0.1", user, secret)
            for (secretText in named) assertFalse("'$secretText' is in the report", text.contains(secretText))
        }
    }
}

/** Answers for the platform's byte counter: [extra] more bytes at the second reading than at the first. */
private class MeterEnvironment(private val extra: Long) : ProbeEnvironment {
    private val readings = AtomicInteger()

    override fun uidRxBytes(): Long? = if (readings.getAndIncrement() == 0) 1_000L else 1_000L + extra

    override fun network(): NetworkSnapshot = FixedEnvironment.network()
}

/** The first look at the network says Wi-Fi, and every later one says mobile data. */
private class MovingEnvironment : ProbeEnvironment {
    private val looks = AtomicInteger()

    override fun uidRxBytes(): Long? = null

    override fun network(): NetworkSnapshot =
        if (looks.getAndIncrement() == 0) {
            NetworkSnapshot("wifi-1", "wifi", false)
        } else {
            NetworkSnapshot("mobile-1", "cellular", true)
        }
}
