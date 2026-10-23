package com.castivio.domain

import kotlinx.coroutines.flow.StateFlow

/**
 * What each channel has been measured at, between launches.
 *
 * ## Why this exists at all
 *
 * A stream's quality is a property of the decoded picture, so the catalogue cannot hold it and
 * a list of fifty thousand channels cannot be asked. What can be known is what has been watched:
 * a channel that played told the decoder's size to the board, and this is where the board keeps
 * it. A channel nobody has opened has no entry, and a row with no entry draws no badge -- which
 * is the whole of the rule. Nothing here is a default, a guess or a reading of a name.
 *
 * Keyed by the channel's id, not its address: the address carries the subscriber's credentials
 * and this is written to disc.
 */
interface ChannelQualities {

    /**
     * Every measurement made so far, by channel id.
     *
     * A flow because a row is on screen while its channel is first measured, and the badge has to
     * appear on that row rather than the next time the list is drawn. Small by construction: one
     * short entry per channel ever watched.
     */
    val known: StateFlow<Map<String, StreamQuality>>

    /** Notes that [channelId] was measured at [quality]. The latest measurement wins. */
    fun record(channelId: String, quality: StreamQuality)
}
