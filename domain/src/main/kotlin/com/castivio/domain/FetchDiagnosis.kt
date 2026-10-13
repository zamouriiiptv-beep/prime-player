package com.castivio.domain

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
 */
data class FetchDiagnosis(
    val stage: FetchStage,
    val httpStatus: Int? = null,
    val exception: String? = null,
    val bytesReceived: Long? = null,
    val itemsRead: Int = 0,
    val itemsSaved: Int = 0,
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
