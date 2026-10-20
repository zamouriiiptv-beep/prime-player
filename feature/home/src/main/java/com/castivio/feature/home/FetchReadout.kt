package com.castivio.feature.home

import com.castivio.domain.FetchDiagnosis
import com.castivio.domain.FetchStage
import java.util.Locale

/**
 * A failed fetch's figures, as the lines a debuggable build's error screen prints in place
 * of the sentence.
 *
 * ## Why it exists
 *
 * The sentence says what kind of thing went wrong; it cannot say which status, which
 * exception, or how far the download got, and for a playlist that failed as `UNKNOWN`
 * that is the whole question. The answer was in logcat, and a viewer holding a television
 * remote has no logcat. This puts the same facts on the screen they are looking at.
 *
 * ## Why these words are not translated
 *
 * It is an instrument, and written the way an instrument is: Latin identifiers and
 * figures, the same words the logcat line of the same failure uses, which a photograph
 * carries intact to whoever is helping. Translated, `HTTP` and an exception's
 * class would still be Latin inside the sentence, and the line would be reordered around
 * them. A release build never draws it — see the error state in [ChannelsScreen].
 *
 * ## Why every line is always there
 *
 * Six rows, in the same order, on every failure, with `-` for a figure that does not
 * exist. Two photographs can then be compared row by row, and a missing status reads as
 * "the provider never answered" instead of as a row somebody forgot.
 *
 * The last says which way the subscription was read — the playlist or the panel's API —
 * and, when the playlist was the second try, what the panel said first. A playlist
 * subscription can be read either way, so without it the five figures above it could
 * belong to either request.
 *
 * Formatted by the state holder, not in composition — see [BrowseState.fetchReadout].
 */
internal fun fetchReadout(diagnosis: FetchDiagnosis): String = listOf(
    "Stage: ${diagnosis.stage.name} (${diagnosis.stage.meaning})",
    "HTTP: ${diagnosis.httpStatus ?: NONE}",
    "Exception: ${diagnosis.exception ?: NONE}",
    "Received: ${diagnosis.bytesReceived?.let { "${count(it)} bytes" } ?: NONE}",
    // Every entry of the playlist, films and episodes included: one file holds them all.
    "Channels: ${count(diagnosis.itemsRead.toLong())} read · ${count(diagnosis.itemsSaved.toLong())} saved",
    "Route: ${route(diagnosis)}",
).joinToString(separator = "\n")

/** `XTREAM`, `M3U`, or `M3U after XTREAM ANSWER · HTTP 403 · HttpStatusException`. */
private fun route(diagnosis: FetchDiagnosis): String {
    val taken = diagnosis.route?.name ?: return NONE
    val first = diagnosis.after ?: return taken
    return "$taken after ${first.route?.name ?: NONE} ${first.stage.name}" +
        " · HTTP ${first.httpStatus ?: NONE} · ${first.exception ?: NONE}"
}

/** What each stage means, in the few words that fit beside its name. */
private val FetchStage.meaning: String
    get() = when (this) {
        FetchStage.PREPARE -> "request not sent"
        FetchStage.CONNECT -> "no answer from the provider"
        FetchStage.ANSWER -> "the provider answered with an error"
        FetchStage.READ -> "reading the playlist"
        FetchStage.SAVE -> "saving to the database"
        FetchStage.OTHER -> "in the app, outside the download"
    }

/**
 * `1,048,576`. [Locale.ROOT], because a figure that changes shape with the user's language
 * is a figure that gets copied down wrongly.
 */
private fun count(value: Long): String = String.format(Locale.ROOT, "%,d", value)

private const val NONE = "-"
