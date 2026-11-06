package com.castivio.feature.home

import com.castivio.core.common.format.CastivioDates
import com.castivio.domain.Channel
import com.castivio.domain.Programme

/**
 * What Home's live card offers when the viewer has watched something: the channel watched last, what is on it now, and the
 * few watched before it.
 *
 * ## Facts, and only facts
 *
 * Every field is something the device holds. The channel is the last row of the viewer's own watch record; the programme is
 * what the stored guide says is on at this instant -- null, and not a guess, where it says nothing; the earlier channels are
 * the rows watched before that one. Nothing here is fetched for the card: the guide is read from storage, as the channels'
 * board reads it first, and a channel whose guide is not stored shows its name and no programme.
 *
 * ## Carried ready to draw
 *
 * The hours are strings, formatted here by [CastivioDates] -- the one place a time becomes text -- and [progress] is a
 * fraction, so the card does arithmetic and formatting in neither composition nor layout.
 */
data class Resume(
    /** The channel whose picture showed last. */
    val channel: Channel,
    /** What is on [channel] now, or null where the stored guide holds nothing for this instant. */
    val programme: Programme?,
    /** How far into [programme] it is, 0 to 1, as at the moment this was made; 0 with no programme. */
    val progress: Float,
    /** [programme]'s start and end as clock times, or null with it. */
    val from: String?,
    val to: String?,
    /** The channels watched before [channel], the one watched just before it first; at most [RESUME_EARLIER]. */
    val earlier: List<Channel>,
)

/** How many channels the card names after the one it resumes: the strip's four plates. */
internal const val RESUME_EARLIER = 4

/** How many of the watch record Home reads: the one resumed and the ones before it. */
internal const val RESUME_READ = RESUME_EARLIER + 1

/**
 * The card's offer, from the channels watched last -- the last first -- and what the guide says is on the first of them.
 *
 * Null when nothing was watched. A [programme] that is not on at [nowMs] is not offered as what is on: a guide row that
 * ended a minute ago is the previous programme, and drawing it as the current one is the small lie a viewer catches at once.
 */
internal fun resumeOf(recent: List<Channel>, programme: Programme?, nowMs: Long): Resume? {
    val channel = recent.firstOrNull() ?: return null
    val live = programme?.takeIf { it.isLiveAt(nowMs) }
    return Resume(
        channel = channel,
        programme = live,
        progress = live?.progressAt(nowMs) ?: 0f,
        from = live?.let { CastivioDates.time(it.startMs) },
        to = live?.let { CastivioDates.time(it.stopMs) },
        earlier = recent.drop(1).take(RESUME_EARLIER),
    )
}

/**
 * The keys a channel's programmes may be filed under, in the order the guide's writer uses them: the guide id the catalogue
 * carries, then the media id. The same two the channels' board reads, so the card and the board never disagree on what is on.
 */
internal fun guideKeys(channel: Channel): List<String> =
    listOfNotNull(channel.epgChannelId?.takeIf { it.isNotBlank() }, channel.id).distinct()

/** How often the card's bar is moved while a programme is on: once a minute is as fine as its hours are. */
internal const val RESUME_TICK_MS = 60_000L

/** How soon a channel with no programme is asked about again: the board may have fetched its guide by then. */
internal const val RESUME_RETRY_MS = 5 * 60_000L

/**
 * How long until the card's programme and bar are read again: at the programme's end if that is sooner than a tick -- so the
 * next one is on the card as it starts -- and otherwise a tick; a channel with no programme is asked again after
 * [RESUME_RETRY_MS]. A programme that is on has a stop after [nowMs], so the wait is never under a second: one that ends in the
 * next millisecond is read again a second after it, and not in a loop.
 */
internal fun resumeRefreshMs(programme: Programme?, nowMs: Long): Long {
    if (programme == null || !programme.isLiveAt(nowMs)) return RESUME_RETRY_MS
    return minOf(RESUME_TICK_MS, programme.stopMs - nowMs + 1_000L)
}

/**
 * Which face the live card shows.
 *
 * [Plain] is the card as it was: the glyph and its lines, the name and the count -- or the load, its bar and its failure. It
 * stays whenever the section is loading, queued, has just arrived or failed, so everything the load says is said as before.
 * [NotLoaded] is the invitation of a section that has never been fetched and is not on its way. [Continue] is the channel
 * watched last, once the section is here and something was watched.
 */
internal enum class HeroFace { Plain, NotLoaded, Continue }

/**
 * Decided from four facts and nothing else, so each face is a unit test and not a device session.
 *
 * @param unfetched the section has no rows here and was never marked as loaded.
 * @param counted the section has rows on the device.
 * @param load what the card says about the section's load: only [CardLoad.Idle] leaves the card free to change its face.
 * @param resume what was watched last, or null.
 */
internal fun heroFace(unfetched: Boolean, counted: Boolean, load: CardLoad, resume: Resume?): HeroFace =
    tileFace(unfetched, counted, load, resumed = resume != null)
