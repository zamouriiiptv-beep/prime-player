package com.castivio.domain

/**
 * One show's episodes, asked of its provider when the show is opened and written to the catalogue.
 *
 * The catalogue holds a show as a row with no episodes -- listing six hundred of them would cost six hundred
 * requests to show a few -- so the episodes of the one a viewer opens are fetched then, and the season list
 * the page reads is what this has written. A show already holding episodes is fetched again as well, because
 * a show that is still running has new ones.
 */
interface SeriesEpisodes {

    /**
     * Asks the show's provider for its episodes and stores them. Never throws for a provider that cannot answer.
     *
     * [onReady] is told what the provider sent **as soon as it has been read** -- before any of it is written -- so the
     * page can draw it without waiting for the catalogue to keep it and to be read again. Not called for an empty answer.
     */
    suspend fun load(seriesId: String, onReady: (EpisodesReady) -> Unit = {}): EpisodesLoad
}

/** What a provider sent of a show, read and not yet kept: its episodes by season, what it says of the show and of each. */
data class EpisodesReady(
    val seasons: List<Season>,
    val details: SeriesDetails?,
    /** The plot of each episode that has one, by the episode's id. */
    val plots: Map<String, String>,
)

/** How asking a provider for a show's episodes ended. */
sealed interface EpisodesLoad {

    /**
     * The provider answered, with [episodes] of them. Zero is an answer, and not always the last one: a provider that
     * has not yet built the show answers empty the first time -- see [times] for what the asking cost.
     */
    data class Loaded(val episodes: Int, val times: RequestTimes? = null) : EpisodesLoad

    /** There is nobody to ask: the show has no row of its own, or its source cannot be asked for episodes. */
    data object NoProvider : EpisodesLoad

    /** The provider could not be reached, or answered with something that is not a list of episodes. */
    data class Failed(val cause: Throwable) : EpisodesLoad {

        /** What went wrong in one short line, for a debuggable build's error screen. See [failureReason]. */
        val reason: String get() = failureReason(cause)
    }
}

/**
 * A failure as one line a person can read off a television: the kind of failure and what it said, and what lay under
 * it. An address's credentials are taken out first -- a request's address carries the account's user and password, and
 * some failures quote it -- and the line is cut short, so it is safe to print and never a page of trace.
 */
fun failureReason(cause: Throwable): String {
    fun one(t: Throwable): String {
        val name = t.javaClass.simpleName.ifEmpty { "Error" }
        val message = t.message?.let(::withoutCredentials)?.trim().orEmpty()
        return if (message.isEmpty()) name else "$name: $message"
    }
    val root = generateSequence(cause) { it.cause }.take(REASON_DEPTH).last()
    val line = if (root === cause) one(cause) else "${one(cause)} \u2190 ${one(root)}"
    return if (line.length <= REASON_LENGTH) line else line.take(REASON_LENGTH - 1) + "\u2026"
}

private val CREDENTIAL_PARAMETER = Regex("""(?i)\b(username|password|user|pass)=[^&\s"']*""")
private val ADDRESS_USERINFO = Regex("""(?<=//)[^/@\s]+@""")

/** Credentials in a query string or in an address's user part, replaced by an ellipsis. */
private fun withoutCredentials(text: String): String =
    text.replace(CREDENTIAL_PARAMETER) { "${it.groupValues[1]}=\u2026" }.replace(ADDRESS_USERINFO, "\u2026@")

private const val REASON_DEPTH = 6
private const val REASON_LENGTH = 160
