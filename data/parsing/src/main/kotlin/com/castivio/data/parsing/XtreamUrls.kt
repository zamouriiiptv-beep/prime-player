package com.castivio.data.parsing

import com.castivio.domain.MediaKind

/**
 * Builds Xtream URLs.
 *
 * Small, but three details in here are the difference between "works" and
 * "silently fails against half the panels":
 *
 *  - **The base is normalised.** Users paste `http://host:8080/`,
 *    `host:8080`, and `http://host:8080/player_api.php` interchangeably.
 *  - **Credentials are percent-encoded.** Xtream passwords routinely contain
 *    `@`, `+` and `&`, which change the meaning of the URL if passed raw.
 *  - **Stream paths differ by kind.** `/live/`, `/movie/`, `/series/` — and the
 *    classifier depends on those paths, so getting them right here is what makes
 *    a re-import classify the same way twice.
 */
object XtreamUrls {

    /**
     * @param base whatever the user typed: with or without scheme, port, trailing
     *   slash, or a `player_api.php` suffix.
     */
    fun normaliseBase(base: String): String {
        var value = base.trim()
        if (value.isEmpty()) return value
        if (!value.startsWith("http://", ignoreCase = true) && !value.startsWith("https://", ignoreCase = true)) {
            value = "http://$value"
        }
        // Drop anything after the authority: users paste whole API URLs.
        val schemeEnd = value.indexOf("://") + 3
        val pathStart = value.indexOf('/', schemeEnd)
        if (pathStart > 0) value = value.substring(0, pathStart)
        return value.trimEnd('/')
    }

    /** `player_api.php` with credentials and an optional action. */
    fun api(
        base: String,
        username: String,
        password: String,
        action: String? = null,
        parameters: Map<String, String> = emptyMap(),
    ): String = buildString {
        append(normaliseBase(base)).append("/player_api.php")
        append("?username=").append(encode(username))
        append("&password=").append(encode(password))
        if (action != null) append("&action=").append(encode(action))
        for ((key, value) in parameters) {
            append('&').append(encode(key)).append('=').append(encode(value))
        }
    }

    /**
     * A playable stream URL.
     *
     * Live defaults to `.ts`: it is the most widely supported container and, on a
     * weak box, cheaper to start than HLS because there is no playlist round trip
     * before the first segment.
     */
    fun stream(
        base: String,
        username: String,
        password: String,
        kind: MediaKind,
        streamId: String,
        extension: String? = null,
    ): String = stream(streamPrefix(base, username, password, kind), kind, streamId, extension)

    /**
     * What every stream URL of one kind has in common: `http://host:8080/live/user/pass/`.
     *
     * The base is normalised and both credentials percent-encoded to make it, and none of that depends on the
     * stream. An import that builds one URL per channel -- fifty thousand of them -- made it again for each, so it
     * is made once per import by whoever builds the URLs, and [stream] with a prefix adds the stream's own part.
     * The two forms give the same URL character for character: the first is defined as the second.
     */
    fun streamPrefix(base: String, username: String, password: String, kind: MediaKind): String {
        val segment = when (kind) {
            MediaKind.MOVIE -> "movie"
            MediaKind.SERIES -> "series"
            MediaKind.LIVE, MediaKind.RADIO -> "live"
        }
        return buildString {
            append(normaliseBase(base)).append('/').append(segment).append('/')
            append(encode(username)).append('/').append(encode(password)).append('/')
        }
    }

    /** A stream's URL from the [prefix] [streamPrefix] made for its [kind]: the stream's id and container only. */
    fun stream(prefix: String, kind: MediaKind, streamId: String, extension: String? = null): String {
        val suffix = extension?.trim()?.trimStart('.')?.takeIf { it.isNotEmpty() }
            ?: if (kind == MediaKind.MOVIE || kind == MediaKind.SERIES) "mp4" else "ts"
        return buildString(prefix.length + streamId.length + suffix.length + 1) {
            append(prefix).append(streamId).append('.').append(suffix)
        }
    }

    /**
     * The address of an archived programme: `http://host:8080/timeshift/user/pass/<minutes>/<start>/<stream id>.ts`.
     *
     * **One of the forms Xtream panels accept, and the one used here -- it is not a standard.** Community clients for the
     * panel write the same path, with the duration in minutes and the start as `YYYY-MM-DD:HH-MM`, and the panel serves
     * it from the same handler as its `streaming/timeshift.php?stream=&start=&duration=` address. Panels differ in the
     * details (a duration in seconds, another start format), and this has not been tried against a live panel: see
     * `LOCAL_CHANGES.md` section 56 for what is and is not verified.
     *
     * @param start the programme's start as the panel wrote it, already turned into the path's form by [timeshiftStart].
     */
    fun timeshift(
        base: String,
        username: String,
        password: String,
        streamId: String,
        durationMinutes: Int,
        start: String,
        extension: String = "ts",
    ): String = buildString {
        append(normaliseBase(base)).append("/timeshift/")
        append(encode(username)).append('/').append(encode(password)).append('/')
        append(durationMinutes.coerceAtLeast(1)).append('/')
        // Not encoded: [timeshiftStart] makes it of digits, `-` and `:` only, and a panel reads the colon as written.
        append(start).append('/')
        append(encode(streamId)).append('.').append(extension.trim().trimStart('.').ifEmpty { "ts" })
    }

