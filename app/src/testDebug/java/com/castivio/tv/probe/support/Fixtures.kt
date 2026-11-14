package com.castivio.tv.probe.support

import com.castivio.tv.probe.core.BudgetSnapshot
import com.castivio.tv.probe.core.DataBudget
import com.castivio.tv.probe.core.Fetch
import com.castivio.tv.probe.core.FilmEntries
import com.castivio.tv.probe.core.InvalidReason
import com.castivio.tv.probe.core.NetworkNote
import com.castivio.tv.probe.core.NetworkSnapshot
import com.castivio.tv.probe.core.OneReplyScan
import com.castivio.tv.probe.core.Outcome
import com.castivio.tv.probe.core.ParallelEvidence
import com.castivio.tv.probe.core.PlanStep
import com.castivio.tv.probe.core.ProbeArm
import com.castivio.tv.probe.core.ProbeConfig
import com.castivio.tv.probe.core.ProbeEnvironment
import com.castivio.tv.probe.core.ProbePlan
import com.castivio.tv.probe.core.Reconciliation
import com.castivio.tv.probe.core.RequestKind
import com.castivio.tv.probe.core.RequestRecord
import com.castivio.tv.probe.core.RunRecord
import com.castivio.tv.probe.core.SessionHeader
import com.castivio.tv.probe.core.SessionState
import com.castivio.tv.probe.core.SessionStatus
import com.castivio.tv.probe.core.StepPurpose
import com.castivio.tv.probe.core.WalkScan

/** A device that never changes and counts nothing. */
object FixedEnvironment : ProbeEnvironment {
    override fun uidRxBytes(): Long? = null

    override fun network(): NetworkSnapshot = NetworkSnapshot("test-network", "wifi", false)
}

/** Builders for the records the pure logic is given, with every figure a test does not care about filled in. */
object Fixtures {

    fun step(
        arm: ProbeArm,
        purpose: StepPurpose = StepPurpose.TIMED,
        block: Int = 1,
        index: Int = 0,
        pauseAfter: Boolean = false,
    ): PlanStep = PlanStep(index, arm, purpose, block, pauseAfter)

    /** The evidence of a walk that really ran [arm]'s workers at once, over HTTP/1.1. */
    fun evidence(arm: ProbeArm, shown: Boolean = true): ParallelEvidence = if (shown) {
        ParallelEvidence(
            workers = arm.workers,
            connectionsUsed = arm.workers,
            connectionsWithBytes = arm.workers,
            newConnections = arm.workers,
            remoteEndpoints = 1,
            protocols = listOf("http/1.1"),
            peakRunningCalls = arm.workers,
            peakPoolConnections = arm.workers,
            peakActiveConnections = arm.workers,
            msAtFullWorkers = 5_000L,
            activeBuckets = 40,
            meanActive = arm.workers.toDouble(),
        )
    } else {
        ParallelEvidence(
            workers = arm.workers,
            connectionsUsed = 1,
            connectionsWithBytes = 1,
            newConnections = 1,
            remoteEndpoints = 1,
            protocols = listOf("http/1.1"),
            peakRunningCalls = 1,
            peakPoolConnections = 1,
            peakActiveConnections = 1,
            msAtFullWorkers = 0L,
            activeBuckets = 40,
            meanActive = 1.0,
        )
    }

    fun request(
        kind: RequestKind,
        category: String?,
        bytes: Long,
        outcome: Outcome = Outcome.OK,
        status: Int? = null,
        firstByteMs: Long? = 400L,
    ): RequestRecord = RequestRecord(
        kind = kind,
        category = category,
        startMs = 0L,
        endMs = 1_000L,
        firstByteMs = firstByteMs,
        bytes = bytes,
        outcome = outcome,
        status = status,
        connection = 1,
        protocol = "http/1.1",
    )

