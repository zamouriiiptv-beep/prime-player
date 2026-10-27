package com.castivio.domain

/**
 * Where a viewer picks a show up: the episode Play starts, and whether it starts from the beginning.
 *
 * @property resume true when the episode was begun and not finished, so it continues from its position;
 *   false when it starts from the top -- the first episode of a show not begun, or the one after a finished one.
 */
data class ContinueTarget(val episode: Episode, val resume: Boolean)

/**
 * The episode Play starts for a show.
 *
 * The one touched last decides: unfinished, it is the one to continue; finished, the one after it in order --
 * and for the last episode of the show, that same episode again, since there is nothing after it. A show not
 * begun starts at its first episode. Null only for a show with no episodes.
 *
 * @param seasons the show's seasons in order, each with its episodes in order.
 * @param progress the viewer's positions by episode id; an episode with none is absent.
 */
fun continueTarget(seasons: List<Season>, progress: Map<String, PlaybackProgress>): ContinueTarget? {
    val ordered = seasons.flatMap { it.episodes }
    if (ordered.isEmpty()) return null
    val last = ordered
        .mapNotNull { episode -> progress[episode.id]?.let { episode to it } }
        .maxByOrNull { (_, position) -> position.updatedAtEpochMs }
        ?: return ContinueTarget(ordered.first(), resume = false)
    val (episode, position) = last
    if (!position.isFinished) return ContinueTarget(episode, resume = true)
    val next = ordered.getOrNull(ordered.indexOf(episode) + 1)
    return ContinueTarget(next ?: episode, resume = false)
}

/** What a list says about an episode the viewer has, or has not, watched. */
sealed interface EpisodeMark {
    /** Not begun. */
    data object None : EpisodeMark

    /** Begun and not finished, by [percent] of its length (0 to 1). */
    data class Partly(val percent: Float) : EpisodeMark

    /** Watched to the end. */
    data object Watched : EpisodeMark
}

/** The mark an episode carries, from the position kept for it. */
fun episodeMark(progress: PlaybackProgress?): EpisodeMark = when {
    progress == null -> EpisodeMark.None
    progress.isFinished -> EpisodeMark.Watched
    progress.positionMs > 0 -> EpisodeMark.Partly(progress.percent)
    else -> EpisodeMark.None
}
