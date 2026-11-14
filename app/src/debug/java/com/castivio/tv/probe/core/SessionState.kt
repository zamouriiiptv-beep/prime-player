package com.castivio.tv.probe.core

enum class SessionStatus {
    /** Ready to start. */
    IDLE,

    RUNNING,

    /** Resting between two downloads. */
    COOLING,

    /** The pilot is done; the session waits for the owner to read it and go on or stop. */
    PAUSED_PILOT,

    /** More data than announced would be used; the session waits for the owner to agree. */
    PAUSED_BUDGET,

    STOPPED,

    FINISHED,
}

/** What the report says about the conditions, before any figure. */
data class SessionHeader(
    val userAgent: String,
    /** `http` or `https`: the only thing about the address that is ever printed. */
    val scheme: String,
    val network: NetworkNote,
)

data class BudgetSnapshot(
    val announcedBytes: Long,
    val projectedBytes: Long,
    val receivedBytes: Long,
    val level: DataBudget.Level,
)

/**
 * Everything the screen and the report are made of, at one moment.
 *
 * Immutable: the session replaces it, and nothing that reads it can change what another read.
 */
data class SessionState(
    val status: SessionStatus,
    val header: SessionHeader,
    val plan: ProbePlan,
    val config: ProbeConfig,
    val budget: BudgetSnapshot,
    val completedSteps: Int = 0,
    val current: RunProgress? = null,
    val coolingSeconds: Int = 0,
    val runs: List<RunRecord> = emptyList(),
    val reconciliations: Map<String, Reconciliation.Result> = emptyMap(),
    val drift: Reconciliation.Drift? = null,
    val stop: StopReason? = null,
    val decision: Decision? = null,
)
