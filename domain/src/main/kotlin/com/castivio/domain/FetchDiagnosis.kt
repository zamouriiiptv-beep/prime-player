package com.castivio.domain

import com.castivio.core.common.AppError

/**
 * How far a failed download got, in figures a screen can print.
 *
 * ## Why it exists
 *
 * A failure reached the screen as one [com.castivio.core.common.AppError], and several
 * different breakdowns share `UNKNOWN`: a status the app has no name for, a link the HTTP
 * client would not send, the parser or the database throwing, an exception outside the
 * download. Telling them apart took `adb logcat`, which a viewer holding a television
 * remote does not have. This carries the facts the importer's log line already had to
 * the screen the viewer is looking at.
 *
 * ## What is never in it
 *
 * The address, the account, or an exception's message: a stage, a status, class names
 * and counts. The rule the log line follows, for the same reason — a screen gets
 * photographed and sent to whoever is helping, and an M3U link carries its password.
 *
 * @param httpStatus what the provider answered with. Null when it never answered.
 * @param exception the exception's class, with its root cause's when that is a different
 *   one — see [nameOf]. Null when nothing was thrown, as for a refusal by status.
 * @param bytesReceived what had arrived of the body. Null when no body was opened, which
 *   is a different finding from a body that was opened and sent nothing.
 * @param itemsRead playlist entries read so far, whether or not they reached the database.
 * @param itemsSaved rows committed to the database so far. A failed import keeps them.
 * @param route which way the subscription was being read when it failed. Null when that
 *   is not known — an exception from outside the download says nothing about it.
 * @param after the panel's failure, when this one is the playlist's that followed it: the
 *   one fallback an M3U subscription read through its panel makes. Null otherwise.
 */
data class FetchDiagnosis(
    val stage: FetchStage,
    val httpStatus: Int? = null,
    val exception: String? = null,
    val bytesReceived: Long? = null,
    val itemsRead: Int = 0,
    val itemsSaved: Int = 0,
    val route: FetchRoute? = null,
    val after: FetchDiagnosis? = null,
) {
    companion object {
        /**
         * `SSLHandshakeException<-CertificateException`: the class, and the root cause's
         * when it is a different one, which says what a bare wrapper does not.
         *
         * Class names only. A message is never read: an unknown host, a refused cleartext
         * request and most TLS errors name the host in it, and some name the whole URL.
         */
        fun nameOf(error: Throwable): String {
            val root = generateSequence(error) { it.cause }.take(MAX_CAUSES).last()
            val outer = simpleNameOf(error)
            val inner = simpleNameOf(root)
            return if (root === error || inner == outer) outer else "$outer<-$inner"
        }

        // `KClass` rather than `javaClass`: this module is plain Kotlin. An anonymous
        // class has no name to give, and the message is not an acceptable substitute.
        private fun simpleNameOf(error: Throwable): String = error::class.simpleName ?: ANONYMOUS

        private const val MAX_CAUSES = 8
        private const val ANONYMOUS = "anonymous"
    }
}

/**
 * Where a download stopped, in the order a download passes through them.
 *
 * The four a viewer's question splits into — did the request go out, did the provider
 * answer, could the answer be read, could it be kept — plus the one place outside the
 * download an exception can come from.
 */
enum class FetchStage {
    /** Nothing was sent: the link could not be made into a request. */
    PREPARE,

    /** Sent, and no answer came back: no connection, no name lookup, or no reply in time. */
    CONNECT,

    /** The provider answered, with a status that is not a playlist. */
    ANSWER,

    /** The playlist was arriving and could not be read to the end. */
    READ,

    /** What was read could not be written to the database. */
    SAVE,

    /** The app failed outside the download itself, before the request or after the rows. */
    OTHER,
}

/** The two ways a subscription's catalogue is read. */
enum class FetchRoute {
    /** The playlist file, downloaded whole. */
    M3U,

    /** The panel's API, one section at a time. */
    XTREAM,
}

/**
 * Whether a panel's API failing leaves the playlist beside it worth one try.
 *
 * Not when the network is the reason — no connection, no name, no answer in time: the
 * playlist is on the same server, and asking it too would double the wait for the same
 * answer. Not when the database is: the playlist would be written to the same one.
 * Everything else — a refusal, a missing endpoint, a status nobody names, an answer that
 * is not the API's — says something about the API and nothing about the playlist.
 *
 * One rule for the import and the refresh, so a refresh cannot call a subscription
 * unreachable that a section has just loaded from.
 *
 * @param stage where the panel's attempt stopped, when that is known.
 */
fun panelFailureAllowsPlaylist(error: AppError, stage: FetchStage? = null): Boolean =
    error != AppError.NETWORK_UNAVAILABLE && error != AppError.TIMEOUT && stage != FetchStage.SAVE
