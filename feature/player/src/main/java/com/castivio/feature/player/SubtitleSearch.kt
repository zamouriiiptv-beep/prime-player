package com.castivio.feature.player

import com.castivio.data.subtitles.SubtitleFailure
import com.castivio.data.subtitles.SubtitleOffer

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
) {
    /** The codes to send. Empty for [SubtitleLanguage.Any], which means no filter. */
    val codes: List<String> get() = listOfNotNull(language.code.takeIf { it.isNotBlank() })
}
