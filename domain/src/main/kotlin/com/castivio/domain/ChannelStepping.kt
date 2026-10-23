package com.castivio.domain

/**
 * The channel list as a sequence: where a channel stands in it, and what is at a place.
 *
 * Read for one thing -- **the channel before and the channel after the one on screen**, in the
 * order the channel list shows them -- and kept apart from [CatalogRepository] and [CatalogPager]
 * so neither of those grows a method for it. It is read from the very windows the list's own
 * pages are read from, so the order the viewer set, a bouquet's order and a search's order are the
 * one order and there is no second copy of it to drift.
 *
 * Bounded like every read here: [window] is at most a page, and [rank] is one `COUNT`.
 */
interface ChannelWindows {

    /**
     * Where [channelId] stands in the list [query] shows, from 0 -- the number of channels before
     * it -- or null when it is not in that list: another bouquet's, or one the search left out.
     *
     * Null as well for an order that is not read in windows. The channel list is read in the
     * provider's order, the viewer's own where they made one, and a search's; the sorts that
     * are Room's own pages have no windows to count against.
     */
    suspend fun rank(query: CatalogQuery, channelId: String): Int?

    /**
     * [limit] rows of the list [query] shows, from [offset] -- the same rows, in the same order, as
     * its pages -- and how many rows the list has. [Page.totalCount] answers even when the window is
     * past the end, which is what lets a caller wrap round without a second question.
     */
    suspend fun window(query: CatalogQuery, offset: Int, limit: Int): Page<MediaItem>
}

/** A channel the list led to, where it stands, and how long the list is. */
data class SteppedChannel(val channel: Channel, val position: Int, val total: Int)

/**
 * The channel [by] places from [channelId] in the list [query] shows -- 1 for the next, -1 for the
 * one before -- going round: past the last is the first, and before the first is the last.
 *
 * Null when there is nowhere to go: [channelId] is not in the list, or the list holds nothing but
 * it. Never the same channel: a list of one has no next.
 *
 * Asked fresh each time and not walked from a remembered place, so a list that changed under a
 * viewer -- a refresh, a channel the provider dropped -- costs nothing but the position it is read
 * at, and two quick presses cannot both answer the same neighbour once the first has been played.
 */
suspend fun ChannelWindows.step(query: CatalogQuery, channelId: String, by: Int): SteppedChannel? {
    val here = rank(query, channelId) ?: return null
    val ahead = window(query, offset = (here + by).coerceAtLeast(0), limit = 1)
    val total = ahead.totalCount
    if (total < 2) return null
    val target = (here + by).mod(total)
    // Usually the one read: inside the list, the window already is the neighbour.
    val page = if (ahead.offset == target && ahead.items.isNotEmpty()) ahead else window(query, target, 1)
    val channel = page.items.firstOrNull() as? Channel ?: return null
    return SteppedChannel(channel = channel, position = target, total = total)
}
