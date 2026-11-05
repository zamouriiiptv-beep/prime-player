package com.castivio.feature.home

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.castivio.domain.DirectoryFailure
import com.castivio.domain.DirectoryResult
import com.castivio.domain.StationHealth
import com.castivio.domain.WorldListing
import com.castivio.domain.WorldRadioDirectory
import com.castivio.domain.WorldRadioLibrary
import com.castivio.domain.WorldStation

/** The directory could not give a page, and why: what the list says in its own words instead of a blank. */
class WorldDirectoryException(val reason: DirectoryFailure) : Exception("The directory could not be reached: $reason")

/**
 * One list of the directory, read a page at a time -- never the whole of it -- and screened on the way in.
 *
 * ## The page is the directory's, the list is the viewer's
 *
 * The directory's own filter leaves out what *it* has found broken. What it has not checked for months, or that broke since, comes through
 * it -- so each page is read against what this device has seen of its stations ([StationHealth]): the ones resting after a failure or that
 * cannot be opened are left out, the ones that were unstable lately are listed after the others of their page.
 *
 * ## A page that screening empties does not end the list
 *
 * A raw page of sixty that was all resting is no stations, and a list that stopped there would say there were none. So a load goes on to the
 * next raw page, up to [MAX_RAW_PAGES] of them, until it has something to show or the directory has no more. The key of a page is where its
 * first raw station sits in the directory, so a page dropped from memory is read again from the same place.
 *
 * ## A station is on the list once
 *
 * The directory's order moves between two pages' reads, and a station near the join can come on both. A list that keys its rows by the
 * station's uuid cannot hold the same uuid twice, so the first page to carry a station keeps it and the later ones do not -- by page, so
 * reading a dropped page again gives the same stations it gave before.
 */
internal class WorldStationsSource(
    private val directory: WorldRadioDirectory,
    private val library: WorldRadioLibrary,
    private val listing: WorldListing,
    private val clock: () -> Long,
) : PagingSource<Int, WorldStation>() {

    /** Which page first carried each station: the page's key. */
    private val owner = HashMap<String, Int>()

    override fun getRefreshKey(state: PagingState<Int, WorldStation>): Int? = null

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, WorldStation> {
        val first = params.key ?: 0
        val size = params.loadSize.coerceAtLeast(1)
        // Going back reads the one page before; going on may read several, when screening leaves nothing to show.
        val most = if (params is LoadParams.Prepend) 1 else MAX_RAW_PAGES

        var offset = first
        var next: Int? = first
        val shown = ArrayList<WorldStation>()
        var pages = 0
        while (next != null && pages < most && shown.isEmpty()) {
            val answer = directory.page(listing, offset, size)
            val page = when (answer) {
                is DirectoryResult.Fresh -> answer.value
                is DirectoryResult.Stale -> answer.value
                is DirectoryResult.Unavailable -> {
                    // What was read before the failure is shown; only a load with nothing to show is an error.
                    if (shown.isEmpty()) return LoadResult.Error(WorldDirectoryException(answer.reason))
                    break
                }
            }
            pages += 1
            shown += screened(page.stations, first)
            next = if (page.endReached) null else page.nextOffset
            if (next != null) offset = next
        }

        // A list the screening emptied for as far as a load reads is, for the viewer, a list with nothing to play: it ends here.
        if (shown.isEmpty()) next = null
        val before = if (first > 0) (first - size).coerceAtLeast(0) else null
        return LoadResult.Page(data = shown, prevKey = before, nextKey = if (params is LoadParams.Prepend) null else next)
    }

    /** [stations] as the list shows them: the ones this device would not play left out, the shaky ones last, a station's first page keeping it. */
    private suspend fun screened(stations: List<WorldStation>, pageKey: Int): List<WorldStation> {
        val records = library.records(stations.map { it.uuid })
        val now = clock()
        return stations
            .filter { station -> synchronized(owner) { owner.getOrPut(station.uuid) { pageKey } == pageKey } }
            .map { it to StationHealth.standing(it, records[it.uuid], now) }
            .filter { (_, standing) -> StationHealth.listed(standing) }
            .sortedBy { (_, standing) -> StationHealth.rank(standing) }
            .map { it.first }
    }

    private companion object {
        /** How many raw pages one load reads while screening leaves it nothing to show. */
        const val MAX_RAW_PAGES = 4
    }
}
