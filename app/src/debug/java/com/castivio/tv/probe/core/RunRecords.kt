package com.castivio.tv.probe.core

/** What a request asked for. */
enum class RequestKind {
    /** `get_vod_categories`: small, and needed by every arm. */
    CATEGORIES,

    /** `get_vod_streams` with no category: the whole section. */
    REPLY,

    /** `get_vod_streams&category_id=`: one category of it. */
    CATEGORY,
}

/**
 * How a request ended, as a word. Never the exception's own text: it can carry the address.
 */
enum class Outcome {
    OK,
    HTTP,
    TIMEOUT,
    DNS,
    CONNECT,
    TLS,
    RESET,
    TRUNCATED,
    PROTOCOL,
    UNREADABLE,
    OVERSIZE,
    CANCELLED,
    OTHER,
}

/** Why a run does not count towards the comparison. A run with none is valid. */
enum class InvalidReason {
    FAILED_REQUESTS,
    CONTAMINATED,
    NETWORK_CHANGED,
    CANCELLED,
    OVERSIZE,
    CAP_EXCEEDED,
    PROVIDER_STOP,
    NO_DATA,
}

/** One request, timed from the start of its run. */
data class RequestRecord(
    val kind: RequestKind,
    /** The category asked for: a provider's id, not a name. Null for the list and for the one reply. */
    val category: String?,
    val startMs: Long,
    val endMs: Long,
    /** From the request's headers being sent to the first byte of the answer: the provider thinking. */
    val firstByteMs: Long?,
    val bytes: Long,
    val outcome: Outcome,
    val status: Int?,
    /** The connection that carried it, numbered from 1 within the run. Never an address, never a port. */
    val connection: Int?,
    val protocol: String?,
) {
    val durationMs: Long get() = endMs - startMs
}

data class NetworkNote(
    val transport: String,
    val metered: Boolean?,
    val changed: Boolean = false,
)

/**
 * What the client side can say about how many connections really carried the run.
 *
 * It is counted four ways that do not depend on one another: the connections that **received bytes**, and in
 * how many 250 ms windows at once; the requests the run has begun and not finished, to the last byte of their bodies;
 * the connections in its pool; and the new sockets it opened. Local ports are not among them -- they only name a
 * connection, they do not show it working.
 *
 * The requests are the run's own count and not OkHttp's `dispatcher.runningCallsCount()`: for a synchronous call that
 * one stops counting when the response **headers** arrive, so a body still streaming is not "running" to it, and a run
 * of three connections that are all receiving reads nought.
 */
data class ParallelEvidence(
    val workers: Int,
    val connectionsUsed: Int = 0,
    val connectionsWithBytes: Int = 0,
    val newConnections: Int = 0,
    val remoteEndpoints: Int = 0,
    val protocols: List<String> = emptyList(),
    val peakRunningCalls: Int = 0,
    val peakPoolConnections: Int = 0,
    val peakActiveConnections: Int = 0,
    val msAtFullWorkers: Long = 0L,
    val activeBuckets: Int = 0,
    val meanActive: Double = 0.0,
    val bucketMs: Long = 250L,
)

/** One download of the section, with everything the report says about it. */
data class RunRecord(
    val step: PlanStep,
    /** From just before the first request to the last byte of the last: the whole of it, the categories list included. */
    val totalMs: Long,
    val requests: List<RequestRecord>,
    /** Bytes the probe counted in the bodies. */
    val bytes: Long,
    val evidence: ParallelEvidence,
    /** What the platform counted for the app over the same time, where it counts. Includes headers, and any other traffic. */
    val wireBytes: Long? = null,
    /** How many categories the panel listed. */
    val categoriesListed: Int? = null,
    val duplicateCategoryIds: Int = 0,
    /** Bytes of each category's reply, by the provider's id: what two walks are compared by. */
    val categoryBytes: Map<String, Long> = emptyMap(),
    val network: NetworkNote = NetworkNote("unknown", null),
    val invalid: List<InvalidReason> = emptyList(),
    /** The status that ended the session (403, 429, 5xx), if a request got one. */
    val stopStatus: Int? = null,
    /** A refusal of the categories list or of the one reply: without them there is nothing to compare. */
    val fatalStatus: Int? = null,
) {
    val valid: Boolean get() = invalid.isEmpty()

    val failures: Int get() = requests.count { it.outcome != Outcome.OK }

    /** The requests for films: the list of categories is not one of them. */
    val filmRequests: List<RequestRecord> get() = requests.filter { it.kind != RequestKind.CATEGORIES }

    val replyBytes: Long?
        get() = requests.firstOrNull { it.kind == RequestKind.REPLY && it.outcome == Outcome.OK }?.bytes

    val megabytesPerSecond: Double?
        get() = if (totalMs > 0L) bytes / BYTES_PER_MB / (totalMs / 1000.0) else null

    private companion object {
        const val BYTES_PER_MB = 1_048_576.0
    }
}

/** Where a run is, for the screen. */
class RunProgress(
    val label: String,
    val elapsedMs: Long,
    val bytes: Long,
    val requestsDone: Int,
    val inFlight: Int,
)

/** What a run may not exceed. */
class RunLimits(
    val maxBytes: Long,
    val capMs: Long,
)

/** The session's hand on a run in progress. */
class RunControl {
    @Volatile
    var cancelled: Boolean = false
        private set

    fun cancel() {
        cancelled = true
    }
}

class RunRequest(
    val step: PlanStep,
    val control: RunControl,
    val limits: RunLimits,
    val progress: (RunProgress) -> Unit,
)

/**
 * A finished run, and what a verification run read: [reply] for the one reply, [walk] for a walk. Both null for a
 * timed run, which reads nothing from the bodies.
 */
class RunOutcome(
    val record: RunRecord,
    val reply: OneReplyScan? = null,
    val walk: WalkScan? = null,
)

/** Runs one step to its end. Blocks; returns promptly once [RunRequest.control] is cancelled. */
fun interface RunExecutor {
    fun execute(request: RunRequest): RunOutcome
}
