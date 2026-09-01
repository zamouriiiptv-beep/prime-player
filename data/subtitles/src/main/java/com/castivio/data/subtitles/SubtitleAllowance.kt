package com.castivio.data.subtitles

import java.util.TimeZone

/**
 * How many subtitle files have actually been downloaded today.
 *
 * ## Why this exists, which is a defect and not a feature request
 *
 * The player told people "today's downloads are used up" when they had downloaded nothing.
 * There was no counter at all: the sentence was a translation of an HTTP status, and the
 * status was `429` — *too many requests* — arriving on a **search**. Two different things
 * were being called one thing, so a rate limit on a lookup came out as a spent quota on
 * downloads, and no amount of waiting until tomorrow would have helped.
 *
 * The fix has two halves. The other half is in [OpenSubtitlesApi], which no longer maps a
 * throttle onto a quota. This half is the number itself: a count of *completed* downloads,
 * kept for a day, that the sentence can be true about.
 *
 * ## What counts
 *
 * One thing. A subtitle file that arrived, parsed into cues, and was put in front of the
 * viewer. Not a search, not a result, not a press, not an attempt, not a refusal, not a
 * cancellation — none of those is a download, and the counter is incremented at exactly one
 * place in the codebase after the work is done rather than when it is asked for.
 *
 * ## What "saved" means here
 *
 * The player deliberately never writes a subtitle to disk — it is 60 KB, it is wanted now,
 * and a file in a cache is a file somebody has to remember to delete. So the destination a
 * download is confirmed against is the track the player is drawing from, and the moment of
 * confirmation is the moment it starts drawing. There is no file write to fail; there is an
 * assignment that either happened or did not.
 */
interface SubtitleAllowance {

    /** Completed downloads on the device's current calendar day. Zero on a new day. */
    fun spentToday(): Int

    /**
     * Record one completed download.
     *
     * Called after the file has arrived, parsed and been applied — never before, never on a
     * request, and never more than once for one operation.
     */
    fun recordDownload()

    /**
     * Record that OpenSubtitles itself says the day's allowance is gone.
     *
     * The only thing that may set the count without a file arriving, and it is not a guess:
     * it is a `406` on a download request, which is the provider stating the fact. Kept
     * because the provider's count and this one can legitimately differ — a download made
     * from another device spends the same account — and when they do, the provider is right.
     */
    fun markSpent()

    /**
     * The number of downloads a day is expected to hold.
     *
     * A property of the account rather than of this application, and not discoverable from
     * the API without asking for it, so it is a stated expectation: what a free
     * OpenSubtitles account is given. It decides only when the sheet stops offering to
     * download; the provider remains the authority, and [markSpent] is how it corrects us.
     */
    val dailyLimit: Int

    /** Whether the count has reached [dailyLimit]. False whenever the count is zero. */
    fun spent(): Boolean = dailyLimit > 0 && spentToday() >= dailyLimit
}

/**
 * Which calendar day a moment falls on, where the device is.
 *
 * Its own function, pure and tested, because "the counter resets at midnight" is a claim
 * about arithmetic that is wrong in two ways people keep rediscovering: dividing UTC
 * milliseconds by a day resets at UTC midnight, which is the middle of the afternoon in
 * some places, and adding a *fixed* offset is wrong for half the year everywhere that keeps
 * summer time.
 *
 * [TimeZone.getOffset] is asked about the instant in question, so a count taken before a
 * clock change and read after one is still attributed to the day it happened on.
 *
 * `floorDiv`, not `/`, because the answer for a moment before 1970 must round down rather
 * than towards zero — which costs nothing here and is the kind of thing that is only ever
 * found by someone whose device clock was wrong.
 */
fun localDayOf(millis: Long, zone: TimeZone = TimeZone.getDefault()): Long =
    Math.floorDiv(millis + zone.getOffset(millis), MILLIS_PER_DAY)

private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000
