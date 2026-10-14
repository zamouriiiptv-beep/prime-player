package com.castivio.domain.provider

import com.castivio.domain.PlaylistSource

/**
 * What is wrong with what the user typed, decided while they are still typing.
 *
 * Every rule here is pure and instant, because the alternative is the pattern this app
 * exists to be better than: a Continue button that always looks enabled, a spinner, and
 * "Login failed" thirty seconds later with no indication of which field was wrong.
 *
 * The other half of its job is **normalisation**, which matters more than the checking.
 * What a provider sends by e-mail — a bare host, a trailing slash, a full `player_api`
 * URL, a line that got a space on the end when it was copied — is not what an Xtream
 * client needs, and asking the user to fix that by hand on a television remote is a
 * design failure. [Validated.value] is the corrected text, ready to submit.
 */
data class Validated(
    /** The trimmed, normalised value. Present even when [problem] is not null. */
    val value: String,
    val problem: FieldProblem? = null,
) {
    val isValid: Boolean get() = problem == null
}

/**
 * Why a field is not acceptable.
 *
 * Deliberately a small, closed set: each one is a sentence the activation screen has to
 * write, and every entry added here is a translation added in every language.
 */
enum class FieldProblem {
    /** Empty, and this field is not optional. */
    REQUIRED,

    /** Longer than anything real. Almost always a paste of the wrong thing entirely. */
    TOO_LONG,

    /** Spaces inside a URL. A URL cannot contain them, so this is a paste that broke. */
    CONTAINS_SPACES,

    /** Something other than `http` or `https` — `rtmp`, `ftp`, or a typo like `htp`. */
    UNSUPPORTED_SCHEME,

    /**
     * No host at all, or one that is not a hostname: `http://`, `http://.com`, `8080`.
     *
     * A single-label name is *not* a problem. `myserver`, `localhost` and an internal
     * DNS name are all valid hosts, and refusing them would break setups that work
     * today for no benefit — a dot is a convention of public domains, not a rule of
     * hostnames.
     */
    INCOMPLETE_HOST,

    /** A port outside 1–65535, or one that is not a number. */
    INVALID_PORT,
}

object FieldValidation {

    /** Generous, but short of a paste of an entire playlist into the name box. */
    const val MAX_NAME = 60
    const val MAX_URL = 2048
    const val MAX_CREDENTIAL = 128

    /**
     * The playlist's display name, which the user decided is optional.
     *
     * Empty is valid and yields an empty value; the caller substitutes a default from
     * the host name. That decision is not made here because the default is a piece of
     * user-facing text, and user-facing text is not `:domain`'s to write.
     */
    fun playlistName(raw: String): Validated {
        val value = raw.trim()
        return when {
            value.length > MAX_NAME -> Validated(value.take(MAX_NAME), FieldProblem.TOO_LONG)
            else -> Validated(value)
        }
    }

    /**
     * An Xtream server, normalised to its origin: `http://host:8080`.
     *
     * Path and query are dropped on purpose. Providers hand out
     * `http://host:8080/player_api.php?username=…` and
     * `http://host:8080/c/` about as often as they hand out the bare origin, and an
     * Xtream client builds its own paths — so keeping what they sent would produce
     * `http://host:8080/c/player_api.php` and a failure nobody could read.
     */
    fun serverUrl(raw: String): Validated {
        val problem = lengthOrSpaces(raw, MAX_URL)
        if (problem != null) return Validated(raw.trim(), problem)

        val url = Url.parse(raw) ?: return Validated(raw.trim(), FieldProblem.INCOMPLETE_HOST)
        url.problem()?.let { return Validated(url.origin, it) }
        return Validated(url.origin)
    }

    /**
     * A playlist URL, normalised but otherwise left alone.
     *
     * Unlike [serverUrl] the path and query are the whole point, and no attempt is made
     * to guess whether the target is really a playlist — plenty of working M3U links end
     * in `.php`, a token, or nothing at all, and a client that refuses them is a client
     * the user abandons.
     */
    fun playlistUrl(raw: String): Validated {
        val problem = lengthOrSpaces(raw, MAX_URL)
        if (problem != null) return Validated(raw.trim(), problem)

        val url = Url.parse(raw) ?: return Validated(raw.trim(), FieldProblem.INCOMPLETE_HOST)
        url.problem()?.let { return Validated(url.full, it) }
        return Validated(url.full)
    }

