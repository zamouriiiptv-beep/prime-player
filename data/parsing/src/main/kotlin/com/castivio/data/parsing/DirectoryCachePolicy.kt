package com.castivio.data.parsing

import com.castivio.domain.WorldListing

/**
 * How long what the directory said is believed, as pure rules over ages.
 *
 * The lists that change slowly and are asked for on every visit -- the countries, the tags -- are kept on the device: **fresh** for a day,
 * **usable but stale** for two weeks when the servers cannot be reached, and gone after that. A page of stations is kept in memory for a
 * short while, so going back to a list the viewer just left is not a second request.
 */
object DirectoryCachePolicy {

    private const val MINUTE_MS = 60_000L
    private const val DAY_MS = 24 * 60 * MINUTE_MS

    /** How long the countries and the tags are taken as current. */
    const val LISTS_FRESH_MS = DAY_MS

    /** How long they are still better than nothing, when the directory cannot be asked. */
    const val LISTS_USABLE_MS = 14 * DAY_MS

    /** How long a page of stations is taken as current: what the most listened to changes slowly, a country's list changes little. */
    fun pageFreshMs(listing: WorldListing): Long = when (listing) {
        WorldListing.Top -> 60 * MINUTE_MS
        is WorldListing.Search -> 2 * MINUTE_MS
        else -> 10 * MINUTE_MS
    }

    /** What a kept list's age says to do with it. */
    enum class Verdict { FRESH, REFRESH_BUT_KEEP, EXPIRED }

    fun verdict(ageMs: Long): Verdict = when {
        ageMs < 0 -> Verdict.REFRESH_BUT_KEEP
        ageMs < LISTS_FRESH_MS -> Verdict.FRESH
        ageMs < LISTS_USABLE_MS -> Verdict.REFRESH_BUT_KEEP
        else -> Verdict.EXPIRED
    }
}
