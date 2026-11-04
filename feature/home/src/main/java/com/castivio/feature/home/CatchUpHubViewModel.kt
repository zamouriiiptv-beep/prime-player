package com.castivio.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.filter
import androidx.paging.map
import com.castivio.domain.ArchiveGroup
import com.castivio.domain.CatalogPager
import com.castivio.domain.CatalogQuery
import com.castivio.domain.CatalogRepository
import com.castivio.domain.CatchUpGateway
import com.castivio.domain.Channel
import com.castivio.domain.MediaKind
import com.castivio.domain.searchTermOf
import com.castivio.domain.time.TrustedTime
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/**
 * Where the viewer is in Catch-up. Three places, one inside the next, and Back leaves them in the opposite order.
 */
sealed interface HubStage {

    /** The categories that keep an archive, and "all". */
    data object Groups : HubStage

    /** One category's channels with an archive; a null [group] is all of them. */
    data class Channels(val group: ArchiveGroup?) : HubStage

    /**
     * One channel's programmes, over its list: the screen [CatchUpOverlay] draws.
     *
     * [fromGroups] is a channel found by the search on the first page, which has no category open: Back returns to that
     * page, and its results, and not to a list of "all" it was never in.
     */
    data class Programmes(val group: ArchiveGroup?, val channel: Channel, val fromGroups: Boolean = false) : HubStage
}

/** What the first page lists: the categories that hold a channel with an archive, and how many such channels there are. */
data class HubGroups(val groups: List<ArchiveGroup>) {
    val total: Int get() = groups.sumOf { it.channels }
}

/**
 * Catch-up as a place of its own: the categories that keep an archive, a category's channels, and -- through
 * [CatchUpOverlay] -- a channel's programmes.
 *
 * ## What it reads, and how much
 *
 * The categories are one aggregate over the live rows, with their counts: hundreds of rows at most, read whole like the
 * rail's. The channels are the catalogue's own pages -- [CatalogQuery.archive] -- a window at a time, so "all" of a
 * subscription that has hundreds of them is read the way every other list is, and never whole. Nothing here asks a
 * provider for anything: that is [CatchUpViewModel]'s, and only when a channel's programmes are opened.
 *
 * ## A channel the provider claims and does not keep
 *
 * A provider can mark a channel as keeping an archive and hold no recording of it: the player finds that out when it opens a
 * programme, and says so ([unavailable]). The channel is then left out of the list for as long as the gateway remembers an
 * empty listing, and the list is read again at once, so the viewer who comes back from the failed programme finds the
 * channel gone and not a second way to fail the same way.
 *
 * ## The search
 *
 * One box, in every place there is a list. On the first page it looks for categories and channels together -- the
 * categories by their names, in memory, and the channels through the index, a window at a time like every list; inside a
 * category it looks among that category's channels. Typing is the search: the words are folded, held back until the viewer
 * pauses and a superseded question is cancelled, as the channels' own are. Back closes it before it leaves the page.
 *
 * One instance for the activity, as the other screens' are: what the player reports reaches the list that is open.
 */
