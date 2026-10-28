package com.castivio.domain

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A long list read a window at a time, with a ceiling on how many rows are ever held.
 *
 * ## Why this exists
 *
 * A bouquet can hold thousands of channels, and the list a screen draws over it must not hold them all:
 * the rule of the product is a window of [windowSize] rows, a few of them kept, and at most
 * `windowSize * maxWindows` -- 300 by default -- in memory at once. A window is read when a row in it is
 * asked for, and the least recently used is let go when there are more than [maxWindows].
 *
 * It holds no policy about *which* window to read next: the screen says what it is looking at -- [ensure]
 * with a row's index -- and this keeps the answer. The reads are [ChannelWindows.window]'s, so the order of
 * the rows is the channel list's own and there is no second copy of it to drift.
 *
 * Used from one thread, the view model's: a window arrives on the caller's thread and a row is asked for
 * on the same one, so nothing here is locked except the reading itself, which two asks for the same window
 * must not do twice.
 */
class WindowedRows<T : Any>(
    private val windowSize: Int = PageRequest.DEFAULT_PAGE,
    private val maxWindows: Int = MAX_WINDOWS,
    private val read: suspend (offset: Int, limit: Int) -> Page<T>,
) {
    init {
        require(windowSize in 1..PageRequest.MAX_PAGE) { "window size $windowSize outside 1..${PageRequest.MAX_PAGE}" }
        require(maxWindows >= 1) { "a list holds at least one window" }
    }

    /** Window index to its rows, the least recently used first: reading one moves it to the end. */
    private val held = LinkedHashMap<Int, List<T>>(INITIAL_CAPACITY, LOAD_FACTOR, true)
    private val reading = Mutex()

    /** How many rows the list has, once a window has said so; null before the first one arrives. */
    var total: Int? = null
        private set

    /** How many rows are held right now. Never more than `windowSize * maxWindows`. */
    val rowsHeld: Int get() = held.values.sumOf { it.size }

    /** The row at [index] if its window is held, and null if it is not -- or is past the end of the list. */
    fun rowAt(index: Int): T? {
        if (index < 0) return null
        val window = index / windowSize
        return held[window]?.getOrNull(index - window * windowSize)
    }

    /**
     * Read the window [index] is in, unless it is held already. True when a window arrived, which is when
     * a screen drawing rows has something new to draw.
     *
     * An index past the end of a list whose length is known reads nothing, and one before the start never
     * does: asking for a row that is not there is not a reason to ask the database.
     */
    suspend fun ensure(index: Int): Boolean {
        if (index < 0) return false
        total?.let { if (index >= it) return false }
        val window = index / windowSize
        if (held.containsKey(window)) {
            // A read of the map's own order: the window is the most recently used now.
            held[window]
            return false
        }
        val page = reading.withLock {
            if (held.containsKey(window)) null else read(window * windowSize, windowSize)
        } ?: return false
        total = page.totalCount
        held[window] = page.items
        trim(keep = window)
        return true
    }

    /** Let go of the least recently used windows until the ceiling holds, never the one just read. */
    private fun trim(keep: Int) {
        val iterator = held.keys.iterator()
        while (held.size > maxWindows && iterator.hasNext()) {
            if (iterator.next() != keep) iterator.remove()
        }
    }

    companion object {
        /** Five windows of sixty: the 300 rows the product allows a list to hold. */
        const val MAX_WINDOWS = 5
        private const val INITIAL_CAPACITY = 8
        private const val LOAD_FACTOR = 0.75f
    }
}
