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

    /** No host, or a host with no dot in it: `http://`, `myserver`, `8080`. */
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

    fun username(raw: String): Validated = credential(raw)

    fun password(raw: String): Validated = credential(raw)

    /**
     * The Xtream credentials hiding inside a playlist URL, or null.
     *
     * Users paste `http://host:8080/get.php?username=X&password=Y&type=m3u_plus` into
     * the M3U box constantly, because it is the link their provider sent them. It works
     * either way, but as Xtream it also gets categories, series, catch-up and a status
     * the app can read — so recognising it and offering to switch is worth the twenty
     * lines.
     *
     * An offer, not a correction: this returns what was found and the screen asks. A
     * client that silently rewrites what someone typed is a client they stop trusting.
     */
    fun detectXtream(raw: String): PlaylistSource.Xtream? {
        val url = Url.parse(raw) ?: return null
        if (url.problem() != null) return null
        val user = url.parameter("username") ?: return null
        val pass = url.parameter("password") ?: return null
        if (user.isEmpty() || pass.isEmpty()) return null
        return PlaylistSource.Xtream(url.origin, user, pass)
    }

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
        !hostLooksReal() -> FieldProblem.INCOMPLETE_HOST
        else -> null
    }

    /** The first value for [name] in the query string, decoded enough to be usable. */
    fun parameter(name: String): String? = query
        .removePrefix("?")
        .split('&')
        .firstOrNull { it.startsWith("$name=") }
        ?.substringAfter('=')
        ?.let(::decode)

    /**
     * A host with no dot is a mistake often enough to reject: `localhost` is not what a
     * provider hands out, and `myserver` or a bare `8080` is someone who has pasted half
     * of something. An IPv6 literal is exempt — it has colons instead.
     */
    private fun hostLooksReal(): Boolean = when {
        host.isEmpty() -> false
        host.startsWith("[") -> host.endsWith("]") && host.length > 2
        host.startsWith(".") || host.endsWith(".") -> false
        else -> host.contains('.') && host.none { it in ILLEGAL_IN_HOST }
    }

    companion object {
        private const val ILLEGAL_IN_HOST = "/\\?#@:[]"

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

        private fun decode(value: String): String {
            if ('%' !in value && '+' !in value) return value
            val out = StringBuilder(value.length)
            var i = 0
            while (i < value.length) {
                val c = value[i]
                when {
                    c == '+' -> { out.append(' '); i++ }
                    c == '%' && i + 2 < value.length -> {
                        val hex = value.substring(i + 1, i + 3).toIntOrNull(16)
                        if (hex == null) { out.append(c); i++ } else { out.append(hex.toChar()); i += 3 }
                    }
                    else -> { out.append(c); i++ }
                }
            }
            return out.toString()
        }
    }
}
