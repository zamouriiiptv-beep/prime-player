package com.castivio.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.castivio.domain.HiddenItems
import com.castivio.domain.HiddenKind
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The board is choosing what to hide: bouquets, or channels, and which are ticked so far.
 *
 * One kind at a time. Hiding bouquets and hiding channels are two modes that never share a tick: the
 * set is of the kind it was begun for, and a press on a row that is not of that kind is not a tick.
 */
data class Hiding(val kind: HiddenKind, val picked: Set<String> = emptySet())

/**
 * Hiding bouquets and channels from the channel board in bulk -- the choice, the one write that hides
 * the ticked ones, and the way back from Settings.
 *
 * ## Its own mode, apart from reorder mode and from picking favourites
 *
 * Choosing what to hide moves nothing, writes no order and marks no favourite: it is a set of ids waiting
 * for one press. It is held here, and not in the board's holder where the reorder mode is nor in the
 * favourites' holder, so the three modes cannot share a state -- and the strip keeps only one of the
 * three answering at a time.
 *
 * ## Hiding is local, and reversible
 *
 * [HiddenItems] writes the viewer's own rows beside the catalogue, which a refresh of the subscription
 * replaces and these survive. Nothing is deleted and the provider is not told; [restore] deletes the
 * rows, and the items are where they were.
 */
@HiltViewModel
class ChannelHidingViewModel @Inject constructor(
    private val hidden: HiddenItems,
) : ViewModel() {

    private val _hiding = MutableStateFlow<Hiding?>(null)

    /** What is being chosen, or null when the board is not choosing. */
    val hiding: StateFlow<Hiding?> = _hiding

    /** How many bouquets are hidden: what Settings offers to restore. */
    val hiddenBouquets: StateFlow<Int> = hidden.count(HiddenKind.BOUQUET)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(GRACE_MS), 0)

    /** How many channels are hidden by themselves: what Settings offers to restore. */
    val hiddenChannels: StateFlow<Int> = hidden.count(HiddenKind.CHANNEL)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(GRACE_MS), 0)

    /** Start choosing [kind], with nothing ticked. Nothing happens while the board is choosing already. */
    fun begin(kind: HiddenKind) {
        if (_hiding.value == null) _hiding.value = Hiding(kind)
    }

    /** A press on a row while choosing: it is ticked, or -- it was -- put back. */
    fun toggle(id: String) {
        _hiding.update { it?.copy(picked = it.picked.toggled(id)) }
    }

    /** Leave without hiding anything. */
    fun cancel() {
        _hiding.value = null
    }

    /**
     * Hide every ticked item, in one write, and leave the mode. Nothing ticked leaves the mode and
     * writes nothing. If it cannot be written the ticks are kept, so the same press tries again.
     */
    fun confirm() {
        val now = _hiding.value ?: return
        _hiding.value = null
        if (now.picked.isEmpty()) return
        viewModelScope.launch {
            try {
                hidden.hide(now.kind, now.picked)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _hiding.value = _hiding.value ?: now
            }
        }
    }

    /** Settings: bring back every hidden item of [kind]. */
    fun restore(kind: HiddenKind) {
        viewModelScope.launch {
            try {
                hidden.restoreAll(kind)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Still hidden, and the count in Settings says so: the same press tries again.
            }
        }
    }

    private companion object {
        const val GRACE_MS = 5_000L
    }
}
