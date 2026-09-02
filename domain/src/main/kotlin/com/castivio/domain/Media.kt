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

/**
 * A group as the provider defined it (a "category" in Xtream, a group-title in M3U).
 *
 * [providerRef] is what makes on-demand loading possible at all. The row id is a hash
 * of the category's name and is deliberately not reversible, so without the provider's
 * own `category_id` there is no way to ask for this category's contents later — the
 * app would be back to downloading everything up front to avoid needing it.
 *
 * [itemsLoadedAtMs] is when this category's rows were last fetched, or null when they
 * never were. A category is listed long before it is opened, so "the category exists"
 * and "its contents are on the device" are different facts and are stored as such.
 */
data class MediaGroup(
    val id: String,
    val name: String,
    val kind: MediaKind,
    /** The provider's own category id. Null for M3U, which has no such identifier. */
    val providerRef: String? = null,
    /** When this category was last listed. Written by the store, not by a caller. */
    val listedAtMs: Long = 0,
    val itemsLoadedAtMs: Long? = null,
    /**
     * How many rows this category holds, from the denormalised column.
     *
     * Meaningful only once [itemsLoadedAtMs] is set: before a category is opened
     * nothing behind it has been fetched, and zero then means "not yet asked" rather
     * than "the provider carries none".
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
