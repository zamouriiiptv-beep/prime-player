package com.castivio.feature.activation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.castivio.domain.ProviderSource
import com.castivio.domain.SourceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The subscriptions this box already holds, and which one is in use.
 *
 * ## Why there is no storage here
 *
 * `SourceRepository` already exists in `:domain`, backed by Room in `:data:database`,
 * and it already has every operation this screen needs: the list, the active one, and
 * switching between them. A second store for "saved users" would be the same rows
 * written twice, and the two would disagree the first time an import registered a
 * provider without going through this screen.
 *
 * So this view model owns no state of its own. It maps two repository flows into one
 * value the screen renders, and it forwards a switch. Everything that could be wrong
 * about it is wrong in one place.
 *
 * ## What "loading" is for
 *
 * The list arrives asynchronously and an empty list is a real, common answer — a fresh
 * install has no subscriptions. Rendering the empty state during the first frame and
 * the list a moment later is a flash of "you have nothing" at exactly the moment a
 * returning user is looking for their subscriptions, so the two are distinct states
 * and the screen renders neither until the first emission.
 */
@HiltViewModel
class SavedSourcesViewModel @Inject constructor(
    private val sources: SourceRepository,
) : ViewModel() {

    val state: StateFlow<SavedSourcesState> =
        combine(sources.sources(), sources.active()) { all, active ->
            SavedSourcesState.Ready(saved = all, activeId = active?.id)
        }.stateIn(
            scope = viewModelScope,
            // Not `Eagerly`: the screen is one of four destinations and most sessions
            // never open it. `WhileSubscribed` with the conventional stop timeout keeps
            // the query off the database until something is looking, and holds it
            // across a configuration change rather than restarting it.
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = SavedSourcesState.Loading,
        )

    /**
     * Make this the subscription the app shows.
     *
     * The repository is the only writer, so nothing is optimistically flipped here --
     * the flow above re-emits with the new active id and the tick moves because the
     * store said so, not because this screen assumed it would.
     */
    fun choose(id: String) {
        viewModelScope.launch { sources.setActive(id) }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

/**
 * What the saved-subscriptions screen is showing.
 *
 * Sealed, so the screen renders a `when` over it and a state nobody thought about
 * cannot reach a user as a blank rectangle — the same reason every other screen in
 * this module takes a sealed state.
 */
sealed interface SavedSourcesState {

    /** Before the first emission. Not the same thing as having none. */
    data object Loading : SavedSourcesState

    data class Ready(
        val saved: List<ProviderSource>,
        val activeId: String?,
    ) : SavedSourcesState {
        val isEmpty: Boolean get() = saved.isEmpty()
    }
}
