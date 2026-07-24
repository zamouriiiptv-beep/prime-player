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
data class MediaGroup(val id: String, val name: String, val kind: MediaKind)

enum class MediaKind { LIVE, MOVIE, SERIES }

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
