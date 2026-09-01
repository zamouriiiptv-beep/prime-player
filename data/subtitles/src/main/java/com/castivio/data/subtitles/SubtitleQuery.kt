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
 * pressed. This type is that title, parsed once, into the questions the API and the
 * relevance filter both need answered.
 *
 * ## Why it parses rather than trusting
 *
 * Because the title from a library is not a title. It is a listing, written to be clicked:
 *
 *     PURSUIT -- 2026 Jason Statham Full Action Movie
 *     The.Matrix.1999.1080p.BluRay.x264-GROUP.mkv
 *
 * Neither finds anything, and for the same reason: everything after the name is about the
 * copy, the cast or the sale, and none of it is in anybody's subtitle catalogue. The first
 * of those two produced a search that returned nothing at all while a subtitle for *Pursuit*
 * sat on the site.
 *
 * ## What it cuts on, and what it does not
 *
 * Boundaries, not vocabulary. There is no list of actors here and there cannot be one — the
 * names that follow a title are unbounded, and a parser that tried to recognise them would
 * be wrong about a film called *Statham* forever. What is bounded is the small set of
 * *structures* that mark where a name has ended: a release year with words after it, a
 * quality or codec marker, a sales phrase like "Full Action Movie". Cut at the first of
 * those and everything unbounded goes with it, unnamed.
 *
 * Pure Kotlin, no Android, no I/O. Every rule below is a decision about text that somebody
 * will want to change when a provider names its files differently, and all of them are
 * testable against a string.
 */
