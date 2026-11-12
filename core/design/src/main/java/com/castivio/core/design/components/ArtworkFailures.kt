package com.castivio.core.design.components

/**
 * The addresses whose pictures did not come, for as long as it is worth believing it.
 *
 * ## Why a failure is kept
 *
 * A list recycles its rows, so a channel whose logo failed is composed again every time it is scrolled back to, and the image loader
 * does not remember a failure: each return was another request to the same address, and another wait for it to fail. Kept here, an
 * address that failed is not asked for again for [rememberedMs], and its slot draws the tile at once.
 *
 * ## What it is and is not
 *
 * Not a cache of the picture, which the loader keeps on disk and in memory, and not a ban: it forgets by itself, and an address is asked
 * for again when its time is up. Bounded by [capacity] and by use -- the address least recently asked about is the one let go -- so it is
 * the same size for a library of fifty thousand logos as for fifty.
 *
 * Synchronised, because a list that prefetches may compose off the frame thread, and an access-ordered map changes when it is read.
 *
 * @param rememberedMs how long a failure is believed; see [ArtworkPolicy.FAILURE_MEMORY_MS].
 * @param capacity how many addresses are kept; see [ArtworkPolicy.FAILURES_KEPT].
 * @param monotonicMs a clock that never moves backwards, as the rest of the application keeps its intervals: the wall clock is moved by
 *   the network in the minute a first load runs.
 */
class ArtworkFailures(
    private val rememberedMs: Long = ArtworkPolicy.FAILURE_MEMORY_MS,
    private val capacity: Int = ArtworkPolicy.FAILURES_KEPT,
    private val monotonicMs: () -> Long = { System.nanoTime() / NANOS_PER_MS },
) {

    init {
        require(rememberedMs > 0) { "a memory of no time remembers nothing" }
        require(capacity > 0) { "a memory of no addresses remembers nothing" }
    }

    /** When each address failed, the one asked about longest ago first. */
    private val failedAt = object : LinkedHashMap<String, Long>(INITIAL, LOAD_FACTOR, /* accessOrder = */ true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean = size > capacity
    }

    /** [address] has just failed: it is believed to have for [rememberedMs] from now, whether or not it was believed already. */
    @Synchronized
    fun record(address: String) {
        failedAt[address] = monotonicMs()
    }

    /**
     * Whether [address] failed less than [rememberedMs] ago. If so it is not asked for, and its tile is drawn at once; if not -- it was
     * never recorded, or its time is up, in which case it is forgotten here -- it is asked for as any other.
     */
    @Synchronized
    fun isRemembered(address: String): Boolean {
        val at = failedAt[address] ?: return false
        if (monotonicMs() - at >= rememberedMs) {
            failedAt.remove(address)
            return false
        }
        return true
    }

    private companion object {
        const val NANOS_PER_MS = 1_000_000L
        const val INITIAL = 16
        const val LOAD_FACTOR = 0.75f
    }
}
