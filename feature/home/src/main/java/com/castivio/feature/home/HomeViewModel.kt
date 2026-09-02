package com.castivio.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.castivio.domain.CatalogRepository
import com.castivio.domain.MediaItem
import com.castivio.domain.MediaKind
import com.castivio.domain.PageRequest
import com.castivio.domain.SourceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Home: the provider, how much of each kind it carries, and a first taste of each.
 *
 * Counts and rows are separate reads for a reason that is a rule rather than a
 * preference — the count comes from an indexed `COUNT` and the row from a bounded
 * window, and neither is derived from the other. Counting by loading is the defect
 * this data layer is shaped to make impossible, and Home is where a "just take the
 * size of the list" would be most tempting.
 */
data class HomeState(
    /** What the user named their provider, or what its host is. Null before activation. */
    val provider: String? = null,
    val live: List<MediaItem> = emptyList(),
    val movies: List<MediaItem> = emptyList(),
    val episodes: List<MediaItem> = emptyList(),
    val liveCount: Int = 0,
    val movieCount: Int = 0,
    val seriesCount: Int = 0,
    val loading: Boolean = true,
) {
    /** True when the provider imported, and carried nothing at all. */
    val isEmpty: Boolean get() = liveCount == 0 && movieCount == 0 && seriesCount == 0
}

/**
 * Reads Home, and re-reads it when the catalogue underneath changes.
 *
 * The counts are flows, so an import finishing behind this screen moves the numbers
 * without anyone asking. The rows are one-shot window reads keyed off those counts:
 * `mapLatest` cancels a read that a newer count has already superseded, which is what
 * stops a slow first import from painting stale rows over fresh ones.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val catalog: CatalogRepository,
    sources: SourceRepository,
) : ViewModel() {

    val state: StateFlow<HomeState> = combine(
        sources.active().map { it?.label },
        catalog.count(MediaKind.LIVE),
        catalog.count(MediaKind.MOVIE),
        catalog.count(MediaKind.SERIES),
    ) { provider, live, movies, series ->
        HomeState(
            provider = provider,
            liveCount = live,
            movieCount = movies,
            seriesCount = series,
            loading = false,
        )
    }.mapLatest { counts ->
        counts.copy(
            live = row(MediaKind.LIVE, counts.liveCount),
            movies = row(MediaKind.MOVIE, counts.movieCount),
            episodes = row(MediaKind.SERIES, counts.seriesCount),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_GRACE_MS), HomeState())

    /**
     * One row's worth, or nothing when the section is empty.
     *
     * The count is checked first so an empty kind costs no query at all — on a
     * provider with no radio and no series that is two round trips to SQLite saved on
     * every visit to Home.
     */
    private suspend fun row(kind: MediaKind, count: Int): List<MediaItem> {
        if (count == 0) return emptyList()
        return catalog.page(PageRequest(kind = kind, limit = ROW)).items
    }

    private companion object {
        /**
         * How many cards a row holds.
         *
         * A row is a sample, not a section: the section is one press away and pages
         * properly. Twenty is about three screens of scroll on a television and costs
         * one bounded query.
         */
        const val ROW = 20

        const val SUBSCRIPTION_GRACE_MS = 5_000L
    }
}
