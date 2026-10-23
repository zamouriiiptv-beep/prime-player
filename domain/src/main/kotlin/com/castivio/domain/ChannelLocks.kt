package com.castivio.domain

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

/**
 * Which channels are locked, between launches.
 *
 * A locked channel does not play until the password has been typed, and it is locked one channel
 * at a time: locking a channel says nothing about its category, its neighbours or the provider.
 *
 * ## Stored by channel id, and nothing else
 *
 * The same key the favourites use, and for the same reason -- it is the catalogue's own id, so a
 * provider that drops a channel for a week and brings it back under the same id brings it back
 * locked. An address is not a key: it carries the subscriber's credentials and this is written to
 * disc.
 *
 * ## Small by construction
 *
 * One short id for each channel somebody chose to lock. The whole set is held in memory and drawn
 * from there -- a row asking whether it is locked is a set lookup, not a query -- which is only
 * allowed because it is the user's own handful of channels and never the catalogue.
 */
interface ChannelLocks {

    /**
     * The locked channels' ids, or null until the stored set has been read.
     *
     * Null and empty are different answers and the difference is the point of this being nullable:
     * "nothing is locked" is a fact a press may act on, and "not read yet" is not. A press that
     * arrives in the few milliseconds before the file has been read waits for it -- see [isLocked]
     * -- rather than finding the channel open.
     */
    val locked: StateFlow<Set<String>?>

    /** Locks [channelId]. Idempotent, and applied in the order the calls were made. */
    fun lock(channelId: String)

    /** Unlocks [channelId]. Idempotent, and applied in the order the calls were made. */
    fun unlock(channelId: String)
}

/**
 * Whether [channelId] is locked, waiting for the stored set if it has not been read yet.
 *
 * For a caller that can suspend. One that cannot -- a press handler, which has to decide at once
 * whether to start something -- reads [ChannelLocks.locked] and calls [pressVerdictFor] instead.
 */
suspend fun ChannelLocks.isLocked(channelId: String): Boolean =
    channelId in locked.filterNotNull().first()

/** What a press on a channel does about its lock. */
enum class PressVerdict {
    /** Nothing stands in the way: start the channel. */
    Start,

    /** The channel is locked: ask for the password, and start it only when it is given. */
    Ask,

    /** The set of locked channels has not been read yet, so nothing can be said. Wait for it. */
    Wait,
}

/**
 * What a press on [channelId] has to do about its lock, given what is [known] of the locked set.
 *
 * Only a live channel can be locked, so a film, an episode or a press that carries no channel id
 * starts at once and never waits on a file that has nothing to say about it.
 */
fun pressVerdictFor(live: Boolean, channelId: String?, known: Set<String>?): PressVerdict = when {
    !live || channelId == null -> PressVerdict.Start
    known == null -> PressVerdict.Wait
    channelId in known -> PressVerdict.Ask
    else -> PressVerdict.Start
}
