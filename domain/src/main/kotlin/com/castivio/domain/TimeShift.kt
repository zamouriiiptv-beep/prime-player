package com.castivio.domain

import com.castivio.core.common.AppError
import com.castivio.core.common.Outcome
import com.castivio.core.common.map

/**
 * Time shift: putting the picture of a channel a little behind the live edge, from the provider's archive, without leaving the
 * channel. The player offers it only for a channel whose archive has been *proved* to reach back that far.
 *
 * ## Why this is not Catch-up
 *
 * Catch-up is a screen: it lists the programmes that are over and plays one. Time shift is a control on the channel that is
 * playing: four places to go back to -- the start of the programme on, a quarter of an hour, half an hour, an hour -- and a way
 * back to the edge. The two share the provider's archive and its address, and so share [CatchUpGateway] and [CatchUpMemory]; what
 * they ask of it is different, and that difference is this file.
 *
 * ## Confirmed, and not claimed
 *
 * A provider says how many hours it keeps ([CatchUpChannel.archiveHours]); what its guide lists, and how far back, is its own,
 * and the two need not agree. So nothing is offered on the claim. A choice is offered when the archive is *known* to hold the
 * instant it would play from: inside the claimed window, at or after the oldest programme the provider marked as recorded, and not
 * inside a programme it listed, finished and did not record. A channel with no recorded programme in what the provider listed
 * offers nothing at all, and a control that cannot act is not drawn.
 *
 * Pure: the clock is given, so every rule here is checked without a device.
 */
enum class TimeShiftChoice(
    /** How many minutes before the live edge the choice goes, or null for the start of the programme that is on. */
    val minutes: Int?,
) {
    /** The start of the programme on air: where a viewer who tuned in late wants to be. */
    PROGRAMME_START(null),
    MINUTES_15(15),
    MINUTES_30(30),
    MINUTES_60(60),
}

/** One programme of a channel's guide, as far as the archive is concerned: when it ran, and whether the provider recorded it. */
data class ArchiveSpan(
    val startMs: Long,
    val stopMs: Long,
    /** The provider says it recorded this programme. */
    val archived: Boolean,
)

/**
 * What a channel's archive was found to hold when its guide was read, and what the provider's clock is.
 *
 * Kept in the process for as long as a listing is -- see [CatchUpMemory] -- and read against *now*, not against when it was
 * taken: the programme on air moves on, and a choice that was offered a few minutes ago is offered again only if it still holds.
 */
data class ArchiveCover(
    /** Every programme the guide listed that is not wholly in the future, in no promised order. */
    val spans: List<ArchiveSpan>,
    /**
     * How far the provider's wall clock is ahead of UTC, in milliseconds, or null when it could not be told.
     *
     * An archive is asked for by the time on the *provider's* clock, and the guide gives both that and the instant, so the
     * difference is read from the programmes themselves rather than guessed from a zone. Null leaves no way to address an instant
     * that is not a programme's own, and the player then does not offer a rewind at all.
     */
    val clockOffsetMs: Long?,
)

/** The answer to "what can this channel's archive be rewound into?". */
sealed interface ArchiveCoverage {

    /** The archive was read and holds at least one recorded programme. */
    data class Known(val cover: ArchiveCover) : ArchiveCoverage

    /** Nothing can be offered: the subscription has no archive Castivio can address, or the channel has no recorded programme. */
    data object None : ArchiveCoverage

    /** The provider was asked and the answer did not come. Not remembered as "nothing": the next look asks again. */
    data class Failed(val reason: AppError) : ArchiveCoverage
}

/** Where a choice is played from: the address, and how far behind the live edge it starts, at the moment it was made. */
data class TimeShiftAddress(
    val url: String,
    val behindMs: Long,
)

/**
 * What the player is handed to rewind one channel with.
 *
 * An interface so the player knows nothing of providers, panels or their archives: the shell makes one per channel it opens
 * and the player only asks. Both calls are cancellable, and neither runs before a first picture: [choices] is asked a moment
 * after one, [address] when the viewer has chosen.
 */
