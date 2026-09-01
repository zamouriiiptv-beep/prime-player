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
     * Text and not a [SubtitleQuery], because this is what a text field holds — and the only
     * record of what is being looked for. Everything worked out from the title is written
     * into it, including the year, so parsing it back is lossless and there is no second
     * copy to keep in step with a viewer's typing.
     */
    val query: String = "",
    /**
     * Subtitle files actually downloaded today, and how many a day is expected to hold.
     *
     * On the state because the sheet has to be able to *show* them. The defect these two
     * fields exist for was a screen announcing that the day's downloads were used up while
     * nothing anywhere was counting downloads — the sentence was a mistranslation of an
     * HTTP 429 on a *search*, which is a throttle and not a quota. A number the viewer can
     * read is a number that cannot be wrong in that particular way without it being obvious.
     *
     * Read from where it is kept when the player opens and again after every completed
     * download, so what is drawn is always the persisted count and never a guess.
     */
    val spentToday: Int = 0,
    val dailyLimit: Int = 0,
) {
    /** The codes to send. Empty for [SubtitleLanguage.Any], which means no filter. */
    val codes: List<String> get() = listOfNotNull(language.code.takeIf { it.isNotBlank() })

    /** Whether there is anything to search for. An empty box is not a search. */
    val askable: Boolean get() = available && query.isNotBlank()

    /**
     * Whether the day's downloads are genuinely gone.
     *
     * Genuinely: a count of completed downloads, for today, having reached a limit that is
     * not itself zero. With [spentToday] at zero this is false, and the sentence that says
     * otherwise cannot be drawn — which is the whole of the defect this replaced.
     *
     * Searching is unaffected either way. A spent allowance stops downloads, not lookups.
     */
    val spent: Boolean get() = dailyLimit > 0 && spentToday >= dailyLimit

    /**
     * What to actually search with: the box, read as a query.
     *
     * The same path whether the text was put there by the player or typed over it, which is
     * the point of the box holding everything. A viewer who corrects `Pursuit 2026` to
     * `Pursuit 2025` gets a search for 2025 — not a search that quietly kept the year the
     * filename claimed because that is where the state was really held.
     */
    val asked: SubtitleQuery get() = SubtitleQuery.parse(query)
}
