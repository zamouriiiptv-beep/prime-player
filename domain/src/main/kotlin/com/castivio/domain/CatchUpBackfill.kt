package com.castivio.domain

/**
 * Filling `Channel.catchUpHours` for a catalogue that was imported before the column existed.
 *
 * ## Why this is not a re-import
 *
 * A subscription whose provider's list has not changed is skipped by the importer, so rows written before the column
 * existed would keep no value for as long as the list stayed the same; forgetting the sync state to force them would
 * read the whole catalogue again, which is a cold load. What is needed is three fields of a list the provider already
 * has -- which channels keep an archive, and for how long -- so that is all that is asked, once, and all that is written:
 * the `catch_up_hours` of the rows that have one, found by their stable ids, in a single transaction. No row is
 * rewritten, and the search index, the groups, the favourites and the order a viewer made are not touched.
 *
 * ## When it fails
 *
 * It does not try harder. A provider that will not give the list in one answer -- the only cheap way to ask -- is left
 * to its next ordinary import, which writes the column with the row; there is no request per channel, and no
 * catalogue is read again for this. A failure of the connection alone is asked again the next time the app starts;
 * a refusal is not.
 */
interface CatchUpBackfill {

    /**
     * Fills the column for the active subscription if it has not been done, and does nothing otherwise. Safe to call
     * whenever the home screen opens: it returns at once when there is nothing to do, and is cancellable.
     */
    suspend fun ensure()
}

/** One channel's archive, as the provider's own list gives it: the row's stable id and the hours it keeps. */
data class CatchUpHours(val itemId: String, val hours: Int)

/** The write half of the backfill. Blocking, because SQLite is: the caller is on a thread of its own. */
interface CatchUpHoursWriter {

    /**
     * Sets `catch_up_hours` on the rows of [sourceId] named by [updates], **all in one transaction**, and returns how many
     * rows were found. An id that is not in the catalogue -- a channel the provider lists and the importer dropped -- is
     * simply not found.
     */
    fun apply(sourceId: String, updates: List<CatchUpHours>): Int
}

/** What became of a subscription's backfill, remembered so that it is not asked for twice. */
enum class BackfillOutcome {
    /** Filled, or there was nothing to fill: a catalogue that is not here yet is written whole by its first import. */
    DONE,

    /** The provider would not give its list in one answer. Left to the next ordinary import; not asked again. */
    REFUSED,
}

/** The mark that keeps the backfill from being repeated, per subscription. */
interface CatchUpBackfillMarks {
    fun outcomeOf(sourceId: String): BackfillOutcome?
    fun record(sourceId: String, outcome: BackfillOutcome)
}

/**
 * When the backfill may be asked for: only when nothing the viewer is waiting on or watching could feel it.
 *
 * Pure, so the rule is checked without a device. The request is one small call and one short write, and it is still held
 * back until the home screen is up and showing, no section is queued or arriving, and no stream is playing or opening --
 * and then until all of that has stayed true for [SETTLE_MS], so a screen that is still settling, or a channel pressed a
 * moment later, never meets it. A load or a stream that starts while it waits ends the wait; it begins again when things
 * are quiet.
 */
object CatchUpBackfillSchedule {

    /** How long everything must stay quiet before the request is made. */
    const val SETTLE_MS = 10_000L

    fun quiet(homeShown: Boolean, homeLoaded: Boolean, loadActive: Boolean, playbackActive: Boolean): Boolean =
        homeShown && homeLoaded && !loadActive && !playbackActive
}
