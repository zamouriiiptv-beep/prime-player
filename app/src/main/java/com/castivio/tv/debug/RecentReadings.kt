package com.castivio.tv.debug

/**
 * The last few loads' readings, oldest first, as one text.
 *
 * A tester runs loads in a row -- a first load, a reload after Refresh, another -- and pastes
 * when they are done, so what is kept is the last [capacity] rather than the last one: a
 * reading is not lost because the next load ended before it was pasted. Bounded, because a
 * session can run a hundred loads and the clipboard is not an archive.
 *
 * Plain Kotlin, so what the clipboard is given is a unit test.
 */
internal class RecentReadings(private val capacity: Int) {

    init {
        require(capacity > 0) { "capacity $capacity" }
    }

    private val kept = ArrayDeque<List<String>>(capacity)

    /** [lines] kept, the oldest dropped past [capacity], and everything kept as one text. */
    @Synchronized
    fun add(lines: List<String>): String {
        kept.addLast(lines)
        while (kept.size > capacity) kept.removeFirst()
        return kept.joinToString(separator = BETWEEN_READINGS) { it.joinToString(separator = "\n") }
    }

    private companion object {
        /** A blank line between two readings, so each starts where somebody can see it start. */
        const val BETWEEN_READINGS = "\n\n"
    }
}
