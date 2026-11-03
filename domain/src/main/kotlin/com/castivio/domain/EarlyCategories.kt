package com.castivio.domain

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel

/**
 * The categories a viewer asked for while their section was still arriving, handed from the screen to the load that
 * is running.
 *
 * A section's first load brings every category in one long reply, in the provider's order, and a category the reply
 * has not reached yet cannot be opened -- however far down the list it is. A viewer who presses one anyway is asking
 * for it *now*, and the load answers by asking the provider for that one category on its own, beside the long reply,
 * and writing it ahead of the rest. The long reply brings it again later, under the same ids, and replaces it.
 *
 * ## Two sides
 *
 * The screen calls [ask]. The load calls [open] when it starts, takes the ids from what it returned, and calls
 * [close] when it ends. Nothing else is shared between them, which is what lets the screen stay ignorant of how a
 * provider is asked and the import stay ignorant of what a screen is.
 *
 * Pure Kotlin, like everything in `:domain`.
 */
interface EarlyCategories {

    /**
     * Category [groupId] of [kind] is wanted now.
     *
     * Never blocks and never fails. Does nothing unless a load of [kind] is running and is taking asks -- a load that
     * has ended, a section that is not loading and a section that is loaded whole all have nothing to do with it.
     */
    fun ask(kind: MediaKind, groupId: String)

    /**
     * A load of [kind] has begun and takes asks: the ids, in the order they were made, until [close].
     *
     * A load that began before this one and never closed is replaced; its channel is closed.
     */
    fun open(kind: MediaKind): ReceiveChannel<String>

    /**
     * The load that was given [asks] has ended. Asks made after this are dropped, and [asks] is closed.
     *
     * Told which channel, and not only which kind, so a load that ends late cannot close the channel of the one that
     * replaced it.
     */
    fun close(kind: MediaKind, asks: ReceiveChannel<String>)

    /** Nobody asks and nobody is asked: for a screen or an importer that is not given the real one. */
    object None : EarlyCategories {
        override fun ask(kind: MediaKind, groupId: String) = Unit

        override fun open(kind: MediaKind): ReceiveChannel<String> = Channel<String>(Channel.RENDEZVOUS).also { it.close() }

        override fun close(kind: MediaKind, asks: ReceiveChannel<String>) = Unit
    }
}