    /**
     * A Stalker or Ministra portal address, kept whole.
     *
     * The opposite of [serverUrl], and deliberately: an Xtream client builds its own
     * paths from an origin, while a portal *is* a path. Providers hand out
     * `http://host:8080/c/`, `http://host/stalker_portal/c/` and the bare origin, and
     * which of them it is decides where the API lives — so what the user pasted is kept
     * and `PortalUrls` is where it is read. Dropping the path here would turn two
     * different portals on one host into one address.
     */
    fun portalUrl(raw: String): Validated {
        val problem = lengthOrSpaces(raw, MAX_URL)
        if (problem != null) return Validated(raw.trim(), problem)

        val url = Url.parse(raw) ?: return Validated(raw.trim(), FieldProblem.INCOMPLETE_HOST)
        url.problem()?.let { return Validated(url.full, it) }
        return Validated(url.full)
    }

    fun username(raw: String): Validated = credential(raw)

    fun password(raw: String): Validated = credential(raw)

    /**
     * The Xtream account a playlist URL names, read from the URL alone, or null.
     *
     * Users paste `http://host:8080/get.php?username=X&password=Y&type=m3u_plus` into
     * the M3U box constantly, because it is the link their provider sent them. It works
     * either way, but read through the panel's API it also gets categories, series,
     * catch-up and a status the app can read. The subscription stays the playlist the
     * user added — its link, its id, its row in the list; only the reading changes, and
     * that is decided where a stored subscription is turned back into something to
     * fetch: `asPlaylistSource`.
     *
     * ## What makes a link an account
     *
     *  - **`get.php` at the root of the server.** That is the panel's playlist endpoint,
     *    and its API answers beside it at `player_api.php`. A link to anything else makes
     *    no such promise however its query looks, and `get.php` under a sub-path would be
     *    read at the wrong address: the Xtream path builds every URL from the origin.
     *  - **One username and one password, neither empty.** `user` is accepted as the
     *    username's other spelling, as `SourceIds` does. A name given twice with two
     *    different values cannot be read one way, and is left to be what it says it is:
     *    a playlist.
     *  - **Decoded the way the server decodes them.** An escape is a byte of UTF-8, `+` is
     *    a space, and what follows `#` is never sent. A value whose bytes are not UTF-8
     *    makes the link unreadable as an account rather than a guess at one: a password
     *    decoded wrongly is a different password.
     *
     * `type` and `output` shape the playlist's response, not the account, so a link reads
     * the same with them, without them, and in any order.
     *
     * Reading an account is not proof of one. Whether it exists, whether the password is
     * right and whether the panel answers its API is for the panel to say, and the
     * importer falls back to the playlist itself when the API says no.
     */
    fun detectXtream(raw: String): PlaylistSource.Xtream? {
        val url = Url.parse(raw) ?: return null
        if (url.problem() != null) return null
        if (!url.path.equals(XTREAM_PLAYLIST_PATH, ignoreCase = true)) return null
        val user = url.single(USERNAME_KEYS)?.takeIf(::isCredential) ?: return null
        val pass = url.single(PASSWORD_KEYS)?.takeIf(::isCredential) ?: return null
        return PlaylistSource.Xtream(url.origin, user, pass)
    }

    private fun isCredential(value: String): Boolean = value.isNotEmpty() && value.length <= MAX_CREDENTIAL

    /** The panel's playlist endpoint, at the root of the server. */
    private const val XTREAM_PLAYLIST_PATH = "/get.php"

    private val USERNAME_KEYS = setOf("username", "user")
    private val PASSWORD_KEYS = setOf("password")

    private fun credential(raw: String): Validated {
        // Trimmed rather than rejected: a trailing space from a copy is the single most
        // common reason a correct password is refused, and it is not the user's mistake
        // in any sense they could act on.
        val value = raw.trim()
        return when {
            value.isEmpty() -> Validated(value, FieldProblem.REQUIRED)
            value.length > MAX_CREDENTIAL -> Validated(value.take(MAX_CREDENTIAL), FieldProblem.TOO_LONG)
            else -> Validated(value)
        }
    }

    private fun lengthOrSpaces(raw: String, max: Int): FieldProblem? {
        val value = raw.trim()
        return when {
            value.isEmpty() -> FieldProblem.REQUIRED
            value.length > max -> FieldProblem.TOO_LONG
            value.any { it.isWhitespace() } -> FieldProblem.CONTAINS_SPACES
            else -> null
        }
    }
}

