package com.castivio.feature.home

import com.castivio.core.common.AppError
import com.castivio.domain.Channel
import com.castivio.domain.Episode
import com.castivio.domain.MediaItem
import com.castivio.domain.MediaKind
import com.castivio.domain.Movie
import com.castivio.domain.Series

/**
 * The three things a provider carries, and the one place that says which kind each is.
 *
 * Three and not more. Home is a choice between them and nothing else — no rows, no
 * counts, no artwork — because a screen that shows content has to have fetched content,
 * and the whole point of this flow is that signing in fetches nothing at all.
 *
 * Radio has no entry. Xtream carries stations inside live categories and the import
 * still files them under their own kind, so a station never appears in the channel
 * list; what it no longer has is a destination of its own, which was three presses deep
 * for content most providers do not carry.
 */
enum class CatalogSection(val kind: MediaKind) {
    Channels(MediaKind.LIVE),
    Movies(MediaKind.MOVIE),
    Series(MediaKind.SERIES),
}

/**
 * How far a fetch has got, for one section or one category.
 *
 * Its own type rather than a boolean pair, because the four answers lead to four
 * different screens and a boolean pair can express states that do not exist. It is also
 * the reason a failure stays local: this is per screen, so a category that will not load
 * leaves every other part of the app exactly as usable as it was.
 */
sealed interface SectionLoad {

    /** Nothing asked yet. The screen has just been composed. */
    data object Idle : SectionLoad

    /** A request is out. Shown as skeletons only when there is nothing stored yet. */
    data object Loading : SectionLoad

    /**
     * The fetch finished.
     *
     * @param wrote how many rows arrived, or null when nothing was fetched because the
     *   section was already on the device. Null and zero are different screens: one has
     *   rows to show, the other has to explain that the provider carries none.
     */
    data class Ready(val wrote: Int?) : SectionLoad

    /** The fetch failed, with the reason kept so the screen can say which. */
    data class Failed(val error: AppError) : SectionLoad
}

/**
 * Whether a fetch is worth offering again.
 *
 * The same rule the activation screen uses, for the same reason: offering "try again"
 * for a rejected subscription wastes the one move the user has, and teaches them the
 * button means nothing.
 */
val AppError.retryable: Boolean
    get() = when (this) {
        AppError.NETWORK_UNAVAILABLE, AppError.TIMEOUT, AppError.SERVER_ERROR, AppError.UNKNOWN -> true
        AppError.UNAUTHORIZED, AppError.NOT_FOUND, AppError.MALFORMED_PLAYLIST, AppError.NOT_CONFIGURED -> false
    }

/**
 * A press on a catalogue row, reduced to what the player is allowed to be given.
 *
 * Returns null for the rows that are not a stream: a [Series] is a show, and pressing
 * it opens its episodes rather than a video. Modelling that as null instead of as a
 * broken request is what stops a show ever being handed to the engine as a URL.
 *
 * Nothing is fetched here, and nothing can be: everything below is already on screen
 * by the time a press happens, which is the player's contract about the first frame
 * held at the only point where it could be broken.
 */
fun MediaItem.asSelection(): CatalogSelection? = when (this) {
    is Channel -> CatalogSelection(
        url = streamUrl,
        title = title,
        live = true,
        channelNumber = number?.toString(),
        epgChannelId = epgChannelId,
        catchUpHours = catchUpHours,
    )

    is Movie -> CatalogSelection(
        url = streamUrl,
        title = title,
        live = false,
        subtitle = year?.toString(),
    )

    is Episode -> CatalogSelection(
        url = streamUrl,
        title = title,
        live = false,
        episode = true,
        subtitle = episodeLabel(seasonNumber, episodeNumber),
    )

    is Series -> null
}

/**
 * What the player is handed, without knowing the player exists.
 *
 * `:feature:home` must not depend on `:feature:player` — one feature reaching into
 * another is how a module graph becomes a ball of string — so a press is hoisted to
 * whoever composed both, exactly as a local file already is. The fields are the
 * player's request minus the parts only it decides.
 */
data class CatalogSelection(
    val url: String,
    val title: String,
    /** Decides the engine's buffering profile, and whether a timeline is drawn at all. */
    val live: Boolean,
    /** An episode rather than a film, which the engine reads to know what "next" means. */
    val episode: Boolean = false,
    val subtitle: String? = null,
    val channelNumber: String? = null,
    val epgChannelId: String? = null,
    val catchUpHours: Int? = null,
)

/**
 * `S01E04`, or the half of it the provider actually numbered.
 *
 * Zero means "not numbered" — the import writes it when a provider gives nothing —
 * so a zero is dropped rather than shown as `S00`, and an episode numbered by
 * neither gets no label instead of a meaningless one.
 */
internal fun episodeLabel(season: Int, episode: Int): String? = when {
    season > 0 && episode > 0 -> "S%02dE%02d".format(season, episode)
    episode > 0 -> "E%02d".format(episode)
    season > 0 -> "S%02d".format(season)
    else -> null
}