interface TimeShiftSource {

    /**
     * The choices the channel's archive really offers now, in the order the strip lists them. Empty when it offers none, and the
     * control is then not drawn. Served from memory when the archive was read lately, so asking again is not a request.
     */
    suspend fun choices(): List<TimeShiftChoice>

    /** The address to play [choice] from, and where that is. Failing is an answer: the viewer is told and stays on the live edge. */
    suspend fun address(choice: TimeShiftChoice): Outcome<TimeShiftAddress>
}

/** Which choices an archive offers, and where each one puts the picture. */
object TimeShiftPolicy {

    /** Nearer than this to the edge is not a rewind: the player already calls anything under half a minute "at the edge". */
    const val MIN_BEHIND_MS = 2 * CatchUpPolicy.MINUTE_MS

    /** Two choices this close are one place, and the one that names a programme wins over the one that names a number. */
    const val SAME_PLACE_MS = CatchUpPolicy.MINUTE_MS

    /**
     * How recently a programme may have ended and not yet carry the provider's mark before its silence is taken for a hole.
     *
     * Panels mark a programme as recorded a while after it ends, so the newest ones are often unmarked while the recording is
     * perfectly there. An unmarked programme that ended longer ago than this is a hole in the archive; a newer one is not known to be.
     */
    const val MARK_LAG_MS = 45 * CatchUpPolicy.MINUTE_MS

    /**
     * How long past the live edge the archive is asked to go on playing, in minutes.
     *
     * A rewind is a constant distance behind the edge, and stays there for as long as the viewer watches: asked for only as far as
     * the moment it was made, the archive would end after the distance it started from and put the viewer back on the edge a
     * jump ahead. This is the distance the stream is let run to; whatever the provider can serve before it ends is the viewer's.
     */
    const val CONTINUE_MINUTES = 180

    /** The oldest instant the archive is known to reach at [nowMs], or null when there is no evidence it reaches anywhere. */
    fun earliestMs(cover: ArchiveCover, nowMs: Long, windowHours: Int): Long? {
        val floor = nowMs - windowHours.toLong() * CatchUpPolicy.HOUR_MS
        var earliest: Long? = null
        for (span in cover.spans) {
            val usable = span.archived && span.stopMs > span.startMs && span.stopMs <= nowMs && span.startMs >= floor
            if (usable && (earliest == null || span.startMs < earliest)) earliest = span.startMs
        }
        return earliest
    }

    /** The programme on air at [nowMs], or null when the guide has none for this moment. An empty entry covers no moment. */
    fun onAir(cover: ArchiveCover, nowMs: Long): ArchiveSpan? =
        cover.spans.firstOrNull { it.startMs <= nowMs && nowMs < it.stopMs }

    /** The instant [choice] puts the picture at, or null when it cannot be placed: no programme is listed on air. */
    fun targetMs(choice: TimeShiftChoice, cover: ArchiveCover, nowMs: Long): Long? =
        choice.minutes?.let { nowMs - it * CatchUpPolicy.MINUTE_MS } ?: onAir(cover, nowMs)?.startMs

    /**
     * The choices [cover] offers at [nowMs] for a channel whose provider claims [windowHours] hours, in the strip's order: the
     * start of the programme first, then the distances, nearest to farthest.
     */
    fun choices(cover: ArchiveCover, nowMs: Long, windowHours: Int): List<TimeShiftChoice> {
        // No clock, no way to address an instant; and a claim of no hours leaves no window for any programme to be inside, so
        // [earliestMs] finds nothing and nothing is offered.
        if (cover.clockOffsetMs == null) return emptyList()
        val earliest = earliestMs(cover, nowMs, windowHours) ?: return emptyList()
        val holes = cover.spans.filter {
            !it.archived && it.stopMs > it.startMs && it.stopMs <= nowMs - MARK_LAG_MS
        }

        fun reachable(instant: Long): Boolean =
            instant >= earliest &&
                nowMs - instant >= MIN_BEHIND_MS &&
                holes.none { instant >= it.startMs && instant < it.stopMs }

        val start = targetMs(TimeShiftChoice.PROGRAMME_START, cover, nowMs)?.takeIf(::reachable)
        val out = ArrayList<TimeShiftChoice>(TimeShiftChoice.entries.size)
        if (start != null) out += TimeShiftChoice.PROGRAMME_START
        for (choice in TimeShiftChoice.entries) {
            if (choice.minutes == null) continue
            val instant = targetMs(choice, cover, nowMs) ?: continue
            if (!reachable(instant)) continue
            if (start != null && kotlin.math.abs(instant - start) < SAME_PLACE_MS) continue
            out += choice
        }
        return out
    }

