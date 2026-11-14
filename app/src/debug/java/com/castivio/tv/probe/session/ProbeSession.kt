package com.castivio.tv.probe.session

import com.castivio.tv.probe.core.BudgetSnapshot
import com.castivio.tv.probe.core.DataBudget
import com.castivio.tv.probe.core.Decision
import com.castivio.tv.probe.core.DecisionRule
import com.castivio.tv.probe.core.Fetch
import com.castivio.tv.probe.core.OneReplyScan
import com.castivio.tv.probe.core.PlanStep
import com.castivio.tv.probe.core.ProbeConfig
import com.castivio.tv.probe.core.ProbePlan
import com.castivio.tv.probe.core.Reconciliation
import com.castivio.tv.probe.core.RunControl
import com.castivio.tv.probe.core.RunExecutor
import com.castivio.tv.probe.core.RunLimits
import com.castivio.tv.probe.core.RunOutcome
import com.castivio.tv.probe.core.RunProgress
import com.castivio.tv.probe.core.RunRequest
import com.castivio.tv.probe.core.SessionHeader
import com.castivio.tv.probe.core.SessionState
import com.castivio.tv.probe.core.SessionStatus
import com.castivio.tv.probe.core.StepPurpose
import com.castivio.tv.probe.core.StopReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/**
 * Runs the plan, one download at a time, and is the only thing that decides when to stop.
 *
 * ## What it holds to
 *
 *  - **One download at a time**, with [ProbeConfig.cooldownMs] of rest between two.
 *  - **The pilot pauses it.** After the first repeat and the verification downloads it waits for [resume] or
 *    [stop]: the owner reads the errors, the parallelism and the completeness before the rest is spent.
 *  - **A 403, a 429 or any 5xx stops it at once**, with what has been measured so far (see [SafetyGuard]).
 *    So does a refusal of the categories list or of the one reply, a third invalid run in a row, and the
 *    hard data limit.
 *  - **More data than announced pauses it**, before the next download, and asks (see [DataBudget]).
 *
 * It does no network of its own: [executor] does, and a test gives it one that answers from a script.
 */
