package com.castivio.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.castivio.core.common.Outcome
import com.castivio.domain.CatalogPager
import com.castivio.domain.CatalogSections
import com.castivio.domain.Season
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * One show's seasons and episodes, fetched when the show is opened.
 *
 * The third and deepest request, and the one it would be most expensive to make early:
 * `get_series_info` is a request *per show*, so a provider with six hundred series
 * would cost six hundred requests to fill in up front, for episode lists almost none of
 * which get looked at. One show, when it is opened.
 *
 * A show is never a stream. The season list is what a press on a series opens, and the
 * episode is the playable thing — which the type system already holds, because
 * `asSelection` has nothing to build a request from for a show.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ShowViewModel @Inject constructor(
    private val pager: CatalogPager,
    private val sections: CatalogSections,
) : ViewModel() {

    private val series = MutableStateFlow<String?>(null)

    private val _load = MutableStateFlow<SectionLoad>(SectionLoad.Idle)
    val load: StateFlow<SectionLoad> = _load.asStateFlow()

    private var fetching: Job? = null

    /**
     * The seasons, read from the database.
     *
     * Bounded by the show rather than by the library, which is the one place in this
     * app where holding a whole list is right: a season list is tens of rows, and
     * paging it would cost a query per screenful to save nothing.
     */
    val seasons: StateFlow<List<Season>> = series
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else pager.seasons(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_GRACE_MS), emptyList())

    fun open(seriesId: String) {
        if (series.value == seriesId) return
        series.value = seriesId
        fetch()
    }

    fun retry() = fetch()

    private fun fetch() {
        val id = series.value ?: return
        fetching?.cancel()
        fetching = viewModelScope.launch {
            _load.value = SectionLoad.Loading
            _load.value = when (val result = sections.episodes(id)) {
                is Outcome.Success -> SectionLoad.Ready(result.value.takeIf { it >= 0 })
                is Outcome.Failure -> SectionLoad.Failed(result.error)
            }
        }
    }

    private companion object {
        const val SUBSCRIPTION_GRACE_MS = 5_000L
    }
}