/** The Xtream form, checked field by field. */
data class XtreamFormCheck(
    val name: Validated,
    val serverUrl: Validated,
    val username: Validated,
    val password: Validated,
) {
    val canSubmit: Boolean
        get() = name.isValid && serverUrl.isValid && username.isValid && password.isValid

    /** The label to store, or null to let the caller derive one. */
    val label: String? get() = name.value.ifEmpty { null }

    /** What to hand the repository, or null while anything is still wrong. */
    val source: PlaylistSource.Xtream?
        get() = if (canSubmit) {
            PlaylistSource.Xtream(serverUrl.value, username.value, password.value)
        } else {
            null
        }

    companion object {
        fun of(name: String, serverUrl: String, username: String, password: String) = XtreamFormCheck(
            name = FieldValidation.playlistName(name),
            serverUrl = FieldValidation.serverUrl(serverUrl),
            username = FieldValidation.username(username),
            password = FieldValidation.password(password),
        )
    }
}

/** The M3U form, checked field by field. */
data class M3uFormCheck(
    val name: Validated,
    val url: Validated,
    /** Non-null when [url] is really an Xtream link. See [FieldValidation.detectXtream]. */
    val xtream: PlaylistSource.Xtream? = null,
) {
    val canSubmit: Boolean get() = name.isValid && url.isValid

    val label: String? get() = name.value.ifEmpty { null }

    val source: PlaylistSource.M3u? get() = if (canSubmit) PlaylistSource.M3u(url.value) else null

    companion object {
        fun of(name: String, url: String) = M3uFormCheck(
            name = FieldValidation.playlistName(name),
            url = FieldValidation.playlistUrl(url),
            xtream = FieldValidation.detectXtream(url),
        )
    }
}

/**
 * The portal form, checked.
 *
 * The same two fields the playlist form has and the same rules for both — an optional
 * name and an address — because that is what a portal subscription is from this
 * screen's side. What differs is behind the button, not in front of it.
 */
data class PortalFormCheck(
    val name: Validated,
    val url: Validated,
) {
    val canSubmit: Boolean get() = name.isValid && url.isValid

    val label: String? get() = name.value.ifEmpty { null }

    val source: PlaylistSource.Portal? get() = if (canSubmit) PlaylistSource.Portal(url.value) else null

    companion object {
        fun of(name: String, url: String) = PortalFormCheck(
            name = FieldValidation.playlistName(name),
            url = FieldValidation.portalUrl(url),
        )
    }
}

/**
 * Just enough URL to check one and put it back together.
 *
 * Hand-rolled because `:domain` is plain Kotlin and `java.net.URI` is not, and because
 * the standard parsers are strict about things providers are casual about — a missing
 * scheme most of all. Deliberately small: it splits, it does not resolve, encode or
 * canonicalise.
 */