class ProbeSession(
    private val plan: ProbePlan,
    private val executor: RunExecutor,
    private val config: ProbeConfig,
    header: SessionHeader,
    private val io: CoroutineDispatcher,
) {
    private val budget = DataBudget(
        plannedDownloads = plan.fullDownloads,
        priorReplyBytes = config.priorReplyBytes,
        softFactor = config.budgetSoftFactor,
        hardFactor = config.budgetHardFactor,
        runawayFactor = config.runawayFactor,
    )

    private val mutableState = MutableStateFlow(
        SessionState(
            status = SessionStatus.IDLE,
            header = header,
            plan = plan,
            config = config,
            budget = snapshot(),
        ),
    )

    val state: StateFlow<SessionState> = mutableState.asStateFlow()

    @Volatile
    private var stopRequested = false

    @Volatile
    private var control: RunControl? = null

    @Volatile
    private var gate: CompletableDeferred<Boolean>? = null

    /** The owner has read the pilot, or agreed to more data: go on. */
    fun resume() {
        gate?.complete(true)
    }

    /** Ends the session after cancelling the run in progress. What was measured is kept. */
    fun stop() {
        stopRequested = true
        control?.cancel()
        gate?.complete(false)
    }

    suspend fun run() {
        publish { copy(status = SessionStatus.RUNNING) }
        val drafts = LinkedHashMap<String, Reconciliation.Draft>()
        val results = LinkedHashMap<String, Reconciliation.Result>()
        var driftFound: Reconciliation.Drift? = null
        var reference: OneReplyScan? = null
        var invalidStreak = 0
        var ending: StopReason? = null
        try {
            for (step in plan.steps) {
                if (stopRequested) {
                    ending = StopReason.ByUser
                    break
                }
                if (step.index > 0 && !cooldown()) {
                    ending = StopReason.ByUser
                    break
                }
                val level = budget.level()
                if (level == DataBudget.Level.HARD) {
                    ending = StopReason.HardBudget(budget.receivedBytes, budget.announcedBytes)
                    break
                }
                if (level == DataBudget.Level.SOFT) {
                    if (!pause(SessionStatus.PAUSED_BUDGET)) {
                        ending = StopReason.ByUser
                        break
                    }
                    budget.acknowledge()
                }

                publish {
                    copy(
                        status = SessionStatus.RUNNING,
                        coolingSeconds = 0,
                        current = RunProgress(step.label, 0L, 0L, 0, 0),
                        budget = snapshot(),
                    )
                }
                val outcome = execute(step)
                val record = outcome.record
                budget.add(record.bytes)
                if (step.purpose == StepPurpose.TIMED && step.arm.fetch == Fetch.ONE_REPLY && record.valid) {
                    val replyBytes = record.replyBytes
                    if (replyBytes != null) budget.adoptReference(replyBytes)
                }

                when {
                    step.purpose == StepPurpose.VERIFY && step.arm.fetch == Fetch.ONE_REPLY -> {
                        if (record.valid) reference = outcome.reply
                    }
                    step.purpose == StepPurpose.VERIFY -> {
                        val replyScan = reference
                        val walkScan = outcome.walk
                        if (replyScan != null && walkScan != null) {
                            val draft = Reconciliation.draft(step.arm, replyScan, walkScan)
                            drafts[step.arm.id] = draft
                            results[step.arm.id] = Reconciliation.finish(draft, null)
                        }
                    }
                    step.purpose == StepPurpose.DRIFT -> {
                        val replyScan = reference
                        val later = outcome.reply
                        if (replyScan != null && later != null && record.valid) {
                            driftFound = Reconciliation.drift(replyScan, later)
                            for ((id, draft) in drafts) results[id] = Reconciliation.finish(draft, later)
                        }
                    }
                }

                publish {
                    copy(
                        runs = runs + record,
                        completedSteps = step.index + 1,
                        current = null,
                        reconciliations = LinkedHashMap(results),
                        drift = driftFound,
                        budget = snapshot(),
                    )
                }

                val providerStatus = record.stopStatus
                if (providerStatus != null) {
                    ending = StopReason.ProviderStatus(providerStatus, step.label)
                    break
                }
                val fatalStatus = record.fatalStatus
                if (fatalStatus != null) {
                    ending = StopReason.Configuration(fatalStatus, step.label)
                    break
                }
                invalidStreak = if (record.valid) 0 else invalidStreak + 1
                if (invalidStreak >= config.maxConsecutiveInvalid) {
                    ending = StopReason.ConsecutiveInvalid(invalidStreak)
                    break
                }
                if (step.pauseAfter && !pause(SessionStatus.PAUSED_PILOT)) {
                    ending = StopReason.ByUser
                    break
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ending = StopReason.Unexpected(e.javaClass.simpleName)
        }
        finish(ending)
    }

    /** Runs [step] off the caller's thread; a cancellation of the session cancels the run and waits for it to end. */
    private suspend fun execute(step: PlanStep): RunOutcome {
        val runControl = RunControl()
        control = runControl
        if (stopRequested) runControl.cancel()
        val limits = RunLimits(maxBytes = budget.runLimitBytes, capMs = config.runCapMs)
        try {
            return coroutineScope {
                val job = async(io) {
                    executor.execute(
                        RunRequest(step, runControl, limits) { progress -> publish { copy(current = progress) } },
                    )
                }
                try {
                    job.await()
                } catch (e: CancellationException) {
                    runControl.cancel()
                    withContext(NonCancellable) { job.join() }
                    throw e
                }
            }
        } finally {
            control = null
        }
    }

    /** Rests for the cool-down, second by second so the screen can say how long is left. False if stopped meanwhile. */
    private suspend fun cooldown(): Boolean {
        var remaining = (config.cooldownMs / MS_PER_SECOND).toInt()
        publish { copy(status = SessionStatus.COOLING, current = null, coolingSeconds = remaining) }
        while (remaining > 0) {
            if (stopRequested) return false
            delay(MS_PER_SECOND)
            remaining--
            publish { copy(coolingSeconds = remaining) }
        }
        return !stopRequested
    }

    /** Waits for the owner. True to go on, false when the session was stopped instead. */
    private suspend fun pause(waiting: SessionStatus): Boolean {
        val next = CompletableDeferred<Boolean>()
        gate = next
        publish { copy(status = waiting, current = null, budget = snapshot(), decision = decisionOf(this)) }
        if (stopRequested) next.complete(false)
        val proceed = next.await()
        gate = null
        if (proceed) publish { copy(status = SessionStatus.RUNNING) }
        return proceed
    }

    private fun finish(reason: StopReason?) {
        val endStatus = if (reason == null) SessionStatus.FINISHED else SessionStatus.STOPPED
        publish {
            copy(
                status = endStatus,
                stop = reason,
                current = null,
                coolingSeconds = 0,
                budget = snapshot(),
                decision = decisionOf(this),
            )
        }
    }

    private fun decisionOf(state: SessionState): Decision =
        DecisionRule.evaluate(state.runs, state.reconciliations, config.thresholds)

    private fun snapshot(): BudgetSnapshot = BudgetSnapshot(
        announcedBytes = budget.announcedBytes,
        projectedBytes = budget.projectedBytes,
        receivedBytes = budget.receivedBytes,
        level = budget.level(),
    )

    private inline fun publish(change: SessionState.() -> SessionState) {
        mutableState.update { it.change() }
    }

    private companion object {
        const val MS_PER_SECOND = 1_000L
    }
}
