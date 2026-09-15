package com.castivio.domain

/**
 * The content model, shared by every feature.
 *
 * Deliberately independent of where content came from: an M3U entry, an Xtream
 * API row and a future provider all normalise into these types, so features
 * never learn a provider's shape.
 */
sealed interface MediaItem {
    val id: String
    val title: String
    val artworkUrl: String?
}

data class Channel(
    override val id: String,
    override val title: String,
    override val artworkUrl: String?,
    val number: Int?,
    val groupId: String?,
    val streamUrl: String,
    val epgChannelId: String?,
    /** Non-null only when the provider genuinely exposes an archive. */
    val catchUpHours: Int? = null,
) : MediaItem {
    val supportsTimeshift: Boolean get() = (catchUpHours ?: 0) > 0
}

data class Movie(
    override val id: String,
    override val title: String,
    override val artworkUrl: String?,
    val streamUrl: String,
    val year: Int? = null,
    val durationMinutes: Int? = null,
    val genres: List<String> = emptyList(),
) : MediaItem

data class Series(
    override val id: String,
    override val title: String,
    override val artworkUrl: String?,
    val seasons: List<Season> = emptyList(),
) : MediaItem

data class Season(val number: Int, val episodes: List<Episode>)

data class Episode(
    override val id: String,
    override val title: String,
    override val artworkUrl: String?,
    val streamUrl: String,
    val seasonNumber: Int,
    val episodeNumber: Int,
) : MediaItem

/** A group as the provider defined it (a "category" in Xtream, a group-title in M3U). */
data class MediaGroup(
    val id: String,
    val name: String,
    val kind: MediaKind,
    /**
     * How many rows the provider filed under this category.
     *
     * The denormalised column, carried rather than counted. The storage layer has
     * written it since the schema existed and wrote a comment saying why —
     * "the category rail shows a count next to every name; computing it with
     * `COUNT(*) GROUP BY group_id` over 400,000 rows on every observation is a visible
     * stall on a weak box" — and then no domain type had anywhere to put it, so the
     * rail drew no count and the value was written for nobody.
     *
     * Zero by default, which is also what a category genuinely holding nothing reports.
     * The two are not distinguished here because nothing on this screen needs them to
     * be: a rail entry showing `0` and one showing nothing are the same sentence.
     */
    val itemCount: Int = 0,
)

/**
 * What a row is, decided at import time.
 *
 * [RADIO] is a separate kind rather than a flag on [Channel] because it changes
 * how a row is *queried*: radio has its own screen and must never appear in a
 * live-TV page. A boolean would have every live query remember to exclude it.
 */
enum class MediaKind { LIVE, MOVIE, SERIES, RADIO }

/** Drives Continue Watching and History. Position is authoritative; percent is derived. */
data class PlaybackProgress(
    val mediaId: String,
    val positionMs: Long,
    val durationMs: Long?,
    val updatedAtEpochMs: Long,
) {
    val percent: Float
        get() = durationMs?.takeIf { it > 0 }?.let { (positionMs.toFloat() / it).coerceIn(0f, 1f) } ?: 0f

    /** Finished items drop out of Continue Watching rather than lingering at 99%. */
    val isFinished: Boolean get() = percent >= FINISHED_THRESHOLD

    private companion object { const val FINISHED_THRESHOLD = 0.95f }
}
