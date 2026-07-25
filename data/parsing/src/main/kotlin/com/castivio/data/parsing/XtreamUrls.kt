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
    ): String {
        val segment = when (kind) {
            MediaKind.MOVIE -> "movie"
            MediaKind.SERIES -> "series"
            MediaKind.LIVE, MediaKind.RADIO -> "live"
        }
        val suffix = extension?.trim()?.trimStart('.')?.takeIf { it.isNotEmpty() }
            ?: if (kind == MediaKind.MOVIE || kind == MediaKind.SERIES) "mp4" else "ts"
        return buildString {
            append(normaliseBase(base)).append('/').append(segment).append('/')
            append(encode(username)).append('/').append(encode(password)).append('/')
            append(streamId).append('.').append(suffix)
        }
    }

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
