package com.castivio.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.castivio.core.common.Outcome
import com.castivio.domain.CatalogPager
import com.castivio.domain.CatalogQuery
import com.castivio.domain.CatalogSections
import com.castivio.domain.MediaItem
import com.castivio.domain.SeriesSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * One category's contents, fetched when that category is opened.
 *
 * The second and last request of a browse: "UK · General" costs `get_live_streams` for
 * that one category. The other four hundred are not asked for, and neither are films or
 * series — which is the difference between a screen that appears in a second and an
 * import that takes minutes before anything is on it.
 *
 * The rows come from the pager, not from the fetch. A category already on the device
 * draws immediately and [CatalogSections] issues no request at all; one that is not
 * draws as soon as the first batch commits, because the writer commits per batch and
 * the pager observes the table.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CategoryViewModel @Inject constructor(
    private val pager: CatalogPager,
    private val sections: CatalogSections,
) : ViewModel() {

    private val opened = MutableStateFlow<Opened?>(null)

    private val _load = MutableStateFlow<SectionLoad>(SectionLoad.Idle)
    val load: StateFlow<SectionLoad> = _load.asStateFlow()

    private var fetching: Job? = null

    /** Channels and films. Empty for [CatalogSection.Series], which pages shows. */
    val items: Flow<PagingData<MediaItem>> = opened
        .filterNotNull()
        .flatMapLatest { pager.items(it.query) }
        .cachedIn(viewModelScope)

    /** Shows, aggregated by SQL from the rows the fetch wrote — never grouped here. */
    val shows: Flow<PagingData<SeriesSummary>> = opened
        .filterNotNull()
        .flatMapLatest { pager.series(it.query) }
        .cachedIn(viewModelScope)

    /**
     * Told which category this is, and fetches it.
     *
     * Idempotent for the same reason as everywhere else on this path: a recomposition
     * is not a new category, and treating it as one would issue a request per frame.
     */
    fun open(section: CatalogSection, groupId: String) {
        val next = Opened(section, groupId)
        if (opened.value == next) return
        opened.value = next
        fetch()
    }

    fun retry() = fetch()

    private fun fetch() {
        val current = opened.value ?: return
        fetching?.cancel()
        fetching = viewModelScope.launch {
            _load.value = SectionLoad.Loading
            _load.value = when (val result = sections.items(current.groupId)) {
                is Outcome.Success -> SectionLoad.Ready(result.value.takeIf { it >= 0 })
                is Outcome.Failure -> SectionLoad.Failed(result.error)
            }
        }
    }

    /** Which category, and what to read it with. */
    private data class Opened(val section: CatalogSection, val groupId: String) {
        val query: CatalogQuery get() = CatalogQuery(kind = section.kind, groupId = groupId)
    }
}
