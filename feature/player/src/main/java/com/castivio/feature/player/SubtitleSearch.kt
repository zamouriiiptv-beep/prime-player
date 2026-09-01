package com.castivio.feature.player

import com.castivio.data.subtitles.SubtitleFailure
import com.castivio.data.subtitles.SubtitleOffer
import com.castivio.data.subtitles.SubtitleQuery

/**
 * The languages the search offers, and why it is four and not two hundred.
 *
 * OpenSubtitles carries subtitles in about eighty languages, and a viewer who wants one of
 * the other seventy-six is not served by a list they have to scroll: they are served by
 * [Any], which searches without a language filter and shows what exists. What the short
 * list buys is the common case in one tap.
 *
 * Arabic first, and that is not alphabetical. It is this product's own language, the one
 * its interface is written in, and the one its users are most likely to be looking for.
 */
enum class SubtitleLanguage(val code: String) {
    Arabic("ar"),
    English("en"),
    French("fr"),

    /** No filter. What a viewer looking for anything else needs, in one row. */
    Any(""),
}

/**
 * What the search is doing, as one of five things.
 *
 * A sealed type rather than a handful of booleans, for the reason [Picture] is one: a
 * screen renders all of these and the compiler will not let it forget one. "Searching and
 * failed at the same time" is not a state that can be written down.
 */
sealed interface SubtitleHunt {

    /** Nothing asked for yet. The sheet shows the languages and waits. */
    data object Idle : SubtitleHunt

    data object Searching : SubtitleHunt

    /** What came back, possibly empty — which is an answer and says so. */
    data class Offers(val offers: List<SubtitleOffer>) : SubtitleHunt

    /** One is being fetched. Named so the row that was chosen can show it. */
    data class Fetching(val offer: SubtitleOffer) : SubtitleHunt

    /** Why it could not be done, in a form the sheet turns into a sentence. */
    data class Failed(val reason: SubtitleFailure) : SubtitleHunt
}

/**
 * The subtitle search, as the screen sees it.
 *
 * [available] is fixed for the life of the build: it is whether this APK was compiled with
 * credentials. Kept on the state rather than asked of the source at draw time so that the
 * sheet can be composed by a test with no Hilt graph — the same reason everything else on
 * this screen is a value.
 */
data class SubtitleSearch(
    val available: Boolean = false,
    val language: SubtitleLanguage = SubtitleLanguage.Arabic,
    val hunt: SubtitleHunt = SubtitleHunt.Idle,
    /**
     * What is being searched for, as text the viewer can edit.
     *
     * Filled from the title the player was opened with, cleaned by [SubtitleQuery] so that
     * what appears is "Pursuit" and not "PURSUIT -- 2026 Jason Statham Full Action Movie".
     * A viewer who is watching something the library named badly — or named in a language
     * OpenSubtitles does not catalogue it under — types over it, and that is the whole
     * escape hatch for every case the parsing gets wrong.
     *
     * Text and not a [SubtitleQuery] because this is what a text field holds.
     */
    val query: String = "",
    /**
     * The query worked out from the request, kept beside the text it produced.
     *
     * Not a second representation to keep in step — a record of what was known before the
     * text was flattened. The box shows a name and only a name, which is what a person wants
     * to read and correct, so the year and the episode that were worked out from the title
     * are not written in it. Parsing the box back would therefore lose them, and with the
     * year goes the check that tells *The Matrix* from *The Matrix Resurrections*.
     *
     * So an untouched box searches with everything that was derived, and an edited one
     * searches with what was typed. [asked] is that rule, in one line.
     */
    val derived: SubtitleQuery = SubtitleQuery(""),
) {
    /** The codes to send. Empty for [SubtitleLanguage.Any], which means no filter. */
    val codes: List<String> get() = listOfNotNull(language.code.takeIf { it.isNotBlank() })

    /** Whether there is anything to search for. An empty box is not a search. */
    val askable: Boolean get() = available && query.isNotBlank()

    /**
     * What to actually search with: everything derived, unless the viewer has typed over it.
     *
     * The comparison is against the text the derived query itself produced, so "untouched"
     * means untouched and a viewer who deletes a word gets a search for the words they left.
     */
    val asked: SubtitleQuery
        get() = query.trim().let { typed ->
            if (typed == derived.text) derived else SubtitleQuery.parse(typed)
        }
}
