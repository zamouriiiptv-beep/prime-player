package com.castivio.domain

import com.castivio.core.common.AppError
import com.castivio.core.common.Outcome

/**
 * Catch-up: watching a programme that has already been on, from the provider's archive.
 *
 * ## One contract, and the protocols behind it
 *
 * Everything above this file -- the button, the list of days, the choice of a programme -- is written once, against
 * [CatchUpGateway]. What differs between providers is how an archive is discovered, how the programmes in it are
 * listed and how one of them is addressed, and that is the protocol's own business, kept in the layer that already
 * knows the protocol (Xtream's is in `:data:epg`, over `:data:parsing` and `:data:networking`). A provider whose
 * archive has not been checked against how it really works answers [CatchUpListing.NotSupported]; nothing here
 * assumes that two providers address a programme alike.
 *
 * ## What costs a request, and what does not
 *
 * Only two calls can reach a network, and a person makes each of them: [CatchUpGateway.listing] when the catch-up
 * screen opens, [CatchUpGateway.playbackUrl] when a programme is chosen. Whether a channel *offers* catch-up is read
 * from what the catalogue already holds ([Channel.catchUpHours]); there is no probe, no prefetch and nothing that a
 * list scrolling past can start.
 */
data class CatchUpChannel(
    val mediaId: String,
    /** The provider's own id for the stream: what its archive is addressed by. Null for a row that has none. */
    val providerRef: String?,
    val title: String,
    /** How far back the provider says the archive goes, in hours: a claim, not a promise. Always positive here. */
    val archiveHours: Int,
)

/** One programme in an archive. */
data class CatchUpProgramme(
    val startMs: Long,
    val stopMs: Long,
    val title: String,
    val description: String?,
    /**
     * What the protocol needs to address this programme again -- for Xtream, the moment it starts as the panel itself
     * wrote it. Opaque to everything but the gateway that made it, which is what keeps one protocol's addressing
     * out of the screens.
     */
    val locator: String,
)

/** What a request for a channel's archive came to. */
sealed interface CatchUpListing {

    /** The archive's programmes, in the order the provider gave them. Empty is an answer: it has none. */
    data class Programmes(val items: List<CatchUpProgramme>) : CatchUpListing

    /** The provider cannot be asked, and nothing was requested. */
    data class NotSupported(val reason: CatchUpUnsupported) : CatchUpListing

    /** The provider was asked and the answer did not come, or was not one. */
    data class Failed(val error: AppError) : CatchUpListing
}

enum class CatchUpUnsupported {
    /** No subscription is active, or its credentials are not there. */
    NO_ACTIVE_PROVIDER,

    /** The active subscription is of a kind whose archive Castivio has not been taught. */
    PROTOCOL,

    /** The channel carries no provider id, which is what an archive is addressed by. */
    NO_PROVIDER_REFERENCE,
}

/**
 * The port to a provider's archive.
 *
 * Both suspending calls are cancellable: a screen that is left cancels its coroutine, and the request goes with it.
 */
interface CatchUpGateway {

    /**
     * [channel]'s archived programmes, asked of the provider. **Called when the catch-up screen opens and at no other
     * time.** Served from memory when the same channel was asked a short while ago, so entering twice is one request.
     */
    suspend fun listing(channel: CatchUpChannel, nowMs: Long): CatchUpListing

    /**
     * The address to play [programme] from. **Called when a programme is chosen and at no other time.** Failing is an
     * answer: the viewer is told, and nothing else on the screen moves.
     */
    suspend fun playbackUrl(channel: CatchUpChannel, programme: CatchUpProgramme): Outcome<String>

    /**
     * Whether this channel was asked for and had nothing archived, a short while ago. Answered from memory: it is what
     * lets the button go away for a channel that has been found empty, without anything being asked of the network.
     */
    fun knownEmpty(mediaId: String, nowMs: Long): Boolean
}

/** One day of an archive, as the viewer's own calendar draws it. */
data class CatchUpDay(
    /** The instant that day began where the viewer is, for a heading; the programmes are what the day holds. */
    val dayStartMs: Long,
    /** Newest first, as they are listed: the programme the viewer most likely wants is the one just over. */
    val programmes: List<CatchUpProgramme>,
)