    fun run(
        arm: ProbeArm,
        block: Int,
        totalMs: Long,
        purpose: StepPurpose = StepPurpose.TIMED,
        failures: Int = 0,
        invalid: List<InvalidReason> = emptyList(),
        evidence: ParallelEvidence = evidence(arm),
        replyBytes: Long = 1_000L,
        stopStatus: Int? = null,
        fatalStatus: Int? = null,
        planStep: PlanStep = step(arm, purpose, block),
    ): RunRecord {
        val requests = ArrayList<RequestRecord>()
        requests += request(RequestKind.CATEGORIES, null, 100L)
        requests += if (arm.fetch == Fetch.ONE_REPLY) {
            request(RequestKind.REPLY, null, replyBytes)
        } else {
            request(RequestKind.CATEGORY, "1", replyBytes)
        }
        for (index in 0 until failures) {
            requests += request(RequestKind.CATEGORY, "x$index", 0L, Outcome.TIMEOUT)
        }
        return RunRecord(
            step = planStep,
            totalMs = totalMs,
            requests = requests,
            bytes = 100L + replyBytes,
            evidence = evidence,
            invalid = invalid,
            stopStatus = stopStatus,
            fatalStatus = fatalStatus,
        )
    }

    fun header(): SessionHeader = SessionHeader("Castivio/1.0 (Android TV)", "http", NetworkNote("wifi", false))

    fun budget(received: Long = 0L): BudgetSnapshot = BudgetSnapshot(1_000L, 1_000L, received, DataBudget.Level.WITHIN)

    fun state(
        runs: List<RunRecord> = emptyList(),
        status: SessionStatus = SessionStatus.RUNNING,
        reconciliations: Map<String, Reconciliation.Result> = emptyMap(),
        plan: ProbePlan = ProbePlan.standard(3),
        config: ProbeConfig = ProbeConfig(),
    ): SessionState = SessionState(
        status = status,
        header = header(),
        plan = plan,
        config = config,
        budget = budget(),
        runs = runs,
        reconciliations = reconciliations,
    )

    // ------------------------------------------------------------------------------- scans

    /** The one reply: each pair is a film's id and the category it names. */
    fun reply(listed: Set<String>, films: List<Pair<Long, String?>>): OneReplyScan {
        val entries = FilmEntries()
        for ((id, category) in films) entries.add(id, category, category)
        return OneReplyScan(entries, listed, 0)
    }

    /** A walk: each triple is a film's id, the category it was asked under and the category it names (the same when null). */
    fun walk(
        listed: List<String>,
        films: List<Triple<Long, String, String?>>,
        failed: Set<String> = emptySet(),
    ): WalkScan {
        val entries = FilmEntries()
        for ((id, asked, named) in films) entries.add(id, asked, named ?: asked)
        return WalkScan(entries, listed, failed, 0)
    }

    /** A reconciliation with nothing missing and nothing extra: three films in two categories, found by the walk. */
    fun completeResult(arm: ProbeArm): Reconciliation.Result {
        val reply = reply(setOf("a", "b"), listOf(1L to "a", 2L to "a", 3L to "b"))
        val walk = walk(listOf("a", "b"), listOf(Triple(1L, "a", null), Triple(2L, "a", null), Triple(3L, "b", null)))
        return Reconciliation.finish(Reconciliation.draft(arm, reply, walk), null)
    }

    /** A reconciliation in which the walk cannot reach a film the reply holds under no listed category. */
    fun orphanResult(arm: ProbeArm): Reconciliation.Result {
        val reply = reply(setOf("a"), listOf(1L to "a", 2L to "zzz"))
        val walk = walk(listOf("a"), listOf(Triple(1L, "a", null)))
        return Reconciliation.finish(Reconciliation.draft(arm, reply, walk), null)
    }

    /** A reconciliation that is complete but lists film 1 under two categories. */
    fun duplicateResult(arm: ProbeArm): Reconciliation.Result {
        val reply = reply(setOf("a", "b"), listOf(1L to "a"))
        val walk = walk(listOf("a", "b"), listOf(Triple(1L, "a", null), Triple(1L, "b", "a")))
        return Reconciliation.finish(Reconciliation.draft(arm, reply, walk), null)
    }

    /** A reconciliation with one film missing that only the drift check can explain. */
    fun pendingResult(arm: ProbeArm): Reconciliation.Result {
        val reply = reply(setOf("a"), listOf(1L to "a", 2L to "a"))
        val walk = walk(listOf("a"), listOf(Triple(1L, "a", null)))
        return Reconciliation.finish(Reconciliation.draft(arm, reply, walk), null)
    }
}
