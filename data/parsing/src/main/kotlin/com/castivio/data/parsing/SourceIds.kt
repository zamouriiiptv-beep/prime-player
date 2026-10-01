package com.castivio.data.parsing

import com.castivio.domain.PlaylistSource
import com.castivio.domain.SourceKind

/**
 * The identity of a provider.
 *
 * Derived from what the provider *is*, never from when it was added, because
 * every catalogue row id is built on top of it: the same playlist re-entered
 * after a factory reset has to produce the same source id, or a restored backup
 * of favourites points at nothing.
 *
 * The password is deliberately not part of it. A user changing their Xtream
 * password is the same provider, and re-keying the whole catalogue for that would
 * lose their favourites and watch history.
 *
 * ## One rule: the account the source names
 *
 * **The key is the account, and the account is the host and the user on it.** The
 * password is a secret, not an identity; the output format is a request, not an
 * identity; and when a source names no account at all — a static file on a web
 * server — the key is the address itself, because that is the only thing there is.
 *
 * That was already the Xtream path's rule and it was right. The M3U path had a
 * second one, and it collapsed: it dropped the whole query string to get rid of the
 * password, which on a `get.php?username=…&password=…` playlist — the shape every
 * panel hands out — leaves `host/get.php` and nothing else. Two subscriptions on one
 * panel hashed to the same id, and `OnConflictStrategy.REPLACE` did exactly what it
 * says: the second did not appear under the first, it took its place.
 *
 * Deleting the sentence to remove one word from it. The rule now reads the query
 * instead of discarding it, and both kinds answer the same question the same way.
 */
object SourceIds {

    fun of(source: PlaylistSource): String = when (source) {
        is PlaylistSource.M3u -> hash(SourceKind.M3U_URL, m3uKey(source.url))
        is PlaylistSource.LocalFile -> hash(SourceKind.LOCAL_FILE, source.uri)
        is PlaylistSource.Xtream -> hash(SourceKind.XTREAM, account(source.host, source.username))
        is PlaylistSource.Portal -> hash(SourceKind.PORTAL, source.mac.lowercase().replace("-", ":"))
    }

    fun kindOf(source: PlaylistSource): SourceKind = when (source) {
        is PlaylistSource.M3u -> SourceKind.M3U_URL
        is PlaylistSource.LocalFile -> SourceKind.LOCAL_FILE
        is PlaylistSource.Xtream -> SourceKind.XTREAM
        is PlaylistSource.Portal -> SourceKind.PORTAL
    }

    /**
     * A short label for Settings.
     *
     * Never includes the password, and for Xtream shows host and username, which
     * is what a user with two subscriptions on one panel needs to tell them apart.
     */
    fun labelOf(source: PlaylistSource): String = when (source) {
        is PlaylistSource.M3u -> hostOf(source.url) ?: source.url
        is PlaylistSource.LocalFile -> source.label ?: fileNameOf(source.uri)
        is PlaylistSource.Xtream -> "${hostOf(source.host) ?: source.host} · ${source.username}"
        is PlaylistSource.Portal -> source.mac.uppercase()
    }

    /**
     * What an M3U address identifies.
     *
     * A playlist URL that carries a [USERNAME] is a panel account fetched as a
     * playlist, and it is keyed as the account: host and user, exactly as the Xtream
     * path keys the same two things. Everything else in the query is either the
     * secret or the shape of the response, and neither is an identity — a user who
     * rotates their password or switches `m3u_plus` for `m3u` has not acquired a
     * second subscription.
     *
     * A URL with no user named in it is a file on a server, so the key is the
     * address: scheme and host folded to lower case, a trailing slash dropped, and
     * the query kept minus any secret. The query is kept because for this shape it is
     * all there is to tell two addresses apart — `list.php?id=1` and `list.php?id=2`
     * are two playlists, and the old rule called them one.
     */
    private fun m3uKey(url: String): String {
        val trimmed = url.trim().trimEnd('/')
        val queryStart = trimmed.indexOf('?')
        if (queryStart < 0) return trimmed.lowercase()

        val base = trimmed.substring(0, queryStart)
        val params = trimmed.substring(queryStart + 1)
            .split('&')
            .filter { it.isNotEmpty() }
            .map { it.substringBefore('=').lowercase() to it.substringAfter('=', "") }

        val user = params.firstOrNull { it.first in USERNAME }?.second
        if (!user.isNullOrEmpty()) return account(base, user)

        // No account named, so the address is the identity — minus the secret, and in
        // a fixed order, because a provider that reorders its own query has not handed
        // out a different playlist.
        val kept = params
            .filterNot { it.first in SECRET }
            .map { "${it.first}=${it.second}" }
            .sorted()
            .joinToString("&")
        return (base.lowercase() + "?" + kept)
    }

    /**
     * The host and the user on it, which is what both kinds mean by "which account".
     *
     * `normaliseBase` keeps the scheme and the authority and drops the path, so
     * `panel.example/get.php` and `panel.example/player_api.php` are one panel rather
     * than two — which they are.
     *
     * **Folded to lower case here and not in `normaliseBase`.** A hostname is
     * case-insensitive, so `Panel.example` and `panel.example` are one host and have
     * to be one id; the test that says so is the one that caught this. But
     * `normaliseBase` also builds the URLs that are actually requested, and an
     * identity rule has no business quietly rewriting what goes on the wire. So the
     * folding is done where identity is decided and nowhere else.
     */
    private fun account(host: String, username: String): String =
        XtreamUrls.normaliseBase(host).lowercase() + "|" + username.lowercase()

    private fun hostOf(url: String): String? {
        val withScheme = if (url.contains("://")) url else "http://$url"
        val start = withScheme.indexOf("://") + 3
        if (start >= withScheme.length) return null
        val end = withScheme.indexOfFirst(start) { it == '/' || it == '?' }
        return withScheme.substring(start, end).takeIf { it.isNotEmpty() }
    }

    private fun fileNameOf(uri: String): String =
        uri.substringAfterLast('/').substringBefore('?').ifEmpty { "playlist" }

    private inline fun String.indexOfFirst(from: Int, predicate: (Char) -> Boolean): Int {
        for (i in from until length) if (predicate(this[i])) return i
        return length
    }

    private fun hash(kind: SourceKind, key: String): String =
        StableIds.source(kind.name, key)

    /**
     * The parameter names a panel uses for the account.
     *
     * Two of them, because `get.php` says `username` and a handful of panels say
     * `user`. Both name the same thing, so both key the same way — a list of two
     * rather than a rule per vendor.
     */
    private val USERNAME = setOf("username", "user")

    /** Never part of an identity, and never written into one. */
    private val SECRET = setOf("password", "pass", "pwd", "token", "key", "auth")
}