    /** The minute [instantMs] falls in, as the archive is addressed: the start of that minute. */
    fun minuteOf(instantMs: Long): Long = instantMs - instantMs.mod(CatchUpPolicy.MINUTE_MS)

    /** How many minutes the archive is asked for, from the minute [instantMs] falls in: up to [nowMs] and [CONTINUE_MINUTES] past it. */
    fun durationMinutes(instantMs: Long, nowMs: Long): Int {
        val span = (nowMs - minuteOf(instantMs)).coerceAtLeast(0L)
        val toNow = ((span + CatchUpPolicy.MINUTE_MS - 1) / CatchUpPolicy.MINUTE_MS).toInt()
        return toNow + CONTINUE_MINUTES
    }
}

/**
 * A channel's [TimeShiftSource] over the provider's archive: the gateway's cover, the policy's choices, and an address made from both.
 *
 * Made once per channel the player opens and holding nothing of its own but the channel's reference, which is read from the
 * catalogue the first time it is needed and not before: a channel zapped through never asks for it. Every question after the
 * first is answered from the gateway's memory -- see [CatchUpGateway.coverage] -- so asking whether to offer a rewind, and what a
 * choice puts the picture at, are not requests.
 *
 * @param channel the channel as the archive knows it, resolved on first use. Null when the catalogue cannot say.
 * @param nowMs the current instant. The trusted one the rest of catch-up reads, and a function so a test moves it.
 */
class ArchiveTimeShift(
    private val gateway: CatchUpGateway,
    private val channel: suspend () -> CatchUpChannel?,
    private val nowMs: () -> Long,
) : TimeShiftSource {

    private var resolved: CatchUpChannel? = null
    private var asked = false

    private suspend fun channel(): CatchUpChannel? {
        if (!asked) {
            resolved = channel.invoke()
            asked = true
        }
        return resolved
    }

    override suspend fun choices(): List<TimeShiftChoice> {
        val channel = channel() ?: return emptyList()
        val now = nowMs()
        val cover = (gateway.coverage(channel, now) as? ArchiveCoverage.Known)?.cover ?: return emptyList()
        return TimeShiftPolicy.choices(cover, now, channel.archiveHours)
    }

    override suspend fun address(choice: TimeShiftChoice): Outcome<TimeShiftAddress> {
        val channel = channel() ?: return Outcome.Failure(AppError.NOT_FOUND)
        val now = nowMs()
        val cover = (gateway.coverage(channel, now) as? ArchiveCoverage.Known)?.cover
            ?: return Outcome.Failure(AppError.NOT_FOUND)
        // Offered when it was drawn, and not necessarily still: the archive is read against now, and a choice that no longer holds
        // is refused here and not played from a place the archive has not got.
        if (choice !in TimeShiftPolicy.choices(cover, now, channel.archiveHours)) return Outcome.Failure(AppError.NOT_FOUND)
        val target = TimeShiftPolicy.targetMs(choice, cover, now) ?: return Outcome.Failure(AppError.NOT_FOUND)
        val url = gateway.playbackUrlAt(channel, cover, target, TimeShiftPolicy.durationMinutes(target, now))
        // Where the stream begins is the minute the target falls in, a part of a minute before it.
        return url.map { TimeShiftAddress(url = it, behindMs = now - TimeShiftPolicy.minuteOf(target)) }
    }
}
