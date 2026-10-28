package com.castivio.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.castivio.domain.CatalogQuery
import com.castivio.domain.CatalogRepository
import com.castivio.domain.Channel
import com.castivio.domain.ChannelWindows
import com.castivio.domain.MediaItem
import com.castivio.domain.MediaKind
import com.castivio.domain.WindowedRows
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What the list of channels in the player knows: whether its first window has arrived, how long the list
 * is, where the playing channel stands in it -- the row it opens on -- and a counter that moves each time
 * a window arrives, so a screen that reads rows by index reads them again.
 */
data class ChannelListState(
    val ready: Boolean = false,
    val total: Int = 0,
    val start: Int = 0,
    val version: Int = 0,
)

/**
 * The channels the large player lists, a window at a time, in the order the board shows them.
 *
 * ## Which list
 *
 * The one the channel was pressed in -- the bouquet that was open, with the search that narrowed it --
 * and, when it was pressed in none or is not in it any more, its own bouquet: the rule the channel keys
 * step by (see [ChannelZapViewModel.step]), so the list and the keys can never disagree about what is
 * next. A channel that has left the catalogue has no list at all, and the state says the list is empty.
 *
 * ## How much is held
 *
 * [WindowedRows]: sixty rows a window, five windows at most. The list opens scrolled to the playing
 * channel's own window, which is read first, and a window is read when a row in it is asked for or the
 * viewer comes within [PREFETCH] rows of its edge, so scrolling a bouquet of thousands never waits on a
 * row it was about to reach.
 *
 * Held by the activity like the board's own holders, and re-opened by key: opening the list on the same
 * channel in the same list is a no-op, so a recomposition costs nothing.
 */
@HiltViewModel
class ChannelListViewModel @Inject constructor(
    private val windows: ChannelWindows,
    private val catalog: CatalogRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ChannelListState())
    val state: StateFlow<ChannelListState> = _state.asStateFlow()

    private var rows: WindowedRows<MediaItem>? = null
    private var opened: Pair<CatalogQuery?, String>? = null
    private var job: Job? = null

    /** Read the list [list] holds -- or the channel's own bouquet -- starting at [channelId]'s window. */
    fun open(list: CatalogQuery?, channelId: String) {
        val key = list to channelId
        if (opened == key) return
        opened = key
        job?.cancel()
        _state.value = ChannelListState()
        rows = null
        job = viewModelScope.launch {
            val (query, rank) = resolve(list, channelId) ?: run {
                // A channel the catalogue no longer has: nothing to list, and it is said as nothing -- and asked
                // again the next time the list is opened, which may be after a refresh brought it back.
                _state.value = ChannelListState(ready = true)
                opened = null
                return@launch
            }
            val windowed = WindowedRows { offset, limit -> windows.window(query, offset, limit) }
            val start = rank ?: 0
            // A window that cannot be read is a list with nothing in it to show, and not a crash: the
            // reads are the database's, and a failed one is the viewer's list closing, not the app.
            if (attempt { windowed.ensure(start) } == null) {
                _state.value = ChannelListState(ready = true)
                opened = null
                return@launch
            }
            rows = windowed
            _state.update { it.copy(ready = true, total = windowed.total ?: 0, start = start, version = it.version + 1) }
        }
    }

    /** The channel at [index] if its window is held. Read in composition: a lookup, never a read. */
    fun channelAt(index: Int): Channel? = rows?.rowAt(index) as? Channel

    /**
     * Something is looking at [index]: read its window, and the next one or the one before when it is
     * within [PREFETCH] rows of that edge.
     */
    fun want(index: Int) {
        val held = rows ?: return
        viewModelScope.launch {
            var arrived = false
            // A read that fails leaves its rows as placeholders and is asked for again the next time a
            // row in the window is looked at.
            for (at in intArrayOf(index, index + PREFETCH, index - PREFETCH)) {
                attempt { held.ensure(at) }?.let { if (it) arrived = true }
            }
            if (arrived) _state.update { it.copy(total = held.total ?: it.total, version = it.version + 1) }
        }
    }

    /** The result of [block], or null when it failed -- a cancellation is not a failure and is let through. */
    private suspend fun <T> attempt(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /**
     * The list and the channel's place in it: the list it was pressed in when the channel is there, else
     * the channel's own bouquet -- where it may not be either, which opens the list at its top.
     */
    private suspend fun resolve(list: CatalogQuery?, channelId: String): Pair<CatalogQuery, Int?>? {
        if (list != null) windows.rank(list, channelId)?.let { return list to it }
        val channel = catalog.item(channelId) as? Channel ?: return null
        val own = CatalogQuery(kind = MediaKind.LIVE, groupId = channel.groupId)
        return own to windows.rank(own, channelId)
    }

    companion object {
        /** How near a window's edge a row must be for the next window to be read before it is needed. */
        const val PREFETCH = 12
    }
}
