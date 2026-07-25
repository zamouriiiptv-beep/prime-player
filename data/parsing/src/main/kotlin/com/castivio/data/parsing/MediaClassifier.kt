package com.castivio.data.parsing

import com.castivio.domain.MediaKind

/**
 * Decides what an M3U entry actually is.
 *
 * M3U has no notion of content type — providers ship live channels, films,
 * series episodes and radio stations in one flat file and expect the player to
 * work it out. Getting this wrong is visible immediately: films in the live
 * guide, or a Series screen that lists 40,000 individual episodes instead of
 * 600 shows.
 *
 * The signals, strongest first:
 *
 *  1. **The URL path.** Xtream-generated playlists use `/live/`, `/movie/` and
 *     `/series/`. That is the provider stating the type outright, so it wins.
 *  2. **A season/episode marker in the name** (`S01E05`, `1x05`,
 *     `Season 1 Episode 5`) — the only reliable way to fold episodes into shows.
 *  3. **The group title** (`VOD`, `Movies`, `مسلسلات`, `Radio`).
 *  4. **Duration.** `#EXTINF:-1` is live; a positive duration is VOD.
 *
 * Radio is checked before everything else: a radio station is usually served
 * from `/live/` with an audio codec, so a path check alone would file it as a
 * TV channel.
 *
 * Scanning is hand-rolled rather than regex-based. This runs once per entry —
 * 400,000 times on a large import — and a `Regex` here would allocate a
 * `MatchResult` per row and dominate the parse.
 */
object MediaClassifier {

    fun classify(entry: M3uEntry): Classification {
        val group = entry.groupTitle
        if (isRadio(entry, group)) return Classification(MediaKind.RADIO)

        when (pathKind(entry.url)) {
            PathKind.SERIES -> return asSeries(entry)
            PathKind.MOVIE -> return Classification(MediaKind.MOVIE)
            // An explicit /live/ path is the provider being unambiguous; a name
            // that happens to look like "S01" does not override it.
            PathKind.LIVE -> return Classification(MediaKind.LIVE)
            null -> Unit
        }

        if (SeriesMarker.find(entry.name) != null) return asSeries(entry)

        if (group != null) {
            if (group.mentionsSeries()) return asSeries(entry)
            if (group.mentionsMovies()) return Classification(MediaKind.MOVIE)
        }

        return if (entry.isLive) Classification(MediaKind.LIVE) else Classification(MediaKind.MOVIE)
    }

    /**
     * Splits `Breaking Bad S01E05 - Gray Matter` into the show, the numbers and
     * the episode's own title. Without the split, every episode becomes its own
     * top-level entry in the Series screen.
     */
    private fun asSeries(entry: M3uEntry): Classification {
        val name = entry.name
        val marker = SeriesMarker.find(name)
            ?: return Classification(MediaKind.SERIES, seriesTitle = name)

        val show = name.substring(0, marker.start).trimSeparators()
        val tail = name.substring(marker.end).trimSeparators()
        return Classification(
            kind = MediaKind.SERIES,
            // A name that is *only* a marker ("S01E05") still belongs to a show;
            // falling back to the full name keeps it addressable instead of blank.
            seriesTitle = show.ifEmpty { name },
            seasonNumber = marker.season,
            episodeNumber = marker.episode,
            episodeTitle = tail.ifEmpty { null },
        )
    }

    /**
     * Whether a label names radio rather than television.
     *
     * Public because Xtream has no radio endpoint: stations arrive in live
     * categories, and the category name is the only signal there is.
     */
    fun isRadioLabel(label: String): Boolean = label.mentionsRadio()

    private fun isRadio(entry: M3uEntry, group: String?): Boolean =
        group?.mentionsRadio() == true || hasAudioExtension(entry.url)

