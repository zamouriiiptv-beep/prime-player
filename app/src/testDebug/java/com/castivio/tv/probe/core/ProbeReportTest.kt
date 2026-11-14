package com.castivio.tv.probe.core

import com.castivio.tv.probe.support.Fixtures
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class ProbeReportTest {

    private val megabytes78 = 78L * 1_048_576L

    private fun k1(block: Int, ms: Long): RunRecord = Fixtures.run(ProbeArms.K1, block, ms, replyBytes = megabytes78)

    private fun lineStarting(report: String, prefix: String): String = report.lines().first { it.startsWith(prefix) }

    @Test
    fun the_report_names_every_section() {
        val report = ProbeReport.render(Fixtures.state(listOf(k1(1, 64_200L))))
        for (heading in listOf("CASTIVIO PROBE v1", "RUNS", "ARMS", "PARALLELISM", "COMPLETENESS", "DECISION")) {
            assertTrue(heading, report.contains(heading))
        }
    }

    @Test
    fun a_report_with_nothing_in_it_still_reads() {
        val report = ProbeReport.render(Fixtures.state())
        assertTrue(report.contains("none yet"))
        assertTrue(report.contains("no reconciliation yet"))
        assertTrue(report.contains("drift check: not done yet"))
        assertTrue(report.contains("not computed yet"))
    }

    @Test
    fun a_run_line_says_time_rate_bytes_requests_failures_first_byte_and_validity() {
        val line = lineStarting(ProbeReport.render(Fixtures.state(listOf(k1(1, 64_200L)))), "K1 #1")
        for (part in listOf("64.20 s", "1.21 MB/s", "78.0 MB", "req 2", "fail 0", "first byte avg 0.40 s p95 0.40 s (n=1)")) {
            assertTrue("$part in: $line", line.contains(part))
        }
        assertTrue(line.endsWith("valid"))
    }

    @Test
    fun a_walk_line_says_how_many_connections_received_at_once() {
        val line = lineStarting(
            ProbeReport.render(Fixtures.state(listOf(Fixtures.run(ProbeArms.K3, 1, 41_000L)))),
            "K3 #1",
        )
        assertTrue(line, line.contains("sockets 3/3 peak 3 full 5.0 s"))
    }

    @Test
    fun a_verification_run_is_not_timed_in_the_report() {
        val run = Fixtures.run(ProbeArms.K3, 0, 50_000L, purpose = StepPurpose.VERIFY)
        val line = lineStarting(ProbeReport.render(Fixtures.state(listOf(run))), "K3 verify")
        assertTrue(line, line.contains("not timed"))
        assertFalse(line, line.contains("first byte"))
    }

    @Test
    fun an_invalid_run_says_why_and_how_it_failed() {
        val run = Fixtures.run(
            ProbeArms.K3, 1, 41_000L,
            failures = 1,
            invalid = listOf(InvalidReason.FAILED_REQUESTS, InvalidReason.CONTAMINATED),
        )
        val line = lineStarting(ProbeReport.render(Fixtures.state(listOf(run))), "K3 #1")
        assertTrue(line, line.contains("INVALID(failed_requests,contaminated)"))
        assertTrue(line, line.contains("fail 1 (TIMEOUT x1)"))
    }

    @Test
    fun an_http_failure_is_named_with_its_status() {
        val base = Fixtures.run(ProbeArms.K8, 1, 20_000L, invalid = listOf(InvalidReason.PROVIDER_STOP), stopStatus = 429)
        val run = base.copy(
            requests = base.requests + Fixtures.request(RequestKind.CATEGORY, "2", 0L, Outcome.HTTP, status = 429),
        )
        val line = lineStarting(ProbeReport.render(Fixtures.state(listOf(run))), "K8 #1")
        assertTrue(line, line.contains("fail 1 (HTTP 429 x1)"))
    }

    @Test
    fun a_walk_line_says_how_many_categories_the_panel_listed() {
        val run = Fixtures.run(ProbeArms.K3, 1, 41_000L).copy(categoriesListed = 187)
        val line = lineStarting(ProbeReport.render(Fixtures.state(listOf(run))), "K3 #1")
        assertTrue(line, line.contains("cats 187"))
    }

    @Test
    fun every_way_a_session_can_stop_is_said_in_words() {
        val cases = listOf(
            StopReason.ProviderStatus(429, "K8 #1") to "STOPPED BY THE PROVIDER: HTTP 429 during K8 #1",
            StopReason.Configuration(401, "K1 #1") to "STOPPED: HTTP 401 on an essential request during K1 #1",
            StopReason.ByUser to "STOPPED by the user",
            StopReason.HardBudget(30L * 1_048_576L, 20L * 1_048_576L) to
                "STOPPED: 30.0 MB received is past the limit for 20.0 MB announced",
            StopReason.ConsecutiveInvalid(3) to "STOPPED: 3 invalid runs in a row",
            StopReason.Unexpected("IllegalStateException") to "STOPPED: unexpected IllegalStateException",
        )
        for ((reason, expected) in cases) {
            val state = Fixtures.state(status = SessionStatus.STOPPED).copy(stop = reason)
            val report = ProbeReport.render(state)
            assertTrue("$expected in the report", report.contains(expected))
            assertTrue(report.contains("status STOPPED"))
        }
    }

    @Test
    fun the_report_never_prints_an_address() {
        val report = ProbeReport.render(Fixtures.state(listOf(k1(1, 64_200L))))
        assertFalse(report.contains("://"))
        assertTrue(report.contains("ua Castivio/1.0 (Android TV) · http · wifi · unmetered"))
    }

    @Test
    fun digits_stay_western_under_an_arabic_language() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG"))
            val runs = listOf(k1(1, 64_200L), Fixtures.run(ProbeArms.K3, 1, 41_000L))
            val report = ProbeReport.render(Fixtures.state(runs))
            // Arabic-Indic (U+0660..0669) and Extended Arabic-Indic (U+06F0..06F9) digits.
            assertTrue(report.none { it.code in 0x0660..0x0669 || it.code in 0x06F0..0x06F9 })
            assertTrue(report.contains("64.20 s"))
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun the_arms_section_compares_each_arm_with_k1() {
        val runs = (1..3).map { k1(it, 60_000L) } + (1..3).map { Fixtures.run(ProbeArms.K3, it, 36_000L) }
        val report = ProbeReport.render(Fixtures.state(runs))
        val line = lineStarting(report, "K3 · n=3/3")
        assertTrue(line, line.contains("time vs K1 -40.0%"))
        assertFalse(lineStarting(report, "K1 · n=3/3").contains("time vs K1"))
        assertTrue(report.contains("W1 · not run yet"))
    }

    @Test
    fun the_parallelism_section_says_in_how_many_runs_it_was_shown() {
        val runs = (1..3).map { Fixtures.run(ProbeArms.K3, it, 36_000L) }
        val line = lineStarting(ProbeReport.render(Fixtures.state(runs)), "K3 · shown")
        assertTrue(line, line.contains("shown in 3 of 3 valid runs"))
        assertTrue(line, line.contains("connections with bytes min 3 of 3"))
        assertTrue(line, line.contains("protocol http/1.1"))
    }

    @Test
    fun the_completeness_section_gives_each_cause_its_own_figure() {
        val state = Fixtures.state(reconciliations = mapOf("K3" to Fixtures.orphanResult(ProbeArms.K3)))
        val report = ProbeReport.render(state)
        assertTrue(report.contains("one reply R1 · 2 rows · 2 distinct · 0 repeated rows · 0 without id · 1 categories listed · 1 rows under no listed category"))
        assertTrue(report.contains("K3 walk against R1 · INCOMPLETE"))
        assertTrue(report.contains("no listed category 1 · category request failed 0 · removed meanwhile 0 · unexplained 0"))
    }

    @Test
    fun the_drift_is_reported_when_it_has_been_measured() {
        val state = Fixtures.state().copy(drift = Reconciliation.Drift(100, 102, 2, 0))
        assertTrue(ProbeReport.render(state).contains("drift R2 against R1 · distinct 100 → 102 · added 2 · removed 0"))
    }

    @Test
    fun the_decision_section_prints_every_criterion_and_the_result() {
        val runs = (1..3).map { k1(it, 60_000L) } + (1..3).map { Fixtures.run(ProbeArms.K3, it, 36_000L) }
        val results = mapOf("K3" to Fixtures.completeResult(ProbeArms.K3))
        val decision = DecisionRule.evaluate(runs, results, Thresholds())
        val report = ProbeReport.render(Fixtures.state(runs, reconciliations = results).copy(decision = decision))
        assertTrue(report.contains("[met] median faster: 40.0% faster"))
        assertTrue(report.contains("[met] every repeat faster"))
        assertTrue(report.contains("[met] no errors"))
        assertTrue(report.contains("[met] complete"))
        assertTrue(report.contains("[met] parallelism shown"))
        assertTrue(report.contains("RESULT adopt K3"))
    }

    @Test
    fun a_decision_made_on_the_pilot_is_marked_preliminary() {
        val state = Fixtures.state(status = SessionStatus.PAUSED_PILOT)
            .copy(decision = DecisionRule.evaluate(emptyList(), emptyMap(), Thresholds()))
        val report = ProbeReport.render(state)
        assertTrue(report.contains("PAUSED after the pilot"))
        assertTrue(report.contains("RESULT inconclusive (preliminary: pilot only, one run per arm)"))
    }

    @Test
    fun a_data_alert_is_said_beside_the_figures() {
        val mb = 1_048_576L
        val state = Fixtures.state().copy(
            budget = BudgetSnapshot(100L * mb, 200L * mb, 110L * mb, DataBudget.Level.SOFT),
        )
        val report = ProbeReport.render(state)
        assertTrue(report.contains("data 110.0 MB received of 100.0 MB announced · projected 200.0 MB · ALERT: more than announced"))
    }
}
