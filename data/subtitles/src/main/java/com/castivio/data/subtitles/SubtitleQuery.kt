package com.castivio.data.subtitles

/**
 * What is being searched for: a name, and — when the thing is an episode — which episode.
 *
 * ## Why this type exists at all
 *
 * The search used to be given `url.substringAfterLast('/')`. For a file that is a passable
 * guess and for an IPTV stream it is a catastrophe: `http://host/user/pass/502` ends in
 * `502`, so the player asked OpenSubtitles for "502" and got back every episode numbered 502
 * of every series ever uploaded. The results were real results and they were for *Friends*
 * and *The Office*, which is how a search can return two hundred rows and be entirely wrong.
 *
 * The fix is not a better substring. It is to stop asking the URL — a URL is a route to
 * bytes and was never a claim about what the bytes are — and to ask the thing that actually
 * knows: the title the request was opened with, which came from the catalogue row the viewer
 * pressed. This type is that title, parsed once, into the two questions the API and the
 * relevance filter both need answered.
 *
 * ## Why it parses rather than trusting
 *
 * Because the title from a library is not a clean title. It is `The.Matrix.1999.1080p.
 * BluRay.x264-GROUP.mkv` as often as it is `The Matrix`, and a search for the former returns
 * nothing at all — the release noise is not in anybody's subtitle metadata. So the noise
 * comes off, the episode marker is lifted out into its own fields where the API can be told
 * about it properly, and what is left is a name.
 *
 * Pure Kotlin, no Android, no I/O. Every rule below is a decision about text that somebody
 * will want to change when a provider names its files differently, and all of them are
 * testable against a string.
 */