    /** `…/stream.mp3?token=x` → true. Query strings are ignored. */
    private fun hasAudioExtension(url: String): Boolean {
        var end = url.length
        for (i in url.indices) {
            val c = url[i]
            if (c == '?' || c == '#') { end = i; break }
        }
        val dot = url.lastIndexOf('.', end - 1)
        if (dot < 0 || dot >= end - 1) return false
        // Longest audio extension we accept is 4 characters ("aacp", "flac").
        if (end - dot - 1 > 4) return false
        for (ext in AUDIO_EXTENSIONS) {
            if (end - dot - 1 == ext.length && url.regionMatches(dot + 1, ext, 0, ext.length, ignoreCase = true)) {
                return true
            }
        }
        return false
    }

    private fun pathKind(url: String): PathKind? = when {
        url.contains("/series/", ignoreCase = true) -> PathKind.SERIES
        url.contains("/movie/", ignoreCase = true) ||
            url.contains("/movies/", ignoreCase = true) -> PathKind.MOVIE
        url.contains("/live/", ignoreCase = true) -> PathKind.LIVE
        else -> null
    }

    private enum class PathKind { LIVE, MOVIE, SERIES }

    private fun String.mentionsSeries(): Boolean = containsAnyOf(SERIES_WORDS)
    private fun String.mentionsMovies(): Boolean = containsAnyOf(MOVIE_WORDS)

    /**
     * Radio is matched as a *word*, unlike the other hints.
     *
     * Substring matching is right for movies and series — providers write
     * "Filmes", "Séries", "مسلسلات", and clipping those would lose whole
     * catalogues. It is wrong for radio, because "Radiohead" is a music group
     * and its concert films are not radio stations. A plural `s` is still
     * allowed, so "Radios" matches.
     */
    private fun String.mentionsRadio(): Boolean {
        for (word in RADIO_WORDS) {
            var from = 0
            while (true) {
                val at = indexOf(word, from, ignoreCase = true)
                if (at < 0) break
                val before = at == 0 || !this[at - 1].isLetter()
                var after = at + word.length
                if (after < length && (this[after] == 's' || this[after] == 'S')) after++
                val ended = after >= length || !this[after].isLetter()
                if (before && ended) return true
                from = at + 1
            }
        }
        return false
    }

    private fun String.containsAnyOf(words: Array<String>): Boolean {
        for (word in words) if (contains(word, ignoreCase = true)) return true
        return false
    }

    /** Trims the separators providers put around markers: ` - `, `:`, `|`, `.`. */
    private fun String.trimSeparators(): String =
        trim { it == ' ' || it == '-' || it == '–' || it == ':' || it == '|' || it == '.' || it == '_' }

    private val AUDIO_EXTENSIONS = arrayOf("mp3", "aac", "aacp", "ogg", "opus", "m4a", "flac", "wav")

    // Arabic terms are included because a large share of providers label groups
    // in Arabic only, and the alternative is filing every one of those rows as
    // a live channel.
    private val SERIES_WORDS = arrayOf("series", "serie", "tv show", "shows", "مسلسل")
    private val MOVIE_WORDS = arrayOf("movie", "movies", "film", "vod", "cinema", "فيلم", "افلام", "أفلام")
    private val RADIO_WORDS = arrayOf("radio", "راديو", "اذاعة", "إذاعة")
}

/** What the classifier concluded. Series fields are null for every other kind. */
data class Classification(
    val kind: MediaKind,
    val seriesTitle: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    /** The episode's own title, with the show name and marker removed. */
    val episodeTitle: String? = null,
)

/**
 * Finds a season/episode marker inside a title.
 *
 * Recognises `S01E05`, `S1 E5`, `S01.E05`, `S01 Ep05`, `1x05` and
 * `Season 1 Episode 5`.
 *
 * The `NxNN` form requires at least two episode digits, which is what keeps
 * channel names like `MBC 4x4` or `Sport 2x2` from being read as episodes —
 * a false positive there would move a live channel into the Series screen.
 */
internal object SeriesMarker {

    data class Marker(val season: Int, val episode: Int, val start: Int, val end: Int)