/**
 * Which programmes of an archive can be watched, and how they are laid out.
 *
 * Pure: the clock and the zone are given, so every rule here is checked without a device.
 */
object CatchUpPolicy {

    /** Whether a channel offers catch-up at all, from what the catalogue holds. */
    fun offered(archiveHours: Int?): Boolean = (archiveHours ?: 0) > 0

    /**
     * How many days of archive a provider's hours amount to, as the channel's row says them: whole days, a part of one
     * counted as one, and never less than one for an archive that exists. Null for a channel with none.
     */
    fun daysOf(archiveHours: Int?): Int? =
        archiveHours?.takeIf { it > 0 }?.let { (it + HOURS_PER_DAY - 1) / HOURS_PER_DAY }

    /**
     * The programmes that can be replayed: over, and inside the window the provider claims, newest first.
     *
     * A programme that has not finished is the live channel's, and one that began before the window is out of the
     * archive however the provider listed it. A zero-length entry is a gap in a guide and not a programme.
     */
    fun replayable(items: List<CatchUpProgramme>, nowMs: Long, archiveHours: Int): List<CatchUpProgramme> {
        val earliest = nowMs - archiveHours.toLong() * HOUR_MS
        return items
            .asSequence()
            .filter { it.stopMs > it.startMs && it.stopMs <= nowMs && it.startMs >= earliest }
            .sortedByDescending { it.startMs }
            .toList()
    }

    /**
     * [items] grouped by the day they began on, the newest day first.
     *
     * [offsetMsAt] is the viewer's offset from UTC at an instant -- a function and not a number because it changes
     * with daylight saving, and a week spans a change twice a year.
     */
    fun days(items: List<CatchUpProgramme>, offsetMsAt: (Long) -> Long): List<CatchUpDay> {
        val byDay = LinkedHashMap<Long, MutableList<CatchUpProgramme>>()
        for (item in items.sortedByDescending { it.startMs }) {
            val offset = offsetMsAt(item.startMs)
            byDay.getOrPut((item.startMs + offset).floorDiv(DAY_MS)) { ArrayList() }.add(item)
        }
        return byDay.map { (day, programmes) ->
            val first = programmes.first()
            CatchUpDay(dayStartMs = day * DAY_MS - offsetMsAt(first.startMs), programmes = programmes)
        }
    }

    /** The length a provider is asked to replay, in whole minutes and never less than one. */
    fun durationMinutes(programme: CatchUpProgramme): Int {
        val ms = (programme.stopMs - programme.startMs).coerceAtLeast(0L)
        return ((ms + MINUTE_MS - 1) / MINUTE_MS).toInt().coerceAtLeast(1)
    }

    private const val HOURS_PER_DAY = 24
    const val MINUTE_MS = 60_000L
    const val HOUR_MS = 60 * MINUTE_MS
    const val DAY_MS = 24 * HOUR_MS
}

/**
 * What was asked of one provider's archive a short while ago, kept so that asking twice is asking once.
 *
 * Two memories, both in the process and neither on disk: a listing is worth keeping for as long as it takes to look at
 * another programme and come back, and a channel found with nothing archived is worth remembering for longer, so that
 * its button does not keep inviting a request that will come to nothing. Kept short because an archive moves on
 * with every programme that ends.
 *
 * Not thread-safe, and not meant to be: the gateway that owns it guards it.
 */