data class SubtitleQuery(
    /**
     * The name, and nothing else: no year, no cast, no release, no episode marker.
     *
     * The year is held in [year] rather than here because it is a number to compare and not
     * a word to find. Sending it as text asks OpenSubtitles to match "1999" inside a title,
     * which is a different question from the one its `year` field answers.
     */
    val title: String,
    val season: Int? = null,
    val episode: Int? = null,
    val year: Int? = null,
    /**
     * The title without its subtitle: "Blade Runner" of "Blade Runner: The Final Cut".
     *
     * The last thing tried before giving up. A catalogue lists a work under one of the two
     * forms and a provider names the file with the other, and which way round it falls is
     * not knowable from here — so both are asked, in order, most specific first.
     *
     * Equal to [title] when there is no subtitle, which is what makes the ladder in
     * [attempts] collapse to one rung for most things.
     */
    val base: String = title,
) {

    /** Whether this names one episode of something rather than a whole work. */
    val isEpisode: Boolean get() = season != null && episode != null

    /** Nothing to search for. The sheet shows the box and waits rather than asking. */
    val isBlank: Boolean get() = title.isBlank()

    /**
     * What the search box shows, and what [parse] turns back into this.
     *
     * `S05E02` rather than the several other ways the marker was written, because the box is
     * also an editable field: a viewer correcting the season needs to see a form they can
     * edit without guessing which of them this build understands.
     */
    val text: String
        get() = when {
            season != null && episode != null -> "$title S%02dE%02d".format(season, episode)
            else -> title
        }

    /**
     * The searches to try, in order, until one of them finds something.
     *
     * Each rung drops one assumption, and drops it from the acceptance as well as from the
     * question — because the rung before it proved the assumption unproductive. If asking
     * for this name *in this year* found nothing, the year we derived is not the year the
     * catalogue holds, and continuing to insist on it while asking without it would be
     * asking a question whose answers are thrown away.
     *
     *  1. the name, the year and the episode — everything that was worked out;
     *  2. without the year, for a catalogue that dates the work differently;
     *  3. without the subtitle, for a catalogue that lists it under the short name.
     *
     * Rungs two and three exist only when they differ from the one before, so the common
     * case is one request and the "no subtitles available" that a viewer finally sees has
     * three genuine attempts behind it rather than one.
     */
    fun attempts(): List<SubtitleQuery> = listOfNotNull(
        this,
        copy(year = null).takeIf { year != null },
        copy(title = base, base = base, year = null).takeIf { base != title },
    ).filterNot { it.isBlank }

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
         * The order of the steps is the whole of it. The episode marker is lifted before
         * anything is cut, because `S05E02` sits before `720p` in a release name and cutting
         * first would take the marker with it. The sales phrase goes before the year, because
         * "Full Movie 2026" puts them the other way round from "2026 Full Movie". The release
         * markers go last, on what is left.
         *
         * Every cut refuses to leave nothing behind. A title that is *entirely* one of these
         * shapes — a film called *Trailer*, one called *4K* — keeps what it had, because a
         * query of nothing finds nothing and is the one outcome worse than a query with
         * noise in it.
         */
        fun parse(text: String): SubtitleQuery {
            val spaced = spaced(text)
            val marker = episodeIn(spaced)

            val withoutMarker = withoutEpisode(spaced)
            val sold = keeping(withoutMarker) { beforeTheSalesPitch(it) }
            val dated = yearIn(sold)
            val trimmed = keeping(sold) { cut(beforeTheYear(it, dated)) }
            val named = tidy(keeping(trimmed) { withoutTheCategory(it) })

            return SubtitleQuery(
                title = named,
                season = marker?.first,
                episode = marker?.second,
                year = dated?.second,
                base = baseOf(named),
            )
        }

        /** A step, undone when it would leave nothing at all. */
        private inline fun keeping(text: String, step: (String) -> String): String =
            step(text).takeIf { it.isNotBlank() } ?: text

        /**
         * Separators to spaces, the extension off the end, and a dash that is punctuation
         * turned into the space it is standing in for.
         *
         * A dash between words — `PURSUIT -- 2026`, `Pursuit - Full Movie` — is a separator
         * somebody typed. A dash inside a word is part of it: `Spider-Man` is a name and
         * `WEB-DL` is a marker that has to survive whole to be recognised as one.
         *
         * The extension is matched as a known video extension at the end, and only there.
         * `substringBeforeLast('.')` would turn `S.W.A.T` into `S.W.A` and take `Odyssey` off
         * `2001. A Space Odyssey`.
         */
        private fun spaced(text: String): String = text
            .replace(EXTENSION, " ")
            .replace(LOOSE_DASH, " ")
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
         * Everything from a phrase that is selling the file, removed.
         *
         * "Full Action Movie", "Official Trailer", "فيلم كامل" — the words a listing adds so
         * that a person scanning a page knows what they are being offered. They are never
         * part of a name and they are always at the end, so the phrase and its tail go.
         *
         * Phrases and not words: `movie` alone would take the end off *The Lego Movie*, and
         * `full` alone off *Full Metal Jacket*. It is the combination that means a sale.
         */
        private fun beforeTheSalesPitch(text: String): String {
            var kept = text
            SALES_PITCH.forEach { kept = it.replace(kept, " ") }
            return kept.replace(WHITESPACE, " ").trim()
        }

        /**
         * A release year, and where it sits — the strongest boundary there is.
         *
         * Everything after a release year is about the copy: the cast, the quality, the
         * uploader's pitch. So the year both ends the name and is lifted out of it.
         *
         * Two guards, and each is a real title that would otherwise be destroyed:
         *
         *  - **Not the first word.** *2001: A Space Odyssey* opens with a number that is the
         *    name, and a year at the start has nothing before it to be the end of.
         *  - **Not beyond [LATEST_YEAR].** *Blade Runner 2049* ends with a number that is the
         *    name. No file released to anybody is dated past this constant, so a number that
         *    is cannot be a release year — and reading it as one both truncated the title and
         *    filtered out the real film, whose catalogue year is 2017.
         */
        private fun yearIn(text: String): Pair<Int, Int>? {
            val words = words(text)
            for (index in 1 until words.size) {
                val year = words[index].toIntOrNull() ?: continue
                if (year in EARLIEST_YEAR..LATEST_YEAR) return index to year
            }
            return null
        }

        private fun beforeTheYear(text: String, dated: Pair<Int, Int>?): String =
            if (dated == null) text else words(text).take(dated.first).joinToString(" ")

        /**
         * Everything from the first release marker onwards, removed.
         *
         * A release name is ordered — name, year, then how it was made — so the first word
         * that describes the encoding is where the name ended. Cutting is better than
         * removing the noise words one by one because it also takes the things no list can
         * enumerate: the group tag, the tracker's suffix, the `5 1` left behind by `DTS-HD
         * 5.1` once the dot became a space.
         */
        private fun cut(text: String): String {
            val words = words(text)
            val end = words.indexOfFirst { it.lowercase() in NOISE || RESOLUTION.matches(it) }
            return if (end < 0) text else words.take(end).joinToString(" ")
        }

        /**
         * The word an Arabic listing opens with to say what kind of thing this is.
         *
         * `فيلم الاختيار` and `مسلسل الاختيار` are "the film *The Choice*" and "the series
         * *The Choice*" — the first word is the shelf, not the name, and it is on the front of
         * a great many rows in an Arabic playlist. Leading only: a word in the middle of a
         * title is part of it.
         */
        private fun withoutTheCategory(text: String): String =
            text.replace(LEADING_CATEGORY, "").trim()

        /**
         * Punctuation left at either end, and a title that is shouting.
         *
         * `PURSUIT` is a listing's emphasis rather than a spelling, and the box is read by a
         * person deciding whether the guess is right. Only when the whole of what is left is
         * upper case: a single shouted word inside an ordinary title — *The LEGO Movie* — is
         * how the thing is actually written.
         *
         * Cosmetic only. Matching lower-cases both sides and OpenSubtitles does not care.
         */
        private fun tidy(text: String): String {
            val trimmed = text.replace(WHITESPACE, " ").trim().trim(*EDGES)
            if (trimmed.any { it.isLowerCase() }) return trimmed
            return trimmed.split(' ').joinToString(" ") { word ->
                // Only a word that starts with a letter. `4K` is not shouting, and lowering
                // it produced `4k`, which is a different-looking answer to no question.
                if (word.length > 1 && word.first().isLetter()) {
                    word.first() + word.drop(1).lowercase()
                } else {
                    word
                }
            }
        }

        /**
         * The name before its subtitle.
         *
         * A colon only. " - " was a candidate and is not usable: by this point every loose
         * dash has already become a space, and it had to — `PURSUIT -- 2026` needs the dashes
         * gone before anything else can read the line.
         */
        private fun baseOf(title: String): String =
            title.substringBefore(':').trim().ifBlank { title }

        private fun words(text: String): List<String> = text.split(' ').filter { it.isNotBlank() }

        private val EXTENSION = Regex(
            """\.(mkv|mp4|m4v|avi|mov|ts|m2ts|mts|wmv|flv|webm|mpe?g|3gp|ogv|vob|divx|rmvb|asf)$""",
            RegexOption.IGNORE_CASE,
        )

        /** Dots, underscores, brackets and the bullets a library puts between facts. */
        private val SEPARATORS = Regex("""[._\[\]{}()·•|,;]+""")

        /** A dash standing between words, or doubled. Never one inside a word. */
        private val LOOSE_DASH = Regex("""\s+[-–—]+\s*|\s*[-–—]+\s+|[-–—]{2,}""")

        private val WHITESPACE = Regex("""\s+""")
        private val RESOLUTION = Regex("""\d{3,4}[pi]""", RegexOption.IGNORE_CASE)
        private val EDGES = charArrayOf('-', '_', '.', ',', ':', '·', ' ', '–', '—')

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

        private val LEADING_CATEGORY = Regex("""^(فيلم|مسلسل|برنامج|مسرحية|حلقة)\s+""")

        /** What a listing says to sell the file. Each takes everything after it as well. */
        private val SALES_PITCH = listOf(
            Regex("""\bfull\s+(\S+\s+){0,2}(movie|film|episode|series)\b.*""", RegexOption.IGNORE_CASE),
            Regex("""\b(official\s+)?(trailer|teaser)\b.*""", RegexOption.IGNORE_CASE),
            Regex("""\b(hd|4k)\s+(movie|film)\b.*""", RegexOption.IGNORE_CASE),
            Regex("""\bwatch\s+(online|now|free)\b.*""", RegexOption.IGNORE_CASE),
            Regex("""فيلم\s+كامل.*"""),
            Regex("""حلقة\s+كاملة.*"""),
            Regex("""مسلسل\s+كامل.*"""),
            Regex("""\bحصريا\b.*"""),
            Regex("""\bمترجم\s+كامل\b.*"""),
        )

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
            // The Arabic equivalents, which a playlist puts after the name for the same
            // reason: "complete", "subtitled", "exclusively", "in high quality", "online".
            "كامل", "كاملة", "مترجم", "مترجمة", "حصريا", "حصرياً", "بجودة", "اونلاين", "مشاهدة", "تحميل",
        )

        private const val EARLIEST_YEAR = 1900

        /**
         * Past which a four-figure number is part of a name and not a date.
         *
         * A release year is a fact about a file that exists, so it cannot be far in the
         * future — while *Blade Runner 2049*, *Space 2100* and every other title that ends in
         * a number are here now. Far enough ahead to outlast this build; far enough behind
         * 2049 to leave it alone.
         */
        private const val LATEST_YEAR = 2035
    }
}
