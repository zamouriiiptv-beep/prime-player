package com.castivio.domain

/**
 * What one request for a show's episodes or a film's details cost, in milliseconds, so a debuggable build can say
 * where a wait went instead of leaving it to be guessed from a recording.
 *
 * [openMs] is the catalogue made ready to take the reply, [askMs] the wait until the provider began to answer, [readMs]
 * its answer arriving and being read, and [saveMs] the reply being kept. [tries] is how many times the provider had
 * been asked when this answer came: the first answer of a provider that has not built a show yet is often empty, and
 * the one after it is not.
 */
data class RequestTimes(
    val askMs: Long,
    val readMs: Long,
    val saveMs: Long = 0,
    val openMs: Long = 0,
    val tries: Int = 1,
) {
    /** One short line for a tester to read off a screen: `ask 6.1 s · read 0.4 s · save 0.2 s · try 1`. */
    fun line(): String = buildString {
        if (openMs >= SHOWN_FROM_MS) append("open ").append(seconds(openMs)).append(" · ")
        append("ask ").append(seconds(askMs))
        append(" · read ").append(seconds(readMs))
        if (saveMs > 0) append(" · save ").append(seconds(saveMs))
        append(" · try ").append(tries)
    }

    private fun seconds(ms: Long): String = "${ms / MS_PER_SECOND}.${ms % MS_PER_SECOND / TENTH_MS} s"

    private companion object {
        const val MS_PER_SECOND = 1_000L
        const val TENTH_MS = 100L

        /** The catalogue being made ready is not worth a word until it is a tenth of a second. */
        const val SHOWN_FROM_MS = 100L
    }
}
