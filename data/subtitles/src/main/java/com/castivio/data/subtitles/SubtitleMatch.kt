package com.castivio.data.subtitles

/**
 * Whether a result is a result *for this film*, which is not the same question as whether
 * the server returned it.
 *
 * ## Why a client-side filter exists over a server-side search
 *
 * Because OpenSubtitles' search is a search and not a lookup: it answers a text query with
 * what it has, ranked, and it will happily answer a bad query well. Asked for `502` it
 * returns episode 502 of everything, and every one of those rows is a genuine subtitle that
 * genuinely matches what was asked. The server did nothing wrong. The question the viewer
 * asked was "subtitles for what I am watching", and only this side knows what that is.
 *
 * So the results are checked against the query before they are shown, and what does not
 * match is not shown at all — an empty list and "no subtitles available" is a true answer
 * that a viewer can act on, and a list of subtitles for other programmes is a false one that
 * wastes a download from a metered daily allowance to find out.
 *
 * ## The one thing that is never filtered
 *
 * A hash match. [SubtitleOffer.matchesThisFile] means somebody uploaded that subtitle
 * against a file with these exact bytes, which is evidence about *this file* and not about
 * anybody's spelling. It outranks every rule below, and it has to: a film held under an
 * Arabic name whose subtitles are all catalogued under an English one would otherwise be
 * filtered down to nothing while the perfect answer sat in the list.
 *
 * Pure Kotlin. Every rule is a decision about two strings and two numbers.
 */
object SubtitleMatch {

    /** [offers] reduced to the ones that are about [query], in the order they arrived. */
    fun relevant(query: SubtitleQuery, offers: List<SubtitleOffer>): List<SubtitleOffer> =
        offers.filter { keep(query, it) }

    /**
     * Whether one result is about [query].
     *
     * Three gates, cheapest first, and all three have to pass: the name, the year, the
     * episode. Each is written to let something through when it has nothing to judge on —
     * an offer that declares no year is not evidence of the wrong year — because the cost of
     * a wrong rejection is a viewer who is told no subtitle exists when one does, and that
     * is a worse failure than one extra row.
     */
    fun keep(query: SubtitleQuery, offer: SubtitleOffer): Boolean {
        if (offer.matchesThisFile) return true
        if (query.isBlank) return true

        val wanted = significant(query)
        if (wanted.isEmpty()) return true

        val describes = padded(offer.describes)
        if (describes.isBlank()) return false
        if (wanted.any { !describes.hasWord(it) }) return false

        return yearAgrees(query, offer) && episodeAgrees(query, offer)
    }

    /**
     * The words of the title that a result has to contain, normalised for comparison.
     *
     * The year comes out — it is checked as a number, and requiring "1999" to appear in a
     * feature title that reads "The Matrix" would reject the right answer. Bare numbers come
     * out for the same reason: what survives the release-name cut is `2` of `DTS 2 0` far
     * more often than it is anything anybody named a film.
     *
     * Articles come out only when something is left without them. "The One" is a series.
     */
    private fun significant(query: SubtitleQuery): List<String> {
        val words = normalise(query.title)
            .split(' ')
            .filter { it.isNotBlank() && it.toIntOrNull() == null }
        val named = words.filter { it !in ARTICLES }
        return named.ifEmpty { words }
    }

    /**
     * A year, when both sides state one, within a year of each other.
     *
     * The tolerance is not slack. A film finished in one year and released in the next is
     * catalogued under either depending on who catalogued it, and this rule exists to tell
     * *The Matrix* from *The Matrix Resurrections* — twenty-two years apart — not to audit a
     * database.
     */
    private fun yearAgrees(query: SubtitleQuery, offer: SubtitleOffer): Boolean {
        val wanted = query.year ?: return true
        val got = offer.year ?: return true
        return kotlin.math.abs(wanted - got) <= YEAR_TOLERANCE
    }

    /**
     * The season and episode, when the query names one.
     *
     * A query that names no episode accepts anything: someone searching "Friends" is looking
     * for whatever there is. A query that names one is answered only by that one — this is
     * the gate that stops episode 502 of another series, which is the failure the whole
     * filter was written for.
     *
     * When the API declared no numbers, the result's own name is read for them. That is a
     * weaker source and it is used weakly: a name that states a different episode is
     * rejected, and a name that states nothing is allowed through, because a subtitle filed
     * under the right series with no episode in its name is a plausible answer and the
     * viewer can see its name in the row.
     */
    private fun episodeAgrees(query: SubtitleQuery, offer: SubtitleOffer): Boolean {
        if (!query.isEpisode) return true

        val season = offer.season ?: SubtitleQuery.parse(offer.describes).season
        val episode = offer.episode ?: SubtitleQuery.parse(offer.describes).episode
        if (season == null || episode == null) return true

        return season == query.season && episode == query.episode
    }

    /** Lower case, and everything that is not a letter or a digit is a space. */
    private fun normalise(text: String): String = buildString(text.length) {
        text.lowercase().forEach { append(if (it.isLetterOrDigit()) it else ' ') }
    }.replace(WHITESPACE, " ").trim()

    /**
     * Normalised, with a space at each end so a word can be sought whole.
     *
     * Without the padding `matrix` is found inside `matrixes` and, worse, `ar` is found
     * inside every second Arabic transliteration on the site.
     */
    private fun padded(text: String): String {
        val normalised = normalise(text)
        return if (normalised.isBlank()) "" else " $normalised "
    }

    /**
     * Word-whole containment, which is what every check above means by "contains".
     *
     * Named for what it asks rather than overloading `contains`: an extension called
     * `contains` on `String`, in scope here, would win over the standard library's inside
     * its own body and call itself for ever.
     */
    private fun String.hasWord(word: String): Boolean = contains(" $word ")

    private val WHITESPACE = Regex("""\s+""")

    private val ARTICLES = setOf("the", "a", "an", "le", "la", "les", "el", "il", "ال")

    private const val YEAR_TOLERANCE = 1
}
