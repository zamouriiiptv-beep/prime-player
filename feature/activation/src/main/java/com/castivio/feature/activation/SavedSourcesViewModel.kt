package com.castivio.feature.activation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.castivio.domain.ProviderSource
import com.castivio.domain.ProviderStatusCatalogue
import com.castivio.domain.SourceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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
 * ## The expiry is read, not kept
 *
 * The same rule applied to the one fact the rows need that a source row does not carry.
 * When a subscription runs out is the provider's answer, recorded by
 * [ProviderStatusCatalogue] the moment a validator gets it — at registration and again
 * on every refresh — and Home has been reading it from there since it was written. This
 * screen reads the same record for the same reason there is no second store for the
 * sources themselves: a column of our own would be the provider's answer written twice,
 * and the two would disagree the first time a refresh updated one of them.
 *
 * A source with nothing recorded contributes no entry, and the row then shows no expiry
 * at all. That is the honest rendering of three different facts a date cannot express —
 * a provider never asked, a playlist with no subscription behind it, and a panel that
 * states no end date — and the alternative is putting a number on screen that nothing
 * on the device actually knows.
 *
 * ## What "loading" is for
 *
 * The list arrives asynchronously and an empty list is a real, common answer — a fresh
 * install has no subscriptions. Rendering the empty state during the first frame and
 * the list a moment later is a flash of "you have nothing" at exactly the moment a
 * returning user is looking for their subscriptions, so the two are distinct states
 * and the screen renders neither until the first emission.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SavedSourcesViewModel @Inject constructor(
    private val sources: SourceRepository,
    private val statuses: ProviderStatusCatalogue,
) : ViewModel() {

    val state: StateFlow<SavedSourcesState> =
        combine(sources.sources(), sources.active()) { all, active -> all to active }
            // Switched on the list rather than combined with it: the expiries are one
            // flow per saved subscription, so the set of flows to listen to changes
            // whenever a subscription is added or removed. `flatMapLatest` rebuilds that
            // set and drops the previous one, which is what keeps a deleted
            // subscription's record from outliving its row.
            .flatMapLatest { (all, active) ->
                expiriesOf(all).map { expiries ->
                    SavedSourcesState.Ready(
                        saved = all,
                        activeId = active?.id,
                        expiries = expiries,
                    )
                }
            }
            .stateIn(
                scope = viewModelScope,
                // Not `Eagerly`: the screen is one of four destinations and most
                // sessions never open it. `WhileSubscribed` with the conventional stop
                // timeout keeps the query off the database until something is looking,
                // and holds it across a configuration change rather than restarting it.
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = SavedSourcesState.Loading,
            )

    /**
     * When each of these subscriptions runs out, for the ones a provider has said.
     *
     * An empty list short-circuits because `combine` over no flows never emits at all,
     * and a screen whose list is empty would then sit on [SavedSourcesState.Loading]
     * forever — the one state that says "we do not know yet" when we do know.
     *
     * A source with no record, or a record with no date, is absent from the map rather
     * than present with a zero: absent is a fact the row can draw nothing for, and zero
     * is the first day of 1970 on a television.
     */
    private fun expiriesOf(all: List<ProviderSource>): Flow<Map<String, Long>> {
        if (all.isEmpty()) return flowOf(emptyMap())
        return combine(all.map { statuses.of(it.id) }) { recorded ->
            buildMap<String, Long> {
                recorded.forEachIndexed { index, answer ->
                    val at = answer?.expiresAtMs ?: return@forEachIndexed
                    put(all[index].id, at)
                }
            }
        }
    }

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

    /**
     * Give a subscription a different name, and nothing else.
     *
     * The whole of "edit" on this screen. It reads the row, replaces one field and
     * writes it back rather than taking a [ProviderSource] from the caller: a screen
     * that could hand this a whole record could hand it a changed address or a changed
     * password, and renaming is not a door that should open onto those.
     *
     * A blank name is refused rather than stored. Every subscription has a name from
     * the moment it is registered — the user's, or the first free `Playlist n` — and
     * emptying one would put back the blank rows that naming exists to prevent.
     */
    fun rename(id: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val source = sources.get(id) ?: return@launch
            if (source.label == trimmed) return@launch
            sources.save(source.copy(label = trimmed))
        }
    }

    /**
     * Remove a subscription from this device.
     *
     * **Including the one in use, and nothing is activated in its place.** Which
     * subscription the app shows is the user's choice, and picking one for them at the
     * moment they deleted another is the app making that choice on their behalf — on a
     * screen whose whole purpose is that they make it themselves. What the screen owes
     * them instead is a warning that says plainly what deleting the active one costs,
     * which is why [SavedSourcesState.Ready.activeId] is on screen beside every row.
     */
    fun delete(id: String) {
        viewModelScope.launch { sources.delete(id) }
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
        /**
         * When a subscription runs out, by source id, for the ones that have an answer.
         *
         * A map rather than a field on the row's model because the dates do not come
         * from the same place the rows do — see the view model. Missing is the common
         * case and a legitimate one, so a row looks its own id up and draws nothing when
         * it is not there.
         */
        val expiries: Map<String, Long> = emptyMap(),
    ) : SavedSourcesState {
        val isEmpty: Boolean get() = saved.isEmpty()
    }
}
