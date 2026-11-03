package com.castivio.data.playlist

import com.castivio.domain.EarlyCategories
import com.castivio.domain.MediaKind
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel

/**
 * Where a viewer's ask for a category meets the load that is running: one channel of ids for each kind of section
 * that is loading, opened by the load and closed by it.
 *
 * Nothing is kept for a section that is not loading -- an ask for one finds no channel and is dropped -- and nothing
 * outlives the load that opened it. The screen's side never blocks: `trySend` on an unlimited channel returns at
 * once, from the main thread, and the load's side takes the ids on a thread of its own.
 */
class EarlyCategoryHub : EarlyCategories {

    private val channels = ConcurrentHashMap<MediaKind, Channel<String>>()

    override fun ask(kind: MediaKind, groupId: String) {
        channels[kind]?.trySend(groupId)
    }

    override fun open(kind: MediaKind): ReceiveChannel<String> {
        val asks = Channel<String>(Channel.UNLIMITED)
        channels.put(kind, asks)?.close()
        return asks
    }

    override fun close(kind: MediaKind, asks: ReceiveChannel<String>) {
        // Only the channel that was handed to this load: one that replaced it stays open.
        channels.remove(kind, asks)
        (asks as? Channel<String>)?.close()
    }
}
