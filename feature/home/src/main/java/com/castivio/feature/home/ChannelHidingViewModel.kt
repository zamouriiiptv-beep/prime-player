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
 * The board is choosing what to hide, and which bouquets and which channels are ticked so far.
 *
 * Both at once: while it lasts the rail's bouquets and the list's channels carry the same circles, and
 * Hide selected hides every one that is ticked, of either kind. They are kept apart because they are
 * hidden apart -- a bouquet takes its channels with it, a channel only itself.
 */
data class Hiding(val bouquets: Set<String> = emptySet(), val channels: Set<String> = emptySet()) {
    /** Something is ticked, so Hide selected has something to do. */
    val any: Boolean get() = bouquets.isNotEmpty() || channels.isNotEmpty()
}

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

    /** Start choosing, with nothing ticked. Nothing happens while the board is choosing already. */
    fun begin() {
        if (_hiding.value == null) _hiding.value = Hiding()
    }

    /** A press on a bouquet while choosing: it is ticked, or -- it was -- put back. */
    fun toggleBouquet(id: String) {
        _hiding.update { it?.copy(bouquets = it.bouquets.toggled(id)) }
    }

    /** A press on a channel while choosing: it is ticked, or -- it was -- put back. */
    fun toggleChannel(id: String) {
        _hiding.update { it?.copy(channels = it.channels.toggled(id)) }
    }

    /** Leave without hiding anything. */
    fun cancel() {
        _hiding.value = null
    }

    /**
     * Hide every ticked item and leave the mode: the bouquets in one write and the channels in another.
     * Nothing ticked leaves the mode and writes nothing. What cannot be written stays ticked -- only that,
     * a kind already hidden is not ticked again -- so the same press tries again.
     */
    fun confirm() {
        val now = _hiding.value ?: return
        _hiding.value = null
        if (!now.any) return
        viewModelScope.launch {
            var left = now
            try {
                if (left.bouquets.isNotEmpty()) {
                    hidden.hide(HiddenKind.BOUQUET, left.bouquets)
                    left = left.copy(bouquets = emptySet())
                }
                if (left.channels.isNotEmpty()) {
                    hidden.hide(HiddenKind.CHANNEL, left.channels)
                    left = left.copy(channels = emptySet())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _hiding.value = _hiding.value ?: left
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
