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
import com.castivio.domain.time.TrustedTime
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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

    /** One channel's programmes, over its list: the screen [CatchUpOverlay] draws. */
    data class Programmes(val group: ArchiveGroup?, val channel: Channel) : HubStage
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

    /** Bumped when a channel is found unavailable, so the list is read again without it. */
    private val reading = MutableStateFlow(0)

    /**
     * The channels of the category that is open, with the unavailable ones left out.
     *
     * Keyed to the **category**, not to the stage: opening a channel's programmes over the list does not read the list
     * again, and Back lands on the same rows.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val channels: Flow<PagingData<Channel>> = combine(
        _stage.map { stage ->
            when (stage) {
                HubStage.Groups -> null
                is HubStage.Channels -> ListKey(stage.group?.id)
                is HubStage.Programmes -> ListKey(stage.group?.id)
            }
        }.distinctUntilChanged(),
        reading,
    ) { key, _ -> key }
        .flatMapLatest { key ->
            if (key == null) {
                emptyFlow()
            } else {
                pager.items(CatalogQuery(kind = MediaKind.LIVE, groupId = key.groupId, archive = true))
                    .map { data ->
                        data.filter { it is Channel && !gateway.knownEmpty(it.id, clock.nowMs()) }
                            .map { it as Channel }
                    }
            }
        }
        .cachedIn(viewModelScope)

    fun openGroup(group: ArchiveGroup?) {
        _stage.value = HubStage.Channels(group)
    }

    fun openChannel(channel: Channel) {
        val group = (_stage.value as? HubStage.Channels)?.group
        _stage.value = HubStage.Programmes(group, channel)
    }

    /** One step back. False at the first page: there is nowhere inside Catch-up to go, and the caller leaves it. */
    fun back(): Boolean {
        when (val before = _stage.value) {
            HubStage.Groups -> return false
            is HubStage.Channels -> _stage.value = HubStage.Groups
            is HubStage.Programmes -> _stage.value = HubStage.Channels(before.group)
        }
        return true
    }

    /** The viewer left Catch-up: the next time begins at the categories. */
    fun reset() {
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

    private data class ListKey(val groupId: String?)

    private companion object {
        /** How long the categories are kept after the page is left, for a quick return. */
        const val STOP_MS = 5_000L
    }
}
