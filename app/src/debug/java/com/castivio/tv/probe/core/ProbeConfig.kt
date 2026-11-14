package com.castivio.tv.probe.core

/**
 * The decision rule's numbers, fixed before the first byte is downloaded.
 *
 * They are the owner's approved thresholds. Fixing them in a value, and not in the report's prose, is what keeps
 * the verdict from being argued backwards from the figures.
 */
data class Thresholds(
    /** The median time must be at least this much shorter than K1's. */
    val medianImprovement: Double = 0.25,

    /** And in every repeat, against K1's run in the same repeat, at least this much shorter. */
    val repeatImprovement: Double = 0.15,

    /** A wider arm replaces a narrower one that already passes only if it is at least this much shorter again. */
    val widerImprovement: Double = 0.15,

    /** Valid paired repeats needed before anything is decided: fewer is "not enough runs", never a verdict. */
    val minPairs: Int = 3,

    /** How long an arm must have had every worker receiving bytes at once for its parallelism to count as shown. */
    val minFullWidthMs: Long = 1_000L,
)

/** Everything the session is tuned by, in one place so a test can change one number and no other. */
data class ProbeConfig(
    /** Rest between two downloads, so the provider's own limits and the radio settle. */
    val cooldownMs: Long = 60_000L,

    /** A run that has not ended by now is cancelled and does not count. */
    val runCapMs: Long = 6 * 60_000L,

    /** The size of the one reply as measured on this provider before this tool existed (78 MB, as the strip counts MB). */
    val priorReplyBytes: Long = 78L * BYTES_PER_MB,

    /** Past this share of the announced data, or of the projection, the session pauses and asks. */
    val budgetSoftFactor: Double = 1.05,

    /** Past this share the session stops by itself and cannot be told to go on. */
    val budgetHardFactor: Double = 1.30,

    /** A single run that receives more than this many times the reference reply is cancelled. */
    val runawayFactor: Double = 2.0,

    /** More bytes counted for the app than the probe read, beyond the larger of this and [contaminationShare], is another traffic. */
    val contaminationFloorBytes: Long = BYTES_PER_MB,
    val contaminationShare: Double = 0.02,

    /** Invalid runs in a row after which the session stops: the conditions, not the provider, are what to look at. */
    val maxConsecutiveInvalid: Int = 3,

    /** The width of the buckets the bytes of each connection are counted in. */
    val bucketMs: Long = 250L,

    val repeats: Int = 3,

    val thresholds: Thresholds = Thresholds(),
) {
    companion object {
        /** The strip's own megabyte: `bytes / 1048576`. */
        const val BYTES_PER_MB = 1_048_576L
    }
}
