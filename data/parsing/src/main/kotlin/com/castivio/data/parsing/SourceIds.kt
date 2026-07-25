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
 */
object SourceIds {

    fun of(source: PlaylistSource): String = when (source) {
        is PlaylistSource.M3u -> hash(SourceKind.M3U_URL, normaliseUrl(source.url))
        is PlaylistSource.LocalFile -> hash(SourceKind.LOCAL_FILE, source.uri)
        is PlaylistSource.Xtream -> hash(
            SourceKind.XTREAM,
            XtreamUrls.normaliseBase(source.host) + "|" + source.username.lowercase(),
        )
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
     * Ignores what does not change identity: scheme case, a trailing slash, and
     * the credentials some providers embed in the query string.
     */
    private fun normaliseUrl(url: String): String {
        val trimmed = url.trim().trimEnd('/')
        val queryStart = trimmed.indexOf('?')
        val base = if (queryStart > 0) trimmed.substring(0, queryStart) else trimmed
        return base.lowercase()
    }

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
}
