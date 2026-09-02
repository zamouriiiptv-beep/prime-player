package com.castivio.feature.home

import com.castivio.domain.Channel
import com.castivio.domain.Episode
import com.castivio.domain.MediaGroup
import com.castivio.domain.MediaItem
import com.castivio.domain.MediaKind
import com.castivio.domain.Movie
import com.castivio.domain.Series

/**
 * The browsable sections, and the one place that says which kind each one reads.
 *
 * An enum rather than four screens because the three panes — categories, items, and
 * what a press does — are the same shape in all of them. What differs is the kind
 * queried and how an item is drawn, and both are decided from this one value.
 */
enum class CatalogSection(val kind: MediaKind) {
    Live(MediaKind.LIVE),
    Movies(MediaKind.MOVIE),
    Series(MediaKind.SERIES),
    Radio(MediaKind.RADIO),
}

/**
 * A chosen category that survived a re-import, or none.
 *
 * Providers rename and drop categories between imports, and a selection is stored by
 * id. Left alone, a stale id means a category pane with nothing highlighted and a
 * content pane querying a group that no longer exists — an empty screen with no
 * explanation. Falling back to "all" is the honest answer, and it is a rule rather
 * than a coincidence, so it is written down and tested.
 */
internal fun surviving(selected: String?, groups: List<MediaGroup>): String? =
    selected?.takeIf { id -> groups.any { it.id == id } }

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