@HiltViewModel
class CatchUpHubViewModel @Inject constructor(
    catalog: CatalogRepository,
    private val pager: CatalogPager,
    private val gateway: CatchUpGateway,
    private val clock: TrustedTime,
) : ViewModel() {

    private val _stage = MutableStateFlow<HubStage>(HubStage.Groups)
    val stage: StateFlow<HubStage> = _stage.asStateFlow()

    /** Null until the first answer: the page says it is loading, and a null is not an empty answer. */
    val groups: StateFlow<HubGroups?> = catalog.archiveGroups()
        .map { HubGroups(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), null)

    /** What the search box holds, exactly as typed: read straight off the flow, so the field never lags the keys. */
    private val typed = MutableStateFlow("")

    val searchText: StateFlow<String> = typed.asStateFlow()

    /** What the lists are narrowed by: [typed], folded, held back until the viewer pauses; empty is no search. */
    @OptIn(FlowPreview::class)
    private val term: Flow<String> = typed
        .map(::searchTermOf)
        .distinctUntilChanged()
        .debounce { if (it.isEmpty()) 0L else SEARCH_DEBOUNCE_MS }
        .distinctUntilChanged()

    /**
     * The categories the search matches, or null when nothing is searched -- or the categories have not arrived, which the
     * first page says by itself. An empty list is an answer: the search found no category.
     */
    val matches: StateFlow<List<ArchiveGroup>?> = combine(groups, term) { all, words ->
        if (all == null || words.isEmpty()) null else matchingGroups(all.groups, words)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), null)

    /** Bumped when a channel is found unavailable, so the list is read again without it. */
    private val reading = MutableStateFlow(0)

    /**
     * The channels of the category that is open, with the unavailable ones left out.
     *
     * Keyed to the **category and the search**, not to the stage: opening a channel's programmes over the list does not read
     * the list again, and Back lands on the same rows.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val channels: Flow<PagingData<Channel>> = combine(
        combine(_stage, term) { stage, words -> keyOf(stage, words) }.distinctUntilChanged(),
        reading,
    ) { key, _ -> key }
        .flatMapLatest { key ->
            if (key == null) {
                emptyFlow()
            } else {
                pager.items(
                    CatalogQuery(kind = MediaKind.LIVE, groupId = key.groupId, search = key.term, archive = true),
                ).map { data ->
                    data.filter { it is Channel && !gateway.knownEmpty(it.id, clock.nowMs()) }
                        .map { it as Channel }
                }
            }
        }
        .cachedIn(viewModelScope)

    /**
     * The list a place and a search read, or null for the places that read none.
     *
     * The first page reads channels only for a search, across all categories; a category reads its own, narrowed by the
     * search when there is one. A programme list over either reads what it is over, so Back finds the same rows.
     */
    private fun keyOf(stage: HubStage, words: String): ListKey? = when (stage) {
        HubStage.Groups -> if (words.isEmpty()) null else ListKey(null, words)
        is HubStage.Channels -> ListKey(stage.group?.id, words)
        is HubStage.Programmes ->
            if (stage.fromGroups) keyOf(HubStage.Groups, words) else ListKey(stage.group?.id, words)
    }

    /** A category's channels. What was typed goes: a category opened from search results is another place from that page. */
    fun openGroup(group: ArchiveGroup?) {
        typed.value = ""
        _stage.value = HubStage.Channels(group)
    }

    fun openChannel(channel: Channel) {
        _stage.value = when (val at = _stage.value) {
            HubStage.Groups -> HubStage.Programmes(null, channel, fromGroups = true)
            is HubStage.Channels -> HubStage.Programmes(at.group, channel)
            is HubStage.Programmes -> return
        }
    }

    /** The viewer typed in the search box. */
    fun search(text: String) {
        typed.value = text
    }

    /**
     * One step back. A search that holds something is closed first, in the place it was typed; then the places in the
     * opposite order of how they were opened. False at the first page with nothing typed: there is nowhere inside Catch-up
     * to go, and the caller leaves it.
     */
    fun back(): Boolean {
        val at = _stage.value
        if (at !is HubStage.Programmes && typed.value.isNotEmpty()) {
            typed.value = ""
            return true
        }
        when (at) {
            HubStage.Groups -> return false
            is HubStage.Channels -> _stage.value = HubStage.Groups
            is HubStage.Programmes ->
                _stage.value = if (at.fromGroups) HubStage.Groups else HubStage.Channels(at.group)
        }
        return true
    }

    /** The viewer left Catch-up: the next time begins at the categories, with nothing typed. */
    fun reset() {
        typed.value = ""
        _stage.value = HubStage.Groups
    }

    /**
     * The player opened a programme of [mediaId] and the provider has none: the channel leaves the list.
     *
     * Told by whoever hosts the player, because the player is not in this module. Memory only, and the list is read again.
     */
    fun unavailable(mediaId: String) {
        gateway.markUnavailable(mediaId, clock.nowMs())
        reading.update { it + 1 }
    }

    private data class ListKey(val groupId: String?, val term: String)

    private companion object {
        /** How long the categories are kept after the page is left, for a quick return. */
        const val STOP_MS = 5_000L

        /** How long the search waits for the viewer to pause: the channels' board's own. */
        const val SEARCH_DEBOUNCE_MS = 120L
    }
}
