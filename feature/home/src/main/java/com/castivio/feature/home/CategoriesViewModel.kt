package com.castivio.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.castivio.core.common.Outcome
import com.castivio.domain.CatalogRepository
import com.castivio.domain.CatalogSections
import com.castivio.domain.MediaGroup
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
 * The categories of one section, fetched when that section is opened.
 *
 * This is the first request the app makes after signing in, and for most sessions the
 * only one that is not a category the user chose: pressing Channels asks
 * `get_live_categories` and touches neither films nor series. Pressing Movies asks for
 * film categories and touches neither of the others.
 *
 * The list itself is observed from the database rather than returned by the fetch. That
 * is what makes coming back to a section instant — the rows are already there, the
 * screen draws them, and [CatalogSections] decides on its own whether a request is
 * needed at all.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CategoriesViewModel @Inject constructor(
    private val catalog: CatalogRepository,
    private val sections: CatalogSections,
) : ViewModel() {

    private val section = MutableStateFlow<CatalogSection?>(null)

    private val _load = MutableStateFlow<SectionLoad>(SectionLoad.Idle)
    val load: StateFlow<SectionLoad> = _load.asStateFlow()

    /** One in flight at a time: a second press must not race the first. */
    private var fetching: Job? = null

    val categories: StateFlow<List<MediaGroup>> = section
        .flatMapLatest { current ->
            if (current == null) flowOf(emptyList()) else catalog.groups(current.kind)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_GRACE_MS), emptyList())

    /**
     * Told which section this is, and fetches it.
     *
     * Idempotent, because composition runs again for reasons that are not a change of
     * section — and asking the provider once per recomposition is exactly the defect
     * this whole flow exists to avoid.
     */
    fun open(current: CatalogSection) {
        if (section.value == current) return
        section.value = current
        fetch()
    }

    /** After a failure the user can act on. Same request, nothing else reset. */
    fun retry() = fetch()

    private fun fetch() {
        val current = section.value ?: return
        fetching?.cancel()
        fetching = viewModelScope.launch {
            _load.value = SectionLoad.Loading
            _load.value = when (val result = sections.categories(current.kind)) {
                is Outcome.Success -> SectionLoad.Ready(result.value)
                is Outcome.Failure -> SectionLoad.Failed(result.error)
            }
        }
    }

    private companion object {
        /**
         * How long the category list stays observed with nobody watching.
         *
         * Long enough that opening a category and pressing back does not re-run the
         * query; short enough that a section the user has left stops holding a cursor.
         */
        const val SUBSCRIPTION_GRACE_MS = 5_000L
    }
}
