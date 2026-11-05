package com.castivio.domain

import com.castivio.core.common.PlayOutcome

/**
 * What this device has learned about one station of the world, from listening to it.
 *
 * The directory's own word -- `lastcheckok` -- is a status that may be days old, so it is one witness and not the verdict: what the player
 * did with the stream, here, is the other, and the two are read together by [StationHealth].
 */
data class StationRecord(
    val uuid: String,
    /** When a listen of it was last confirmed, or null. */
    val lastOkAtMs: Long? = null,
    /** When a listen of it last failed, or null. */
    val lastFailedAtMs: Long? = null,
    /** Failures since the last confirmed listen: what the ladder of rests is climbed by. */
    val consecutiveFailures: Int = 0,
    /** Unstable listens since [unstableSinceMs]. */
    val unstableCount: Int = 0,
    /** Where the day that [unstableCount] counts began, or null. */
    val unstableSinceMs: Long? = null,
)

/** How a station stands in a list, as far as this device knows. */
enum class Standing {
    /** Listed in its place. */
    NORMAL,

    /** Listed, after the others: unstable more than once today, or not checked by the directory for a long while. */
    LOWERED,

    /** Failed lately and is resting: left out of the lists, shown dimmed among a viewer's favourites. Never for ever. */
    RESTING,

    /** Not a stream that can be asked for at all -- no address the player can open, or the directory says it does not play. */
    SCREENED_OUT,
}

/**
 * Whether a station is worth offering, from what the directory said of it and what this device saw -- as pure rules, tested without a
 * network or a player.
 *
 * ## The directory's server-side filter is not trusted alone
 *
 * `hidebroken` leaves out what the directory *has checked* and found broken; a stream that broke since, or that the directory has not
 * checked for months, comes through it. So every station is read here too, and the player's own answer outranks the directory's.
 *
 * ## A failure rests a station for a while, never for ever
 *
 * The first failure rests it an hour, the next six, then a day, then three days, and a confirmed listen clears the lot. A server that was
 * down for an afternoon, or a network that was, must not take a station out of the lists for good -- and the rest is an interval after the
 * last failure, so the station is back by itself and is asked for again the next time it is pressed.
 *
 * ## An unstable listen is not a failure
 *
 * It lowers a station's place after the third of the day and does nothing else.
 */
object StationHealth {

    private const val HOUR_MS = 3_600_000L
    private const val DAY_MS = 24 * HOUR_MS

    /** The rests, by how many failures there have been in a row: the last one holds for every failure after it. */
    internal val REST_LADDER_MS = longArrayOf(HOUR_MS, 6 * HOUR_MS, DAY_MS, 3 * DAY_MS)

    /** Unstable listens in a day that lower a station's place. */
    const val UNSTABLE_LOWERS_AT = 3

    /** How old the directory's last check of a stream may be before it is not taken as news. */
    const val STALE_CHECK_MS = 30 * DAY_MS

    /**
     * The record after [outcome] on [uuid] at [nowMs]; the same one when the outcome says nothing.
     */
    fun after(record: StationRecord?, uuid: String, outcome: PlayOutcome, nowMs: Long): StationRecord? {
        val base = record ?: StationRecord(uuid)
        return when (outcome) {
            PlayOutcome.INCONCLUSIVE -> record
            PlayOutcome.CONFIRMED -> base.copy(
                lastOkAtMs = nowMs,
                consecutiveFailures = 0,
                unstableCount = 0,
                unstableSinceMs = null,
            )
            PlayOutcome.FAILED -> base.copy(
                lastFailedAtMs = nowMs,
                consecutiveFailures = base.consecutiveFailures + 1,
            )
            PlayOutcome.UNSTABLE -> {
                val since = base.unstableSinceMs
                if (since == null || nowMs - since > DAY_MS) {
                    base.copy(unstableCount = 1, unstableSinceMs = nowMs)
                } else {
                    base.copy(unstableCount = base.unstableCount + 1)
                }
            }
        }
    }

    /** When the rest the record is in ends, or null when it is not resting for failures. */
    fun restEndsAtMs(record: StationRecord?): Long? {
        record ?: return null
        val failedAt = record.lastFailedAtMs ?: return null
        if (record.consecutiveFailures <= 0) return null
        val step = REST_LADDER_MS[minOf(record.consecutiveFailures - 1, REST_LADDER_MS.lastIndex)]
        return failedAt + step
    }

    /** Whether the address can be opened by the player at all: a web address, and not an empty one. */
    private fun opens(address: String?): Boolean {
        val a = address?.trim().orEmpty()
        if (a.isEmpty() || a.length > MAX_ADDRESS) return false
        return a.startsWith("http://", ignoreCase = true) || a.startsWith("https://", ignoreCase = true)
    }

    fun standing(station: WorldStation, record: StationRecord?, nowMs: Long): Standing {
        if (!opens(station.url) && !opens(station.urlResolved)) return Standing.SCREENED_OUT
        if (station.serverCheckedOk == false && !confirmedSince(record, station.serverCheckedAtMs)) return Standing.SCREENED_OUT

        val restEnd = restEndsAtMs(record)
        if (restEnd != null && nowMs < restEnd) return Standing.RESTING

        val checkedAt = station.serverCheckedAtMs
        val unknownOrOld = checkedAt == null || nowMs - checkedAt > STALE_CHECK_MS
        val unstableToday = record != null && record.unstableCount >= UNSTABLE_LOWERS_AT &&
            record.unstableSinceMs != null && nowMs - record.unstableSinceMs <= DAY_MS
        // A listen confirmed here since the directory last looked is newer news than its check.
        val vouched = confirmedSince(record, checkedAt)
        return if (unstableToday || (unknownOrOld && !vouched)) Standing.LOWERED else Standing.NORMAL
    }

    /** Whether this device confirmed a listen after [checkedAt]: the directory's check is then older than what was seen here. */
    private fun confirmedSince(record: StationRecord?, checkedAt: Long?): Boolean {
        val ok = record?.lastOkAtMs ?: return false
        return checkedAt == null || ok > checkedAt
    }

    /** A station's place in a list: lower comes first. Screened-out stations have none -- they are not listed. */
    fun rank(standing: Standing): Int = when (standing) {
        Standing.NORMAL -> 0
        Standing.LOWERED -> 1
        Standing.RESTING -> 2
        Standing.SCREENED_OUT -> 3
    }

    /** Whether a list that is not the viewer's own favourites shows a station at all. */
    fun listed(standing: Standing): Boolean = standing == Standing.NORMAL || standing == Standing.LOWERED

    private const val MAX_ADDRESS = 2048
}

/**
 * Whether failures are the stations' or the network's.
 *
 * Several different stations failing within a couple of minutes is a network or a server that went, not several stations that broke at
 * once, and writing each of them down as failed would rest stations that are fine. The guard says whether a failure just seen is to be
 * counted; a failure of a station that is the only one failing is.
 */
class NetworkTroubleGuard(
    private val windowMs: Long = WINDOW_MS,
    private val distinctStations: Int = DISTINCT_STATIONS,
) {
    private val recent = ArrayDeque<Pair<Long, String>>()

    /** Notes a failure of [uuid] at [nowMs] and returns whether it is to be written down as the station's own. */
    fun countsAgainst(uuid: String, nowMs: Long): Boolean {
        while (recent.isNotEmpty() && nowMs - recent.first().first > windowMs) recent.removeFirst()
        recent.addLast(nowMs to uuid)
        return recent.map { it.second }.toSet().size < distinctStations
    }

    private companion object {
        const val WINDOW_MS = 120_000L
        const val DISTINCT_STATIONS = 3
    }
}
