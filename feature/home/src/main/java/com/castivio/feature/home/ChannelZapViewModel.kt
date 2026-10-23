package com.castivio.feature.home

import androidx.lifecycle.ViewModel
import com.castivio.domain.CatalogQuery
import com.castivio.domain.CatalogRepository
import com.castivio.domain.Channel
import com.castivio.domain.ChannelWindows
import com.castivio.domain.MediaKind
import com.castivio.domain.SteppedChannel
import com.castivio.domain.step
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * The channel after the one on screen, and the one before it -- for the screen that has no channel
 * list of its own to read them from: the full-screen player.
 *
 * A holder in front of two contracts, deciding one thing: which list the step is made in. The step
 * itself is [step]'s, and the order is the channel list's own -- see [ChannelWindows].
 */
@HiltViewModel
class ChannelZapViewModel @Inject constructor(
    private val windows: ChannelWindows,
    private val catalog: CatalogRepository,
) : ViewModel() {

    /**
     * The channel [by] places from [channelId] -- 1 for the next, -1 for the one before -- going round.
     *
     * In [list] when the channel was pressed in one: the bouquet that was open, with the search that
     * narrowed it. Otherwise, or when the channel is not in it any more, in its own bouquet; and a
     * channel the provider put in none is stepped through the whole section. Null when there is
     * nowhere to go: a list of one, or a channel that is no longer in the catalogue.
     */
    suspend fun step(list: CatalogQuery?, channelId: String, by: Int): SteppedChannel? {
        if (list != null) windows.step(list, channelId, by)?.let { return it }
        val channel = catalog.item(channelId) as? Channel ?: return null
        val own = CatalogQuery(kind = MediaKind.LIVE, groupId = channel.groupId)
        return if (own == list) null else windows.step(own, channelId, by)
    }
}
