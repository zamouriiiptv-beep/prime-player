package com.castivio.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.castivio.domain.CatalogRepository
import com.castivio.domain.MediaItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** What the search screen shows: the query it ran, and what came back. */
data class SearchState(
    val query: String = "",
    val results: List<MediaItem> = emptyList(),
    /** True between the last keystroke and the answer, so the screen can say so quietly. */
    val searching: Boolean = false,
) {
    /** A blank box is not "no results"; it is a screen that has not been asked anything. */
    val asked: Boolean get() = query.isNotBlank()
}

/**
 * Search as typing, which is the only kind this product has.
 *
 * There is no Search button and there is nothing to press: the data rules say results
 * appear as the user types, debounced at 120 ms, with superseded queries cancelled and
 * prefix matching over the FTS index. All four of those are here —
 * [debounce] is the first, [mapLatest] is the second and third (a newer query cancels
 * the coroutine running the older one), and the index is the repository's.
 *
 * On a television this matters more than on a phone: typing on a remote is slow enough
 * that an un-debounced screen issues a query per D-pad press across an on-screen
 * keyboard, and a 400,000-row library answers each of them.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val catalog: CatalogRepository,
) : ViewModel() {

    private val typed = MutableStateFlow("")

    val state: StateFlow<SearchState> = typed
        .debounce { if (it.isBlank()) 0 else DEBOUNCE_MS }
        .mapLatest { query ->
            if (query.isBlank()) {
                SearchState(query = query)
            } else {
                SearchState(query = query, results = catalog.search(query, LIMIT))
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_GRACE_MS), SearchState())

    fun type(query: String) {
        typed.value = query
    }

    fun clear() {
        typed.value = ""
    }

    private companion object {
        /**
         * Long enough that a word costs one query, short enough to feel like no wait.
         *
         * Stated in `CLAUDE.md` as a data rule rather than left to each screen, because
         * a second search surface with a different number is how "instant" stops
         * meaning anything.
         */
        const val DEBOUNCE_MS = 120L

        /** A search result list is read, not scrolled through; it is bounded rather than paged. */
        const val LIMIT = 60

        const val SUBSCRIPTION_GRACE_MS = 5_000L
    }
}
