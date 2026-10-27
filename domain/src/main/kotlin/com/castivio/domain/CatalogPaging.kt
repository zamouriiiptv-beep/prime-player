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
    /**
     * Only the channels the viewer has marked as favourites, in the provider's own order -- the list
     * the rail's Favourites entry opens. A favourite is user data and not a place in the catalogue,
     * so this has no [groupId], [search] or [arranging] of its own: a query that asks for it asks for
     * nothing else, and the order is the one every other list of channels is read in.
     */
    val favorites: Boolean = false,
    /**
     * Only the channels the viewer has watched, the one watched last first -- the list the rail's
     * Recently watched entry opens; see [ProgressRepository.watched]. Like [favorites], a list of its own:
     * no [groupId], [search] or [arranging], and never asked for with [favorites].
     */
    val recent: Boolean = false,
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

/** A show's own row: where it came from and how its provider names it. */
data class SeriesShell(
    val sourceId: String,
    /** The provider's id for the show: what its episodes are asked for by. */
    val providerRef: String,
    val title: String,
    val groupId: String?,
    /** The show's own row id: what a favourite of the show is kept under. Null where a fake does not say. */
    val mediaId: String? = null,
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

    /**
     * What a show's own row says about it -- the provider it came from, its id there, its category -- which is
     * what its episodes are asked for by. Null for a show with no such row. Defaulted so a fake that does not
     * ask for episodes need not answer.
     */
    suspend fun seriesShell(seriesId: String): SeriesShell? = null

    fun favorites(): Flow<PagingData<MediaItem>>

    /** Started but unfinished, most recent first. */
    fun continueWatching(): Flow<PagingData<InProgressItem>>

    /** Everything watched, most recent first — finished items included. */
    fun history(): Flow<PagingData<InProgressItem>>
}