data class SubtitleQuery(
    /**
     * The name, cleaned, with any episode marker removed and any year kept.
     *
     * The year stays in because it is part of how a film is named and it narrows the search
     * usefully; it is also lifted into [year] so the filter can compare it as a number
     * rather than as one more word that has to appear.
     */
    val title: String,
    val season: Int? = null,
    val episode: Int? = null,
    val year: Int? = null,
) {

    /** Whether this names one episode of something rather than a whole work. */
    val isEpisode: Boolean get() = season != null && episode != null

    /** Nothing to search for. The sheet shows the box and waits rather than asking. */
    val isBlank: Boolean get() = title.isBlank()

    /**
     * What the search box shows, and what [parse] turns back into this.
     *
     * `S05E02` rather than the twelve other ways the marker was written, because the box is
     * also an editable field: a viewer correcting the season needs to see a form they can
     * edit without guessing which of the twelve this build understands.
     */
    val text: String
        get() = when {
            season != null && episode != null -> "$title S%02dE%02d".format(season, episode)
            else -> title
        }

    companion object {

        /**
         * The query for a request the player was opened with.
         *
         * [subtitle] is consulted for the episode marker when the title has none, because the
         * two fields split differently depending on what opened the player: a library row may
         * put "The Office" in one and "S05E02" in the other, and a file's name puts both in
         * the title.
         */
        fun of(title: String, subtitle: String? = null): SubtitleQuery {
            val parsed = parse(title)
            if (parsed.isEpisode || subtitle.isNullOrBlank()) return parsed

            // The name comes from the title in every case. Only the numbers are taken from
            // the subtitle, and only when the title did not carry them — a subtitle line
            // reading "Season 5 · Episode 2" is about this episode, but "Drama · 2009" is
            // not a name and must never become one.
            val fromSubtitle = episodeIn(subtitle) ?: return parsed
            return parsed.copy(season = fromSubtitle.first, episode = fromSubtitle.second)
        }

        /**
         * A line of text as a query — used for what the viewer types as much as for a title.
         *
         * The order of the steps is the whole of it. The episode marker is lifted before the
         * noise is cut, because `S05E02` sits *before* `720p` in a release name and cutting
         * first would take the marker with it; the noise is cut before the year is read,
         * because `1080p` is not a year and `2009` in `2009.BluRay` is.
         */
        fun parse(text: String): SubtitleQuery {
            val words = spaced(text)
            val marker = episodeIn(words)
            val named = trimmed(cut(withoutEpisode(words)))
            return SubtitleQuery(
                title = named,
                season = marker?.first,
                episode = marker?.second,
                year = yearIn(named),
            )
        }

        /**
         * Separators to spaces, and the extension off the end.
         *
         * Only a known video extension, and only at the end: `substringBeforeLast('.')` would
         * turn `S.W.A.T` into `S.W.A` and `2001. A Space Odyssey` into nothing useful.
         */
        private fun spaced(text: String): String = text
            .replace(EXTENSION, " ")
            .replace(SEPARATORS, " ")
            .replace(WHITESPACE, " ")
            .trim()

        /** The season and episode written anywhere in [text], in any of the forms below. */
        private fun episodeIn(text: String): Pair<Int, Int>? {
            val spaced = spaced(text)
            for (pattern in EPISODE_PATTERNS) {
                val found = pattern.find(spaced) ?: continue
                val season = found.groupValues[1].toIntOrNull() ?: continue
                val episode = found.groupValues[2].toIntOrNull() ?: continue
                return season to episode
            }
            // Arabic names them separately and in either order, so the two are read
            // independently and only count as a marker when both are present.
            val season = ARABIC_SEASON.find(spaced)?.groupValues?.get(1)?.toIntOrNull()
            val episode = ARABIC_EPISODE.find(spaced)?.groupValues?.get(1)?.toIntOrNull()
            return if (season != null && episode != null) season to episode else null
        }

        private fun withoutEpisode(text: String): String {
            var stripped = text
            EPISODE_PATTERNS.forEach { stripped = it.replace(stripped, " ") }
            stripped = ARABIC_SEASON.replace(stripped, " ")
            stripped = ARABIC_EPISODE.replace(stripped, " ")
            return stripped
        }

        /**
         * Everything from the first release marker onwards, removed.
         *
         * A release name is ordered — name, year, then how it was made — so the first word
         * that describes the encoding is where the name ended. Cutting is better than
         * removing the noise words one by one because it also takes the things no list can
         * enumerate: the group tag, the tracker's suffix, the `5 1` left behind by `DTS-HD
         * 5.1` once the dot became a space.
         *
         * Nothing is cut when the first word is itself a marker: `Heat` is a film and `4K` is
         * not a name, but a query of nothing at all is worse than a query with noise in it.
         */
        private fun cut(text: String): String {
            val words = text.split(' ').filter { it.isNotBlank() }
            val end = words.indexOfFirst { it.lowercase() in NOISE || RESOLUTION.matches(it) }
            return when (end) {
                -1 -> words
                0 -> words
                else -> words.take(end)
            }.joinToString(" ")
        }

        /** Punctuation left at either end once the middle was taken out. */
        private fun trimmed(text: String): String =
            text.replace(WHITESPACE, " ").trim().trim('-', '_', '.', ',', ':', '·', ' ')

        /** A four-figure year, which is the only number in a title that means something. */
        private fun yearIn(text: String): Int? = YEAR.findAll(text)
            .mapNotNull { it.value.toIntOrNull() }
            .lastOrNull { it in EARLIEST_YEAR..LATEST_YEAR }

        private val EXTENSION = Regex(
            """\.(mkv|mp4|m4v|avi|mov|ts|m2ts|mts|wmv|flv|webm|mpe?g|3gp|ogv|vob|divx|rmvb|asf)$""",
            RegexOption.IGNORE_CASE,
        )

        /**
         * Dots, underscores, brackets and the bullets a library puts between facts.
         *
         * Hyphens stay: `Spider-Man` is a name, and `web-dl` is caught by the noise list
         * whole rather than by being broken in half.
         */
        private val SEPARATORS = Regex("""[._\[\]{}()·•|,;]+""")
        private val WHITESPACE = Regex("""\s+""")
        private val YEAR = Regex("""(?<!\d)\d{4}(?!\d)""")
        private val RESOLUTION = Regex("""\d{3,4}[pi]""", RegexOption.IGNORE_CASE)

        /**
         * The written forms of "season five, episode two", most specific first.
         *
         * `1x02` is last and guarded on both sides, because it is the one that can match
         * something else: without the guards it finds `20x108` inside `1920x1080`.
         */
        private val EPISODE_PATTERNS = listOf(
            Regex("""(?<![a-z0-9])season\s*(\d{1,2})\s*-?\s*episode\s*(\d{1,3})(?!\d)""", RegexOption.IGNORE_CASE),
            Regex("""(?<![a-z0-9])s\s*(\d{1,2})\s*-?\s*e\s*(\d{1,3})(?!\d)""", RegexOption.IGNORE_CASE),
            Regex("""(?<![a-z0-9])(\d{1,2})x(\d{1,3})(?!\d)""", RegexOption.IGNORE_CASE),
        )

        private val ARABIC_SEASON = Regex("""الموسم\s*(\d{1,2})""")
        private val ARABIC_EPISODE = Regex("""الحلقة\s*(\d{1,3})""")

        /**
         * Words that mean the name has ended.
         *
         * Every one of them describes how a copy was made rather than what it is of. The list
         * is not exhaustive and does not need to be: it only has to contain the first such
         * word in a typical release name, because everything after the cut goes with it.
         */
        private val NOISE = setOf(
            "480p", "576p", "720p", "1080p", "1440p", "2160p", "4k", "8k", "uhd", "hdr", "hdr10", "sdr",
            "x264", "x265", "h264", "h265", "avc", "hevc", "xvid", "divx", "10bit", "8bit",
            "bluray", "blu-ray", "brrip", "bdrip", "bdremux", "dvdrip", "dvdscr", "dvd",
            "webrip", "web-dl", "webdl", "hdtv", "pdtv", "hdcam", "camrip", "telesync", "hdrip",
            "aac", "ac3", "eac3", "dts", "dts-hd", "truehd", "atmos", "dd5", "ddp5", "flac", "mp3",
            "remux", "proper", "repack", "extended", "uncut", "unrated", "limited", "internal",
            "multi", "dual", "dubbed", "subbed", "hardsub", "softsub", "www",
        )

        private const val EARLIEST_YEAR = 1900
        private const val LATEST_YEAR = 2100
    }
}
