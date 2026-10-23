package com.castivio.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.castivio.domain.FavoritesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What the Favourite button does with the channels that were picked: take them away from the
 * favourites when every one of them already is one, and otherwise add them.
 *
 * One rule for the button's word and for its act, so what it says is what it does. An empty pick
 * is neither: there is nothing to add and nothing to remove.
 */
internal fun picksRemove(picked: Set<String>, favorites: Set<String>): Boolean =
    picked.isNotEmpty() && favorites.containsAll(picked)

/** [id] picked, or -- it already was -- put back. */
internal fun Set<String>.toggled(id: String): Set<String> = if (id in this) this - id else this + id

/**
 * Managing the favourites of the channel board in bulk: which channels are picked, which are
 * favourites, and the one act that adds or removes the picked ones.
 *
 * ## Its own mode, apart from the board's reorder mode
 *
 * Picking is a state of the board and not of its list: it moves nothing, writes no order, and touches
 * neither the playing channel nor the provider's numbering. It is held here and not in the board's
 * holder so the two modes cannot share a state -- a press that picks a channel and a press that picks
 * one up to move it are different acts in different holders, and the strip keeps only one of the two
 * buttons answering at a time.
 *
 * ## The storage is the favourites' own
 *
 * [FavoritesRepository], the table the single star has always written: by channel id, kept through a
 * refresh of the subscription and read back whenever the app starts. Nothing parallel is stored; the
 * picks are in memory and are only ever a selection waiting for one press.
 */
@HiltViewModel
class ChannelFavoritesViewModel @Inject constructor(
    private val favorites: FavoritesRepository,
) : ViewModel() {

    /**
     * Every favourite's id, as it changes -- the viewer's own handful, held whole so a row asks whether
     * it is one by a set lookup.
     */
    val ids: StateFlow<Set<String>> = favorites.ids()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(GRACE_MS), emptySet())

    private val _picked = MutableStateFlow<Set<String>?>(null)

    /** The channels picked so far, or null when the board is not picking. */
    val picked: StateFlow<Set<String>?> = _picked

    /**
     * Start picking, with nothing picked: the Favourite button held. One channel is the button pressed,
     * and does not come here; this is for several. Nothing happens when the board is picking already.
     */
    fun begin() {
        if (_picked.value == null) _picked.value = emptySet()
    }

    /** A press on a row while picking: the channel is picked, or put back. */
    fun toggle(channelId: String) {
        _picked.update { it?.toggled(channelId) }
    }

    /** Leave without changing anything. */
    fun cancel() {
        _picked.value = null
    }

    /**
     * Add the picked channels to the favourites -- or, when every one of them already is one, remove
     * them -- and leave the mode. Written in one act; if it cannot be, the picks are kept so the same
     * press tries again.
     */
    fun apply() {
        val picked = _picked.value ?: return
        _picked.value = null
        if (picked.isEmpty()) return
        viewModelScope.launch {
            try {
                val remove = picksRemove(picked, favorites.ids().first())
                favorites.set(picked, favorite = !remove)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _picked.value = _picked.value ?: picked
            }
        }
    }

    private companion object {
        /** Long enough that a trip to the player and back finds the picks and the set as they were. */
        const val GRACE_MS = 5_000L
    }
}
