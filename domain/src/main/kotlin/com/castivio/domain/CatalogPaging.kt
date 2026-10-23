package com.castivio.domain

import androidx.paging.PagingData
import kotlinx.coroutines.flow.Flow

/**
 * What to show, without saying how much of it.
 *
 * Paged reads take this instead of [PageRequest]: offsets and limits are the
 * pager's business, not a screen's. A screen that could name an offset could
 * name `limit = 400_000`.
 */
data class CatalogQuery(
    val kind: MediaKind,
    val groupId: String? = null,
    val sort: SortOrder = SortOrder.PROVIDER,
    /**
     * Only the items whose name matches, or empty for all of them. Always a [searchTermOf]
     * result: the words as the index reads them, so two typings that mean the same search
     * are the same query and do not start a second stream.
     *
     * Answered by the full-text index, one window at a time, like every other read here.
     * Matches come back in the provider's order whatever [sort] says -- a search is read
     * against the list it narrows, and that list's own order is the provider's.
     */
    val search: String = "",
    /**
     * Read the channels of [groupId] as they are being arranged -- see [CatalogOrder] -- and not
     * as they were saved. A list on screen while the viewer moves channels asks for this, so what
     * it shows is the draft; every other list asks for the saved order. Meaningful only with a
     * [groupId].
     */
    val arranging: Boolean = false,
)

/**
 * A show, aggregated from its episodes.
 *
 * The Series screen lists ~600 shows, not the 40,000 episode rows they are
 * stored as, and this is the shape that comes back from that aggregation. It is
 * computed by SQL, never by grouping rows in memory.
 */
data class SeriesSummary(
    val seriesId: String,
    val title: String,
    val artworkUrl: String?,
    val episodeCount: Int,
    val seasonCount: Int,
)

/** A row for Continue Watching and History: what was watched, and how far. */
data class InProgressItem(val item: MediaItem, val progress: PlaybackProgress)

/**
 * Paged catalogue reads.
 *
 * Returns [PagingData] rather than a `PagingSource` on purpose: page size and
 * prefetch distance are performance decisions that belong in one place next to
 * the budgets, not repeated in every screen where one wrong number quietly
 * loads a thousand rows.
 */
interface CatalogPager {

    fun items(query: CatalogQuery): Flow<PagingData<MediaItem>>

    /** Shows, not episodes. [CatalogQuery.kind] is ignored — this is always series. */
    fun series(query: CatalogQuery): Flow<PagingData<SeriesSummary>>

    /** Seasons of one show, small enough to read whole. */
    fun seasons(seriesId: String): Flow<List<Season>>

    fun favorites(): Flow<PagingData<MediaItem>>

    /** Started but unfinished, most recent first. */
    fun continueWatching(): Flow<PagingData<InProgressItem>>

    /** Everything watched, most recent first — finished items included. */
    fun history(): Flow<PagingData<InProgressItem>>
}
