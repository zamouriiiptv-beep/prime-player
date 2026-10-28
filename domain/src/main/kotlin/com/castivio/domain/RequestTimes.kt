package com.castivio.domain

/**
 * What one request for a show's episodes or a film's details cost, in milliseconds, so a debuggable build can say
 * where a wait went instead of leaving it to be guessed from a recording.
 *
 * In the order it is paid: [lookupMs] the show's row and its provider's account read from the catalogue, [askMs] the
 * wait until the provider began to answer, [readMs] its answer arriving and being read, [openMs] the catalogue made
 * ready to take the reply -- after the page has it, so the page never waits on it -- [saveMs] the reply being kept, and
 * [putMs] what the provider said of the show being kept.
 * [shownMs] is when the page first had something to draw, counted from the page being opened, and [totalMs] when the
 * request ended -- so a wait that is in none of the others is in the page, not in the request. [tries] is how many
 * times the provider had been asked when this answer came: the first answer of a provider that has not built a show
 * yet is often empty, and the one after it is not.
 */
data class RequestTimes(
    val askMs: Long,
    val readMs: Long,
    val saveMs: Long = 0,
    val openMs: Long = 0,
    val tries: Int = 1,
    val lookupMs: Long = 0,
    val putMs: Long = 0,
    val shownMs: Long = 0,
    val totalMs: Long = 0,
) {
    /** One short line for a tester to read off a screen: `look 0.1 s · ask 6.1 s · read 0.4 s · shown 6.8 s · try 1`. */
    fun line(): String = buildString {
        if (lookupMs >= SHOWN_FROM_MS) append("look ").append(seconds(lookupMs)).append(" · ")
        append("ask ").append(seconds(askMs))
        append(" · read ").append(seconds(readMs))
        if (openMs >= SHOWN_FROM_MS) append(" · open ").append(seconds(openMs))
        if (saveMs > 0) append(" · save ").append(seconds(saveMs))
        if (putMs >= SHOWN_FROM_MS) append(" · put ").append(seconds(putMs))
        if (shownMs > 0) append(" · shown ").append(seconds(shownMs))
        if (totalMs > 0) append(" · total ").append(seconds(totalMs))
        append(" · try ").append(tries)
    }

    private fun seconds(ms: Long): String = "${ms / MS_PER_SECOND}.${ms % MS_PER_SECOND / TENTH_MS} s"

    private companion object {
        const val MS_PER_SECOND = 1_000L
        const val TENTH_MS = 100L

        /** A step of the request is not worth a word until it is a tenth of a second. */
        const val SHOWN_FROM_MS = 100L
    }
}
