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
 * ## The name has to be the name, not a word in it
 *
 * This used to accept a result whose title *contained* every word of the query, and that is
 * not identification — it is a keyword search wearing a filter's clothes. A query of
 * `Pursuit` contains itself in *Cold Pursuit* and in *The Pursuit of Happyness*, so all
 * three came back and two of them were somebody else's film.
 *
 * So a catalogue title has to *equal* the query, after both are reduced to a comparable
 * form. An uploader's release name — free text, not a title — is held to a whole-word
 * prefix instead: `Friends complete pack` is plausibly *Friends*, and `Cold Pursuit` is not
 * plausibly *Pursuit*, because a release name puts the title first.
 *
 * The cost is a wrong rejection now and then, when a catalogue lists a work under a name a
 * provider does not use. That cost is paid deliberately: the search box is editable, the
 * hash exemption below is unconditional, and being told "no subtitles available" is a
 * smaller failure than being shown four subtitles for a different film and spending a
 * metered daily download to find out.
 *
 * ## The one thing that is never filtered
 *
 * A hash match. [SubtitleOffer.matchesThisFile] means somebody uploaded that subtitle
 * against a file with these exact bytes, which is evidence about *this file* and not about
 * anybody's spelling. It outranks every rule below, and it has to: a film held under an
 * Arabic name and catalogued under an English one would otherwise be filtered down to
 * nothing while the perfect answer sat in the list.
 *
 * Pure Kotlin. Every rule is a decision about two strings and two numbers.
 */
object SubtitleMatch {

    /**
     * [offers] reduced to the ones that are about [query], best first.
     *
     * The order is the recommendation, and a viewer takes the top row: the subtitle timed
     * against these exact bytes, then the one whose year agrees with what was worked out,
     * then the one most people have used.
     */
    fun relevant(query: SubtitleQuery, offers: List<SubtitleOffer>): List<SubtitleOffer> =
        ranked(query, offers.filter { keep(query, it) })

    /**
     * The same, for results the server returned *for a resolved feature id*.
     *
     * The name is not checked, because it has already been answered better than a comparison
     * of strings can answer it: these results were asked for by the catalogue's own
     * identifier for the work. Rechecking the title here would only be able to reject a right
     * answer for being spelled differently.
     *
     * The episode still is checked. A series' id covers every episode of it, and the viewer
     * is watching one.
     */
    fun ofFeature(query: SubtitleQuery, offers: List<SubtitleOffer>): List<SubtitleOffer> =
        ranked(query, offers.filter { it.matchesThisFile || episodeAgrees(query, it) })

    /**
     * Whether a catalogue entry is the work [query] names.
     *
     * The gate on resolution, and it is the same equality the results are held to — because
     * `/features` is a search as well, and asked for `Pursuit` it offers *Cold Pursuit*
     * among the answers. Taking a catalogue's first row on trust would be a keyword search
     * with an extra round trip in front of it.
     */
    fun sameWork(query: SubtitleQuery, title: String, year: Int?): Boolean {
        if (key(title) != key(query.title)) return false
        val wanted = query.year ?: return true
        val got = year ?: return true
        return kotlin.math.abs(wanted - got) <= YEAR_TOLERANCE
    }

    /**
     * Whether one result is about [query].
     *
     * Three gates, and all three have to pass: the name, the year, the episode. The year and
     * the episode let something through when they have nothing to judge on — an offer that
     * declares no year is not evidence of the wrong year — because the cost of a wrong
     * rejection there is a viewer told that no subtitle exists when one does.
     *
     * The name is not so forgiving, and that asymmetry is the design. A missing year is a
     * gap in a record; a different name is a different film.
     */
    fun keep(query: SubtitleQuery, offer: SubtitleOffer): Boolean {
        if (offer.matchesThisFile) return true
        if (query.isBlank) return true

        val wanted = key(query.title)
        if (wanted.isBlank()) return true
        if (!names(offer, wanted)) return false

        return yearAgrees(query, offer) && episodeAgrees(query, offer)
    }

    /**
     * Whether this result's own account of what it is for names the same work.
     *
     * Two sources, held to two standards, because they are two different kinds of claim:
     *
     *  - **The catalogue's title** — OpenSubtitles' own identification of the work, in
     *    `feature_details`. Equality. It is a title, so it can be compared as one, and a
     *    catalogue that says *Cold Pursuit* is not saying *Pursuit*.
     *  - **The uploader's release name**, when the catalogue said nothing. A prefix, in whole
     *    words. It is free text with the title at the front and the encoding behind it, so
     *    *Friends complete pack* is plausibly *Friends* — while *Cold Pursuit*, which puts
     *    another word first, is not plausibly *Pursuit*.
     */
    private fun names(offer: SubtitleOffer, wanted: String): Boolean {
        val catalogued = listOf(offer.parentTitle, offer.featureTitle).filter { it.isNotBlank() }
        if (catalogued.isNotEmpty()) return catalogued.any { key(it) == wanted }

        val release = key(SubtitleQuery.parse(offer.release).title)
        return release == wanted || release.startsWith("$wanted ")
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

        val stated = if (offer.season != null && offer.episode != null) {
            offer.season to offer.episode
        } else {
            SubtitleQuery.parse(offer.release).let { parsed ->
                if (parsed.isEpisode) parsed.season to parsed.episode else null
            }
        } ?: return true

        return stated.first == query.season && stated.second == query.episode
    }

    /**
     * Best first: this file's own subtitle, then the right year, then the popular one.
     *
     * `sortedWith` is stable, so results the ranking cannot separate stay in the order
     * OpenSubtitles put them in — which is its own relevance ranking and better than nothing.
     */
    private fun ranked(query: SubtitleQuery, offers: List<SubtitleOffer>): List<SubtitleOffer> =
        offers.sortedWith(
            compareByDescending<SubtitleOffer> { it.matchesThisFile }
                .thenByDescending { query.year != null && it.year == query.year }
                .thenByDescending { it.downloads },
        )

    /**
     * A title reduced to what two catalogues can be expected to agree on.
     *
     * Case, punctuation and spacing go, because *Spider-Man*, *Spider Man* and *SPIDER-MAN*
     * are one film. A leading article goes, because a provider writing *Matrix 1999* and a
     * catalogue writing *The Matrix* are also one film, and dropping it from both sides is
     * the only way to say so without loosening the comparison for anything else.
     *
     * Nothing else is removed. Every further relaxation is a way for a different film to
     * compare equal.
     */
    private fun key(title: String): String {
        val words = normalise(title).split(' ').filter { it.isNotBlank() }
        val named = if (words.size > 1 && words.first() in ARTICLES) words.drop(1) else words
        return named.joinToString(" ")
    }

    /** Lower case, and everything that is not a letter or a digit is a space. */
    private fun normalise(text: String): String = buildString(text.length) {
        text.lowercase().forEach { append(if (it.isLetterOrDigit()) it else ' ') }
    }.replace(WHITESPACE, " ").trim()

    private val WHITESPACE = Regex("""\s+""")

    private val ARTICLES = setOf("the", "a", "an", "le", "la", "les", "el", "il", "ال")

    private const val YEAR_TOLERANCE = 1
}
