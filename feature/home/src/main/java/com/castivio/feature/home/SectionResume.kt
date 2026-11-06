package com.castivio.feature.home

import com.castivio.domain.Channel
import com.castivio.domain.Episode
import com.castivio.domain.MediaKind
import com.castivio.domain.Movie
import com.castivio.domain.SeriesSummary
import com.castivio.domain.WatchedItem

/**
 * What one of Home's small cards -- the films', the shows' or the radio's -- offers once the viewer has watched something in its
 * section: the film, the episode's show or the station watched last, ready to draw.
 *
 * Facts only, like [Resume]: the name is the item's own (the show's, for an episode), [detail] says which episode, [progress] how
 * far in it the viewer was where that is known -- a film or an episode part-watched -- and nothing is drawn for what is not: a
 * station has no position, and a film only started has none yet.
 */
data class SectionResume(
    val kind: MediaKind,
    /** The film's, the show's or the station's name. */
    val title: String,
    /** Which episode, as the catalogue says it ("S2 E5"), or null for a film and a station. */
    val detail: String?,
    /** The poster or the logo, or null where the provider gave none. */
    val artworkUrl: String?,
    /** How far in it the viewer was, 0 to 1, or null where there is no such thing to say. */
    val progress: Float?,
    /** What a press on the card starts. */
    val open: ResumeOpen,
)

/** What a press on a small card with something to carry on from opens -- always inside its own section, so Back returns to it. */
sealed interface ResumeOpen {

    /** The film's page, over the films' board. */
    data class Film(val movie: Movie) : ResumeOpen

    /** The show's page, over the shows' board. */
    data class Show(val show: SeriesSummary) : ResumeOpen

    /** The station, playing on the radio's board. */
    data class Station(val channel: Channel) : ResumeOpen
}

/**
 * The offer of [kind]'s card, from what was watched last in it, or null when there is nothing to offer.
 *
 * Null for an item the card could not open: an episode whose row carries no show to open, or one of another kind than the card's.
 * The film's bar is drawn only while it is part-watched -- a finished film has nothing left to carry on from, and a bar at its end
 * says the section has stopped.
 */
internal fun sectionResumeOf(kind: MediaKind, watched: WatchedItem?): SectionResume? {
    watched ?: return null
    val item = watched.item
    val percent = watched.progress?.takeIf { it.positionMs > 0 && !it.isFinished && (it.durationMs ?: 0) > 0 }?.percent
    return when (kind) {
        MediaKind.MOVIE -> (item as? Movie)?.let { movie ->
            SectionResume(
                kind = kind,
                title = movie.title,
                detail = null,
                artworkUrl = movie.artworkUrl,
                progress = percent,
                open = ResumeOpen.Film(movie),
            )
        }

        MediaKind.SERIES -> {
            val episode = item as? Episode
            val showId = watched.showId
            val showTitle = watched.showTitle?.takeIf { it.isNotBlank() }
            if (episode == null || showId == null || showTitle == null) {
                null
            } else {
                SectionResume(
                    kind = kind,
                    title = showTitle,
                    detail = episodeLabel(episode.seasonNumber, episode.episodeNumber),
                    artworkUrl = episode.artworkUrl,
                    progress = percent,
                    open = ResumeOpen.Show(
                        SeriesSummary(
                            seriesId = showId,
                            title = showTitle,
                            artworkUrl = episode.artworkUrl,
                            // The show's page reads neither: it asks for the seasons itself, by the id.
                            episodeCount = 0,
                            seasonCount = 0,
                        ),
                    ),
                )
            }
        }

        MediaKind.RADIO -> (item as? Channel)?.let { station ->
            SectionResume(
                kind = kind,
                title = station.title,
                detail = null,
                artworkUrl = station.artworkUrl,
                // A live stream has no position to be at.
                progress = null,
                open = ResumeOpen.Station(station),
            )
        }

        // The live channels have their own card and their own offer: see [Resume].
        MediaKind.LIVE -> null
    }
}

/**
 * Which face a small card shows: the same three decisions as the live card's [heroFace], from the same facts, so that the three
 * small cards and the large one move together -- a load, a queue or a failure keeps every card as it was.
 */
internal fun tileFace(unfetched: Boolean, counted: Boolean, load: CardLoad, resumed: Boolean): HeroFace = when {
    load != CardLoad.Idle -> HeroFace.Plain
    unfetched -> HeroFace.NotLoaded
    counted && resumed -> HeroFace.Continue
    else -> HeroFace.Plain
}