    /**
     * `2025-11-02 21:30:00` (or with a `T`) as the archive path writes it, `2025-11-02:21-30`, or null when the text is
     * not a date and a time.
     *
     * The wall-clock digits are carried over **unchanged**: they are in the panel's time zone, which is the one an
     * archive request is read in, and nothing here converts them to or from any other.
     */
    fun timeshiftStart(serverStart: String): String? {
        val text = serverStart.trim()
        if (text.length < 16) return null
        if (text[4] != '-' || text[7] != '-' || (text[10] != ' ' && text[10] != 'T') || text[13] != ':') return null
        for (i in intArrayOf(0, 1, 2, 3, 5, 6, 8, 9, 11, 12, 14, 15)) if (text[i] !in '0'..'9') return null
        return buildString(16) {
            append(text, 0, 10).append(':').append(text, 11, 13).append('-').append(text, 14, 16)
        }
    }

    /**
     * The same archived programme as [timeshift], addressed the other way Xtream panels accept:
     * `http://host:port/streaming/timeshift.php?username=&password=&stream=&start=&duration=`.
     *
     * **Asked for only after the path form answered 404, and once** -- see the player's catch-up fallback. Which of the
     * two a panel serves is not something it says; some serve one, some both. The parameter names are the ones community
     * documentation lists for this endpoint -- not an official specification, and not yet tried against a live panel --
     * with the same values as the path form: the stream is the stream id, the start is `YYYY-MM-DD:HH-MM` in the panel's
     * own time zone, and the duration is in whole minutes.
     *
     * **Checked before it is built, and null for anything the panel would not read:**
     * - the stream is made of letters, digits, `-` and `_` and is not empty -- what a panel's stream id is;
     * - the duration is a whole number of minutes from 1 to [MAX_ARCHIVE_MINUTES];
     * - the start is exactly `YYYY-MM-DD:HH-MM`: digits and its three separators, nothing else.
     *
     * The account is percent-encoded as it is in [timeshift]; the colon of the start is written as it is, which a query
     * allows and a panel reads as written.
     */
    fun timeshiftQuery(
        base: String,
        username: String,
        password: String,
        streamId: String,
        durationMinutes: Int,
        start: String,
    ): String? {
        val stream = streamId.trim()
        if (!isStreamId(stream) || durationMinutes !in 1..MAX_ARCHIVE_MINUTES || !isTimeshiftStart(start)) return null
        return buildString {
            append(normaliseBase(base)).append("/streaming/timeshift.php")
            append("?username=").append(encode(username))
            append("&password=").append(encode(password))
            append("&stream=").append(stream)
            append("&start=").append(start)
            append("&duration=").append(durationMinutes)
        }
    }

    /** The longest programme an archive address is built for: a day. Longer is not a programme, it is a gap in a guide. */
    const val MAX_ARCHIVE_MINUTES = 24 * 60

    /** Whether [text] is exactly `YYYY-MM-DD:HH-MM`: the form [timeshiftStart] makes and a panel reads. */
    internal fun isTimeshiftStart(text: String): Boolean {
        if (text.length != TIMESHIFT_START_LENGTH) return false
        if (text[4] != '-' || text[7] != '-' || text[10] != ':' || text[13] != '-') return false
        for (i in intArrayOf(0, 1, 2, 3, 5, 6, 8, 9, 11, 12, 14, 15)) if (text[i] !in '0'..'9') return false
        return true
    }

    /** Whether [text] can be a stream id: not empty, and only ASCII letters, digits, `-` and `_`. */
    internal fun isStreamId(text: String): Boolean =
        text.isNotEmpty() && text.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' }

    private const val TIMESHIFT_START_LENGTH = 16

    /** The alternate container for a stream, used by playback fallback. */
    fun alternateLiveFormat(url: String): String? = when {
        url.endsWith(".ts", ignoreCase = true) -> url.dropLast(3) + ".m3u8"
        url.endsWith(".m3u8", ignoreCase = true) -> url.dropLast(5) + ".ts"
        else -> null
    }

    /**
     * The provider's own XMLTV endpoint. Full guide, so it is the fallback when
     * `get_short_epg` is not enough — never the first choice.
     */
    fun xmltv(base: String, username: String, password: String): String =
        "${normaliseBase(base)}/xmltv.php?username=${encode(username)}&password=${encode(password)}"

    /**
     * Percent-encodes a path or query component.
     *
     * `URLEncoder` is not used because it encodes spaces as `+`, which is correct
     * for form bodies and wrong inside a path — and Xtream credentials go in the
     * path.
     */
    internal fun encode(value: String): String {
        val out = StringBuilder(value.length + 8)
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val c = byte.toInt().toChar()
            if (c.isLetterOrDigit() && byte.toInt() in 0..127 || c in UNRESERVED) {
                out.append(c)
            } else {
                out.append('%').append(HEX[(byte.toInt() shr 4) and 0xF]).append(HEX[byte.toInt() and 0xF])
            }
        }
        return out.toString()
    }

    private const val UNRESERVED = "-_.~"
    private val HEX = "0123456789ABCDEF".toCharArray()
}
