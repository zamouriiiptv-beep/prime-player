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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
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
 * These stored favourites as the board has just made them: each of [changes] -- a channel to whether it
 * now is a favourite -- applied on top.
 */
internal fun Set<String>.withChanges(changes: Map<String, Boolean>): Set<String> {
    if (changes.isEmpty()) return this
    val added = changes.filterValues { it }.keys
    val removed = changes.filterValues { !it }.keys
    return this - removed + added
}

/** The [changes] the stored favourites do not show yet: one they now agree with is settled, and goes. */
internal fun Map<String, Boolean>.unsettledBy(stored: Set<String>): Map<String, Boolean> =
    filterNot { (id, favorite) -> (id in stored) == favorite }

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
     * What this board has just marked or unmarked and the stored favourites do not show yet: a channel
     * to whether it now is a favourite. Each entry goes when the store agrees with it, or when the write
     * that made it fails.
     */
    private val ahead = MutableStateFlow<Map<String, Boolean>>(emptyMap())

    /**
     * Every favourite's id, as it changes -- the viewer's own handful, held whole so a row asks whether
     * it is one by a set lookup.
     *
     * **At once, not when the store gets round to saying so.** The rows' stars and the button were two
     * reads of the same table, and on a device the stars came in up to two minutes after the button had
     * lit. So a mark made here is shown from the press, over the stored set, and the store's own answer
     * takes over as soon as it has one -- the button and the stars are both drawn from this.
     */
    val ids: StateFlow<Set<String>> = combine(
        favorites.ids().onEach { stored -> ahead.update { it.unsettledBy(stored) } },
        ahead,
    ) { stored, changes -> stored.withChanges(changes) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(GRACE_MS), emptySet())

    /**
     * The Favourite button pressed for the channel in the picture: it is marked, or -- it was one --
     * the mark is taken away. Shown at once; written behind it, and taken back if it cannot be.
     */
    fun flip(channelId: String) {
        mark(setOf(channelId), favorite = channelId !in ids.value)
    }

    /** [channelIds] made favourites, or not: shown at once, written once, and undone on screen if it fails. */
    private fun mark(channelIds: Set<String>, favorite: Boolean, onFailure: () -> Unit = {}) {
        ahead.update { it + channelIds.associateWith { favorite } }
        viewModelScope.launch {
            try {
                favorites.set(channelIds, favorite)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ahead.update { it - channelIds }
                onFailure()
            }
        }
    }

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
        // Against what the board shows, which is what the button's word said it would do.
        val remove = picksRemove(picked, ids.value)
        mark(picked, favorite = !remove, onFailure = { _picked.value = _picked.value ?: picked })
    }

    private companion object {
        /** Long enough that a trip to the player and back finds the picks and the set as they were. */
        const val GRACE_MS = 5_000L
    }
}
