package com.castivio.data.database

import com.castivio.data.database.dao.MediaWithProgress
import com.castivio.data.database.dao.SeriesRow
import com.castivio.data.database.entity.GroupEntity
import com.castivio.data.database.entity.MediaEntity
import com.castivio.data.database.entity.ProgressEntity
import com.castivio.domain.CatalogItem
import com.castivio.domain.Channel
import com.castivio.domain.Episode
import com.castivio.domain.InProgressItem
import com.castivio.domain.MediaGroup
import com.castivio.domain.MediaItem
import com.castivio.domain.MediaKind
import com.castivio.domain.Movie
import com.castivio.domain.PlaybackProgress
import com.castivio.domain.SeriesSummary

/**
 * Entity ↔ domain translation.
 *
 * The database's flat row and the domain's sealed model are deliberately
 * different shapes — one is built for bulk insert, the other for features to
 * read — and this file is the only place that knows both.
 */

internal fun MediaEntity.toDomain(): MediaItem = when (kindOrLive()) {
    MediaKind.MOVIE -> Movie(
        id = id,
        title = title,
        artworkUrl = artworkUrl,
        streamUrl = streamUrl,
        durationMinutes = durationSeconds?.let { it / 60 },
    )

    MediaKind.SERIES -> Episode(
        id = id,
        title = title,
        artworkUrl = artworkUrl,
        streamUrl = streamUrl,
        // A provider that gives no numbering still gets a playable episode;
        // 0 sorts it first rather than dropping it.
        seasonNumber = seasonNumber ?: 0,
        episodeNumber = episodeNumber ?: 0,
    )

    // Radio is a Channel too: an audio-only live stream is still a channel with a
    // logo and an EPG id, and the player treats it the same.
    MediaKind.LIVE, MediaKind.RADIO -> Channel(
        id = id,
        title = title,
        artworkUrl = artworkUrl,
        number = null,
        groupId = groupId,
        streamUrl = streamUrl,
        epgChannelId = epgChannelId,
    )
}

/**
 * Unknown kinds read as live rather than throwing.
 *
 * A row written by a newer version of the app must not crash an older one —
 * downgrades happen, and a sideloaded APK on a TV box is not rare.
 */
internal fun MediaEntity.kindOrLive(): MediaKind =
    runCatching { MediaKind.valueOf(kind) }.getOrDefault(MediaKind.LIVE)

internal fun CatalogItem.toEntity(generation: Long, now: Long): MediaEntity = MediaEntity(
    id = id,
    sourceId = sourceId,
    kind = kind.name,
    title = title,
    sortTitle = sortTitle(title),
    searchText = searchText(title, seriesTitle),
    streamUrl = streamUrl,
    artworkUrl = artworkUrl,
    groupId = groupId,
    epgChannelId = epgChannelId,
    providerOrder = providerOrder,
    durationSeconds = durationSeconds,
    seriesId = seriesId,
    seriesTitle = seriesTitle,
    seasonNumber = seasonNumber,
    episodeNumber = episodeNumber,
    generation = generation,
    addedAt = now,
)

/**
 * The sort key.
 *
 * Providers decorate names heavily — `|AR| [4K] The Matrix`, `••• Sports`,
 * `[HD] Nova`. Sorted raw, an alphabetical list is a list of brackets and pipes
 * and the alphabet jump-bar becomes useless, so the decoration comes off once at
 * import rather than in every query.
 *
 * Only English articles are dropped. `El Clásico` and `Der Spiegel` are how
 * people look those up, and guessing at articles across twelve languages
 * produces more surprises than it fixes.
 */
internal fun sortTitle(title: String): String {
    var start = 0
    while (start < title.length) {
        val c = title[start]
        when {
            c.isLetterOrDigit() -> break
            // A delimited prefix is skipped whole: "[4K] The Matrix" and
            // "|AR| Zulu Dawn" must sort as "matrix" and "zulu dawn", not as
            // "4k] the matrix" and "ar| zulu dawn".
            c == '[' || c == '(' || c == '{' || c == '|' -> {
                val close = when (c) { '[' -> ']'; '(' -> ')'; '{' -> '}'; else -> '|' }
                val end = title.indexOf(close, start + 1)
                if (end < 0) break // unbalanced — stop rather than eat the title
                start = end + 1
            }
            else -> start++ // spaces, pipes, bullets, dashes
        }
    }
    var end = title.length
    while (end > start && !title[end - 1].isLetterOrDigit()) end--

    val core = title.substring(start, end).lowercase()
    if (core.isEmpty()) return title.lowercase() // all decoration: better than blank
    for (article in ARTICLES) {
        if (core.length > article.length && core.startsWith(article)) {
            return core.substring(article.length)
        }
    }
    return core
}

private val ARTICLES = arrayOf("the ", "an ", "a ")

/**
 * What search matches against.
 *
 * Both the episode title and the show name, case-folded in Kotlin because
 * SQLite's `lower()` is ASCII-only and the FTS tokenizer does not case-fold
 * non-Latin scripts. Folding once at import is also cheaper than folding a
 * column per keystroke.
 */
internal fun searchText(title: String, seriesTitle: String?): String =
    if (seriesTitle == null || seriesTitle == title) {
        title.lowercase()
    } else {
        // Two fields in one indexed column: FTS4 ranks by term, not by column, so
        // splitting them would buy nothing and cost an index.
        "${title.lowercase()} ${seriesTitle.lowercase()}"
    }

internal fun GroupEntity.toDomain(): MediaGroup = MediaGroup(
    id = id,
    name = name,
    kind = runCatching { MediaKind.valueOf(kind) }.getOrDefault(MediaKind.LIVE),
)

internal fun MediaGroup.toEntity(
    sourceId: String,
    providerOrder: Int,
    generation: Long,
): GroupEntity = GroupEntity(
    id = id,
    sourceId = sourceId,
    name = name,
    kind = kind.name,
    providerOrder = providerOrder,
    generation = generation,
)

internal fun SeriesRow.toDomain(): SeriesSummary = SeriesSummary(
    seriesId = seriesId,
    // A show with no title is not showable; fall back to the id so it is at least
    // reachable and visibly wrong rather than silently missing.
    title = title ?: seriesId,
    artworkUrl = artworkUrl,
    episodeCount = episodeCount,
    seasonCount = seasonCount,
)

internal fun ProgressEntity.toDomain(): PlaybackProgress = PlaybackProgress(
    mediaId = mediaId,
    positionMs = positionMs,
    durationMs = durationMs,
    updatedAtEpochMs = updatedAt,
)

internal fun MediaWithProgress.toDomain(): InProgressItem =
    InProgressItem(item = media.toDomain(), progress = progress.toDomain())
