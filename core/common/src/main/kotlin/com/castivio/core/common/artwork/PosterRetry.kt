package com.castivio.core.common.artwork

/**
 * When a picture that failed is asked for again, and whether it is worth asking.
 *
 * A poster is a few hundred kilobytes asked of a host the application does not control, over a connection that may be a phone's
 * mobile data, and while a section's first load is running that connection is busy with a reply of seventy-eight megabytes.
 * A call that gave up for want of a few seconds is not a poster that does not exist, and a card that drew nothing the first
 * time and was never asked again showed an empty frame for as long as it stayed on the screen. This is the rule that asks again:
 * soon after the first failure, then less and less often, and at once when the load that was crowding it out has ended
 * (see [ArtworkKick]).
 *
 * Pure, so the schedule and the line between "try again" and "it will not change" are held by a test and not by a long wait.
 */
object PosterRetry {

    /**
     * The waits before each new try, in milliseconds: two seconds after the first failure, then five, ten, twenty and thirty.
     * The five of them cover a minute and seven seconds, which is as long as a first load of a big section takes on a slow
     * line; after that the picture is asked for again only when something says the connection is free.
     */
    private val WAITS_MS = longArrayOf(2_000L, 5_000L, 10_000L, 20_000L, 30_000L)

    /** How many times a picture is asked for again on its own, before it waits to be kicked. */
    val RETRIES: Int get() = WAITS_MS.size

    /**
     * How long to wait before the next try of a picture that has failed [failures] times, or null when it has been tried as
     * often as it is on its own -- and for a count of nought or fewer, which is a picture that has not failed.
     */
    fun waitBeforeRetry(failures: Int): Long? = WAITS_MS.getOrNull(failures - 1)

    /**
     * Whether asking again can help.
     *
     * [httpStatus] is the answer's, or null when there was none -- a timeout, a connection that dropped, a name that did not
     * resolve -- which is the case a retry is for. A host that said it is busy (408, 425, 429) or broken (5xx) may say
     * something else a few seconds later; one that said the picture is not there, or is not for us (404, 403, 410, the other
     * 4xx), will say it again, and asking is a request wasted on a host that has already answered.
     */
    fun retryable(httpStatus: Int?): Boolean = when {
        httpStatus == null -> true
        httpStatus == 408 || httpStatus == 425 || httpStatus == 429 -> true
        httpStatus in 500..599 -> true
        else -> false
    }

    /**
     * The HTTP status a failure's message names, or null when it names none.
     *
     * The image loader reports a host's refusal as an exception whose message begins `HTTP 404: Not Found`; a timeout, a
     * connection that dropped and a name that did not resolve say something else and have no status. Read from the message
     * and not from the exception's own fields, so that this module -- and the card that asks -- needs nothing of the loader's
     * types to tell a refusal from a failure of the road.
     */
    fun statusOf(message: String?): Int? = message?.let { STATUS.find(it)?.groupValues?.get(1)?.toIntOrNull() }

    private val STATUS = Regex("""^HTTP (\d{3})\b""")
}
