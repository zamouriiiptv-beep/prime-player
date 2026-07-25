package com.castivio.core.navigation

/**
 * Remembers where the user was on every screen they have visited.
 *
 * On a remote this is not a nicety. Going into a channel and pressing Back should
 * return the cursor to that channel, not to the top of a list of forty thousand —
 * and having to scroll back is the single most common complaint about IPTV players
 * on television.
 *
 * Kept out of the UI layer for two reasons: it is state, not rendering, so it
 * survives configuration changes and screen recreation for free; and it is pure
 * Kotlin, so the rules below are unit-tested rather than trusted.
 *
 * **Bounded.** A user can visit hundreds of categories in one session, so the map
 * is an LRU of [capacity] entries. Memory that grows with browsing is the same bug
 * as memory that grows with library size, one layer up.
 */
class NavigationMemory(private val capacity: Int = DEFAULT_CAPACITY) {

    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    // Access-ordered so the least recently used entry is always first.
    private val positions = LinkedHashMap<String, ScreenPosition>(16, 0.75f, true)

    /**
     * Records where focus was when a screen was left.
     *
     * @param row which row of a multi-row screen; 0 for a single list.
     * @param item index within that row.
     * @param itemKey the item's stable id, which survives the list changing
     *   underneath — an index alone points at a different channel after a refresh.
     */
    fun remember(route: Route, row: Int = 0, item: Int = 0, itemKey: String? = null) {
        positions[route.key] = ScreenPosition(row = row, item = item, itemKey = itemKey)
        trim()
    }

    /** Where focus was, or null for a screen not visited yet. */
    fun recall(route: Route): ScreenPosition? = positions[route.key]

    /**
     * Where focus should go, resolved against the list as it is *now*.
     *
     * The key wins over the index: after a refresh the channel the user was on may
     * have moved, and landing on it is right where landing on its old position is
     * merely close. When the key is gone the index is clamped, so a shortened list
     * lands at its end rather than nowhere.
     */
    fun resolve(route: Route, currentKeys: List<String>): Int {
        val remembered = positions[route.key] ?: return 0
        if (currentKeys.isEmpty()) return 0

        val byKey = remembered.itemKey?.let { currentKeys.indexOf(it) } ?: -1
        if (byKey >= 0) return byKey

        return remembered.item.coerceIn(0, currentKeys.lastIndex)
    }

    /** Forgets one screen — used when a screen's content is replaced wholesale. */
    fun forget(route: Route) {
        positions.remove(route.key)
    }

    /**
     * Forgets everything.
     *
     * Called when the catalogue is replaced: a remembered position in the old
     * library means nothing in the new one, and restoring it would drop the user
     * somewhere arbitrary.
     */
    fun clear() {
        positions.clear()
    }

    val size: Int get() = positions.size

    private fun trim() {
        while (positions.size > capacity) {
            val oldest = positions.keys.firstOrNull() ?: return
            positions.remove(oldest)
        }
    }

    companion object {
        /**
         * Enough for a deep browse — every section, a few dozen categories and the
         * screens between them — without being unbounded.
         */
        const val DEFAULT_CAPACITY = 64
    }
}

data class ScreenPosition(val row: Int, val item: Int, val itemKey: String? = null)