    fun find(name: String): Marker? {
        for (i in name.indices) {
            if (!startsToken(name, i)) continue
            val c = name[i]
            when {
                c == 's' || c == 'S' -> {
                    sxxExx(name, i)?.let { return it }
                    seasonWord(name, i)?.let { return it }
                }
                c in '0'..'9' -> nxNN(name, i)?.let { return it }
            }
        }
        return null
    }

    /** A marker must start a token, so `Sports` never reads as season 0. */
    private fun startsToken(name: String, at: Int): Boolean {
        if (at == 0) return true
        val prev = name[at - 1]
        return !prev.isLetterOrDigit()
    }

    /** `S01E05`, `S1 E5`, `S01.Ep05`. */
    private fun sxxExx(name: String, at: Int): Marker? {
        var i = at + 1
        val seasonEnd = digitsEnd(name, i)
        if (seasonEnd == i || seasonEnd - i > 3) return null
        val season = intAt(name, i, seasonEnd)
        i = skipSeparators(name, seasonEnd)
        if (i >= name.length) return null
        if (name[i] != 'e' && name[i] != 'E') return null
        i++
        // "Ep05" is as common as "E05".
        if (i < name.length && (name[i] == 'p' || name[i] == 'P')) i++
        i = skipSeparators(name, i)
        val episodeEnd = digitsEnd(name, i)
        if (episodeEnd == i || episodeEnd - i > 4) return null
        return Marker(season, intAt(name, i, episodeEnd), at, episodeEnd)
    }

    /** `Season 1 Episode 5`, `Season 1 E5`. */
    private fun seasonWord(name: String, at: Int): Marker? {
        if (!name.regionMatches(at, "season", 0, 6, ignoreCase = true)) return null
        var i = skipSeparators(name, at + 6)
        val seasonEnd = digitsEnd(name, i)
        if (seasonEnd == i || seasonEnd - i > 3) return null
        val season = intAt(name, i, seasonEnd)
        i = skipSeparators(name, seasonEnd)
        i += when {
            name.regionMatches(i, "episode", 0, 7, ignoreCase = true) -> 7
            name.regionMatches(i, "ep", 0, 2, ignoreCase = true) -> 2
            i < name.length && (name[i] == 'e' || name[i] == 'E') -> 1
            else -> return null
        }
        i = skipSeparators(name, i)
        val episodeEnd = digitsEnd(name, i)
        if (episodeEnd == i || episodeEnd - i > 4) return null
        return Marker(season, intAt(name, i, episodeEnd), at, episodeEnd)
    }

    /** `1x05` — two episode digits minimum, deliberately. */
    private fun nxNN(name: String, at: Int): Marker? {
        val seasonEnd = digitsEnd(name, at)
        if (seasonEnd - at > 2) return null
        if (seasonEnd >= name.length) return null
        if (name[seasonEnd] != 'x' && name[seasonEnd] != 'X') return null
        val episodeStart = seasonEnd + 1
        val episodeEnd = digitsEnd(name, episodeStart)
        if (episodeEnd - episodeStart < 2 || episodeEnd - episodeStart > 4) return null
        // A trailing letter means it was never a marker: "1x05p" or "4x4mm".
        if (episodeEnd < name.length && name[episodeEnd].isLetter()) return null
        return Marker(intAt(name, at, seasonEnd), intAt(name, episodeStart, episodeEnd), at, episodeEnd)
    }

    private fun digitsEnd(name: String, from: Int): Int {
        var i = from
        while (i < name.length && name[i] in '0'..'9') i++
        return i
    }

    /** Parses without `substring` or `toInt` — no allocation on the hot path. */
    private fun intAt(name: String, from: Int, to: Int): Int {
        var value = 0
        for (i in from until to) value = value * 10 + (name[i] - '0')
        return value
    }

    private fun skipSeparators(name: String, from: Int): Int {
        var i = from
        while (i < name.length) {
            val c = name[i]
            if (c == ' ' || c == '.' || c == '-' || c == '_' || c == ':') i++ else break
        }
        return i
    }
}