private class Url(
    val scheme: String,
    val host: String,
    val port: Int?,
    val portText: String?,
    val path: String,
    val query: String,
) {

    val origin: String get() = "$scheme://$host" + (port?.let { ":$it" } ?: "")

    val full: String get() = origin + path + query

    fun problem(): FieldProblem? = when {
        scheme != "http" && scheme != "https" -> FieldProblem.UNSUPPORTED_SCHEME
        portText != null && port == null -> FieldProblem.INVALID_PORT
        !hostIsWellFormed() -> FieldProblem.INCOMPLETE_HOST
        else -> null
    }

    /**
     * The one value the query gives under any of [names], decoded; null when none does,
     * when two of them disagree, or when one is not UTF-8.
     *
     * Names are matched exactly, as the server matches them. The query is read as it is
     * sent: everything after `#` is the fragment, which never leaves the device.
     */
    fun single(names: Set<String>): String? {
        val values = query.removePrefix("?").substringBefore('#')
            .split('&')
            .filter { it.substringBefore('=') in names }
            .map { decode(it.substringAfter('=', "")) ?: return null }
            .distinct()
        return values.singleOrNull()
    }

    /**
     * Hostname syntax, and nothing beyond it.
     *
     * A dot is deliberately not required. `myserver`, `localhost` and an internal DNS
     * name are valid hosts, and plenty of people run a panel on one — refusing them
     * would break working setups to catch a typo.
     *
     * The one single-label form that is refused is an all-digit one, because `8080` is
     * never a hostname anyone types on purpose; it is a port that arrived without its
     * host. An IPv4 address is unaffected: it has dots, so it is not a single label.
     */
    private fun hostIsWellFormed(): Boolean {
        if (host.isEmpty()) return false
        if (host.startsWith("[")) return host.endsWith("]") && host.length > 2

        val labels = host.split('.')
        if (labels.any { !isLabel(it) }) return false
        if (labels.size == 1 && labels[0].all { it.isDigit() }) return false
        return host.length <= MAX_HOST
    }

    private fun isLabel(label: String): Boolean =
        label.isNotEmpty() &&
            label.length <= MAX_LABEL &&
            !label.startsWith('-') &&
            !label.endsWith('-') &&
            label.all { it.isLetterOrDigit() || it == '-' || it == '_' }

    companion object {
        /** RFC 1035: a label is at most 63 octets, a name at most 253. */
        private const val MAX_LABEL = 63
        private const val MAX_HOST = 253

        fun parse(raw: String): Url? {
            val text = raw.trim()
            if (text.isEmpty()) return null

            // A bare host is the most common thing a provider sends, so it is treated as
            // an omission rather than an error. Plain http because provider panels
            // overwhelmingly are; the user can type https and keep it.
            val separator = text.indexOf("://")
            val scheme = if (separator > 0) text.substring(0, separator).lowercase() else "http"
            val rest = if (separator > 0) text.substring(separator + 3) else text

            val authorityEnd = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
                .let { if (it == -1) rest.length else it }
            // Credentials in the authority (`user:pass@host`) are dropped: they are not
            // how any Xtream or M3U provider authenticates, and carrying them into a
            // stored URL would put a password somewhere nobody expects to find one.
            val authority = rest.substring(0, authorityEnd).substringAfterLast('@')
            val tail = rest.substring(authorityEnd)

            val queryStart = tail.indexOfFirst { it == '?' || it == '#' }
            val path = (if (queryStart == -1) tail else tail.substring(0, queryStart)).trimEnd('/')
            val query = if (queryStart == -1) "" else tail.substring(queryStart)

            val (host, portText) = splitPort(authority)
            val port = portText?.toIntOrNull()?.takeIf { it in 1..65535 }

            return Url(scheme, host, port, portText, path, query)
        }

        private fun splitPort(authority: String): Pair<String, String?> {
            // IPv6 literals are bracketed, so only a colon after the closing bracket is
            // a port separator.
            val from = if (authority.startsWith("[")) authority.indexOf(']') + 1 else 0
            if (from < 0) return authority to null
            val colon = authority.indexOf(':', startIndex = from)
            if (colon == -1) return authority to null
            return authority.substring(0, colon) to authority.substring(colon + 1)
        }

        /**
         * Percent-decoding as the server does it, or null when the result is not text.
         *
         * An escape is one byte, and the bytes together are UTF-8: `%C3%A9` is `é`, where
         * the decoder this replaces made each escape a character of its own and produced
         * `Ã©` — a password the panel would refuse, built from one it would accept. `+` is
         * a space, as in any query. A `%` that does not start an escape is kept as it is.
         * Bytes that are not UTF-8 are refused rather than repaired: a repaired password
         * is a different password.
         */
        private fun decode(value: String): String? {
            if ('%' !in value && '+' !in value) return value
            val bytes = ArrayList<Byte>(value.length)
            val literal = StringBuilder()
            fun flush() {
                if (literal.isEmpty()) return
                literal.toString().encodeToByteArray().forEach { bytes.add(it) }
                literal.clear()
            }
            var i = 0
            while (i < value.length) {
                val c = value[i]
                val high = if (c == '%' && i + 2 < value.length) hexDigit(value[i + 1]) else -1
                val low = if (high >= 0) hexDigit(value[i + 2]) else -1
                when {
                    low >= 0 -> {
                        flush()
                        bytes.add(((high shl 4) or low).toByte())
                        i += 3
                    }
                    c == '+' -> {
                        flush()
                        bytes.add(SPACE)
                        i++
                    }
                    else -> {
                        literal.append(c)
                        i++
                    }
                }
            }
            flush()
            return try {
                bytes.toByteArray().decodeToString(throwOnInvalidSequence = true)
            } catch (e: CharacterCodingException) {
                null
            }
        }

        private fun hexDigit(c: Char): Int = when (c) {
            in '0'..'9' -> c - '0'
            in 'a'..'f' -> c - 'a' + 10
            in 'A'..'F' -> c - 'A' + 10
            else -> -1
        }

        private const val SPACE: Byte = 0x20
    }
}