class CatchUpMemory(
    private val listingTtlMs: Long = LISTING_TTL_MS,
    private val emptyTtlMs: Long = EMPTY_TTL_MS,
    private val capacity: Int = CAPACITY,
) {
    private class Kept(val atMs: Long, val items: List<CatchUpProgramme>)

    private val listings = LinkedHashMap<String, Kept>()
    private val empties = LinkedHashMap<String, Long>()

    /** The listing asked for [mediaId] within the last [listingTtlMs], or null. */
    fun listing(mediaId: String, nowMs: Long): List<CatchUpProgramme>? {
        val kept = listings[mediaId] ?: return null
        if (nowMs - kept.atMs !in 0..listingTtlMs) {
            listings.remove(mediaId)
            return null
        }
        return kept.items
    }

    /** Keeps [items] as the answer for [mediaId]; an empty one also marks the channel as having nothing archived. */
    fun remember(mediaId: String, items: List<CatchUpProgramme>, nowMs: Long) {
        listings.remove(mediaId)
        listings[mediaId] = Kept(nowMs, items)
        while (listings.size > capacity) listings.remove(listings.keys.first())
        if (items.isEmpty()) {
            empties.remove(mediaId)
            empties[mediaId] = nowMs
            while (empties.size > capacity) empties.remove(empties.keys.first())
        } else {
            empties.remove(mediaId)
        }
    }

    fun knownEmpty(mediaId: String, nowMs: Long): Boolean {
        val at = empties[mediaId] ?: return false
        if (nowMs - at !in 0..emptyTtlMs) {
            empties.remove(mediaId)
            return false
        }
        return true
    }

    companion object {
        const val LISTING_TTL_MS = 10 * CatchUpPolicy.MINUTE_MS
        const val EMPTY_TTL_MS = 30 * CatchUpPolicy.MINUTE_MS

        /** Channels remembered: enough for a session's browsing, few enough that the rest is not a cache of the library. */
        const val CAPACITY = 24
    }
}

/**
 * What an archive listing really held, for a development build to write down.
 *
 * **To prove the archive's depth and not to assume it.** A provider says how many days it keeps (`tv_archive_duration`);
 * what its guide lists, and how far back, is its own, and the two do not have to agree. This is the answer to "how far
 * back did the listing really go": the count it held, how many programmes carry the provider's archive mark, how many
 * of those can be replayed inside the window, and the oldest and newest start of each, as hours before now.
 *
 * Numbers only. Nothing of the stream's address, the account or the channel is in it, and it is built and written only
 * where the development switch is on.
 */
data class CatchUpDepth(
    val received: Int,
    val archived: Int,
    val replayable: Int,
    val windowHours: Int,
    /** Oldest and newest start of everything received, as milliseconds before now (negative: in the future). */
    val receivedOldestAgoMs: Long?,
    val receivedNewestAgoMs: Long?,
    /** The same for the programmes carrying the archive mark. */
    val archivedOldestAgoMs: Long?,
    val archivedNewestAgoMs: Long?,
    /** And the oldest of those that can be replayed: how far back a viewer can really go. */
    val replayableOldestAgoMs: Long?,
) {

    /** One line, `key=value`, hours to a tenth, `-` where there is nothing. */
    fun format(): String = buildString {
        append("catch-up archive: received=").append(received)
        append(" archived=").append(archived)
        append(" replayable=").append(replayable)
        append(" window=").append(windowHours).append('h')
        append(" received[oldest=").append(ago(receivedOldestAgoMs)).append(" newest=").append(ago(receivedNewestAgoMs)).append(']')
        append(" archived[oldest=").append(ago(archivedOldestAgoMs)).append(" newest=").append(ago(archivedNewestAgoMs)).append(']')
        append(" replayable[oldest=").append(ago(replayableOldestAgoMs)).append(']')
    }

    private fun ago(ms: Long?): String {
        if (ms == null) return "-"
        val tenths = kotlin.math.abs(ms) * 10 / CatchUpPolicy.HOUR_MS
        val text = "${tenths / 10}.${tenths % 10}h"
        return if (ms >= 0) "$text ago" else "$text ahead"
    }

    companion object {
        /**
         * The reading of a listing: the start of every programme received, of those carrying the mark, and of those that
         * can be replayed, against [nowMs].
         */
        fun of(
            receivedStarts: List<Long>,
            archivedStarts: List<Long>,
            replayableStarts: List<Long>,
            windowHours: Int,
            nowMs: Long,
        ): CatchUpDepth = CatchUpDepth(
            received = receivedStarts.size,
            archived = archivedStarts.size,
            replayable = replayableStarts.size,
            windowHours = windowHours,
            receivedOldestAgoMs = receivedStarts.minOrNull()?.let { nowMs - it },
            receivedNewestAgoMs = receivedStarts.maxOrNull()?.let { nowMs - it },
            archivedOldestAgoMs = archivedStarts.minOrNull()?.let { nowMs - it },
            archivedNewestAgoMs = archivedStarts.maxOrNull()?.let { nowMs - it },
            replayableOldestAgoMs = replayableStarts.minOrNull()?.let { nowMs - it },
        )
    }
}
