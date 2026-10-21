package com.castivio.playback.api

/**
 * A stream's address, safe to put in a log line or a report.
 *
 * ## Why dropping the query was not enough
 *
 * Both engines kept an address up to its `?`, on the grounds that the query is where an
 * address carries its credentials. On an Xtream panel it is not: a channel is
 * `/live/<user>/<password>/123.ts` and a film `/movie/<user>/<password>/…`, so every log line
 * and every report a viewer was invited to copy carried the subscriber's username and password
 * in the path.
 *
 * What is kept is what identifies the stream to whoever reads the report -- the scheme, the
 * host and port, and the file at the end, whose extension says what kind of stream it was --
 * and nothing between them. A user name written into the authority goes as well.
 */
object SourceRedaction {

    fun safe(url: String): String {
        val bare = url.substringBefore('?').substringBefore('#').trim()
        val scheme = bare.substringBefore("://", missingDelimiterValue = "")
        if (scheme.isEmpty()) return bare.substringAfterLast('/').take(MAX_LENGTH)

        val rest = bare.substringAfter("://")
        val authority = rest.substringBefore('/').substringAfterLast('@')
        val segments = rest.substringAfter('/', missingDelimiterValue = "")
            .split('/')
            .filter { it.isNotEmpty() }
        val path = when (segments.size) {
            0 -> ""
            1 -> "/" + segments[0]
            else -> "/…/" + segments.last()
        }
        return "$scheme://$authority$path".take(MAX_LENGTH)
    }

    /** Enough to identify a stream, too little to carry anything else out of the device. */
    private const val MAX_LENGTH = 120
}
