package com.castivio.data.parsing

import com.castivio.domain.CatalogItem
import com.castivio.domain.CatalogWriter
import com.castivio.domain.ImportMode
import com.castivio.domain.ImportProgress
import com.castivio.domain.ImportSummary
import com.castivio.domain.MediaGroup
import com.castivio.domain.MediaKind
import java.io.Reader

/**
 * Imports an Xtream catalogue, category by category.
 *
 * This is the single biggest performance win available in the whole app, and it
 * is an API shape rather than an optimisation: Xtream is addressable by category,
 * so a 100,000-channel provider is a few hundred category rows plus whatever the
 * user actually opens. The M3U path has no such option and must stream the entire
 * playlist; here we simply never download it.
 *
 * What that buys, concretely: categories arrive in one small request and the UI is
 * usable immediately. A full catalogue import — every category of every kind — is
 * still supported for users who want offline browsing, and it streams the same
 * way, in bounded batches with nothing accumulated.
 *
 * Series are imported as shells: one row per show, no episodes. Episode lists
 * cost one request each (`get_series_info`), so fetching 600 of them up front
 * would take minutes for data almost none of which gets looked at. [importEpisodes]
 * fills in a single show when the user opens it.
 *
 * Blocking, like the writer it feeds. Call it on an IO dispatcher.
 */
class XtreamImportEngine(
    private val writer: CatalogWriter,
    private val batchSize: Int = CatalogImportEngine.DEFAULT_BATCH,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    init {
        require(batchSize in 1..CatalogImportEngine.MAX_BATCH) { "batchSize $batchSize out of range" }
    }

    /**
     * What the engine needs from the network.
     *
     * Deliberately narrow and returning plain [Reader]s: HTTP, credentials and
     * retries live in `:data:networking`, which keeps this engine pure Kotlin and
     * therefore testable and benchmarkable without a device or a server.
     */
    interface Api {
        fun categories(kind: MediaKind): Reader
        fun streams(kind: MediaKind, categoryId: String): Reader
        fun series(categoryId: String): Reader
        fun seriesInfo(seriesId: String): Reader

        /** The playable URL for a stream, built by the networking layer. */
        fun streamUrl(kind: MediaKind, streamId: String, extension: String?): String
    }

    /**
     * Imports categories and their contents for [kinds].
     *
     * @param isCancelled checked between categories and between batches — a
     *   category is one request, so this stops within one round trip.
     */
    fun importCatalogue(
        sourceId: String,
        api: Api,
        kinds: Set<MediaKind> = DEFAULT_KINDS,
        onProgress: (ImportProgress) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): ImportSummary {
        val started = clock()
        val batch = ArrayList<CatalogItem>(batchSize)
        val perKind = IntArray(MediaKind.entries.size)
        var imported = 0
        var order = 0
        var groups = 0
        var cancelled = false

        writer.begin(sourceId)
        try {
            for (requested in kinds) {
                if (cancelled) break
                // Radio has no Xtream endpoint of its own: stations live in live
                // categories, so they are found by category name below.
                val endpointKind = if (requested == MediaKind.RADIO) MediaKind.LIVE else requested
                if (requested == MediaKind.RADIO && MediaKind.LIVE in kinds) continue

                val categories = api.categories(endpointKind).use { reader ->
                    val list = ArrayList<XtreamCategory>(64)
                    XtreamParser.parseCategories(reader, endpointKind) { list.add(it) }
                    list
                }

                for (category in categories) {
                    if (cancelled) break
                    // A category named "RADIO" holds stations, not channels; they
                    // get their own kind so no live query has to exclude them.
                    val kind = if (endpointKind == MediaKind.LIVE && MediaClassifier.isRadioLabel(category.name)) {
                        MediaKind.RADIO
                    } else {
                        endpointKind
                    }
                    val groupId = StableIds.group(sourceId, kind, category.name)
                    writer.writeGroups(listOf(MediaGroup(groupId, category.name, kind)))
                    groups++

                    when (kind) {
                        MediaKind.SERIES -> api.series(category.id).use { reader ->
                            XtreamParser.parseSeries(reader) { series ->
                                perKind[MediaKind.SERIES.ordinal]++
                                batch.add(seriesShell(sourceId, series, groupId, order++))
                                if (batch.size >= batchSize) {
                                    imported += flush(batch)
                                    onProgress(ImportProgress.Importing(imported, groups, kind))
                                }
                            }
                        }

                        else -> api.streams(kind, category.id).use { reader ->
                            XtreamParser.parseStreams(reader) { stream ->
                                perKind[kind.ordinal]++
                                batch.add(item(sourceId, api, kind, stream, groupId, order++))
                                if (batch.size >= batchSize) {
                                    imported += flush(batch)
                                    onProgress(ImportProgress.Importing(imported, groups, kind))
                                }
                            }
                        }
                    }

                    imported += flush(batch)
                    onProgress(ImportProgress.Importing(imported, groups, kind))
                    if (isCancelled()) cancelled = true
                }
            }

            imported += flush(batch)
            val summary = ImportSummary(
                sourceId = sourceId,
                items = imported,
                groups = groups,
                skipped = 0,
                byKind = perKind.toKindMap(),
                durationMs = clock() - started,
                cancelled = cancelled,
            )
            if (cancelled) {
                writer.abort(null)
            } else {
                writer.finish(summary)
                onProgress(ImportProgress.Done(imported, summary.durationMs))
            }
            return summary
        } catch (t: Throwable) {
            writer.abort(t)
            throw t
        }
    }

    /**
     * Fills in one show's episodes, on demand.
     *
     * Called when the user opens a series. The rows carry the same `seriesId` as
     * the shell written during the catalogue import, so the season list simply
     * appears — no reconciliation, no second identity scheme.
     */
    fun importEpisodes(
        sourceId: String,
        api: Api,
        providerSeriesId: String,
        seriesTitle: String,
        groupId: String?,
    ): Int {
        val started = clock()
        val seriesId = StableIds.seriesByProviderId(sourceId, providerSeriesId)
        val batch = ArrayList<CatalogItem>(64)
        var order = 0
        var written = 0

        // APPEND, emphatically: a REPLACE write here would prune every row that
        // is not one of this show's episodes — the entire library.
        writer.begin(sourceId, ImportMode.APPEND)
        try {
            api.seriesInfo(providerSeriesId).use { reader ->
                XtreamParser.parseSeriesInfo(reader) { episode ->
                    batch.add(
                        CatalogItem(
                            id = StableIds.item(sourceId, "episode:${episode.episodeId}"),
                            sourceId = sourceId,
                            kind = MediaKind.SERIES,
                            title = episode.title,
                            streamUrl = api.streamUrl(
                                MediaKind.SERIES,
                                episode.episodeId,
                                episode.containerExtension,
                            ),
                            artworkUrl = episode.coverUrl,
                            groupId = groupId,
                            providerOrder = order++,
                            durationSeconds = episode.durationSeconds,
                            seriesId = seriesId,
                            seriesTitle = seriesTitle,
                            seasonNumber = episode.seasonNumber,
                            episodeNumber = episode.episodeNumber,
                        ),
                    )
                    if (batch.size >= batchSize) written += flush(batch)
                }
            }
            written += flush(batch)
            writer.finish(
                ImportSummary(
                    sourceId = sourceId,
                    items = written,
                    groups = 0,
                    skipped = 0,
                    byKind = mapOf(MediaKind.SERIES to written),
                    durationMs = clock() - started,
                ),
            )
            return written
        } catch (t: Throwable) {
            writer.abort(t)
            throw t
        }
    }

    private fun item(
        sourceId: String,
        api: Api,
        kind: MediaKind,
        stream: XtreamStream,
        groupId: String,
        order: Int,
    ) = CatalogItem(
        // Keyed on the provider's stream id, not the URL: Xtream URLs embed
        // credentials, so a password change would otherwise re-key the catalogue
        // and orphan every favourite.
        id = StableIds.item(sourceId, "stream:${stream.streamId}"),
        sourceId = sourceId,
        kind = kind,
        title = stream.name,
        streamUrl = api.streamUrl(kind, stream.streamId, stream.containerExtension),
        artworkUrl = stream.iconUrl,
        groupId = groupId,
        epgChannelId = stream.epgChannelId,
        providerOrder = stream.number ?: order,
        durationSeconds = null,
    )

    /**
     * A show with no episodes yet.
     *
     * `episodeCount` in the Series screen counts rows that have an episode number,
     * so a shell reads as "no episodes loaded" rather than "one episode".
     */
    private fun seriesShell(
        sourceId: String,
        series: XtreamSeries,
        groupId: String,
        order: Int,
    ) = CatalogItem(
        id = StableIds.item(sourceId, "series:${series.seriesId}"),
        sourceId = sourceId,
        kind = MediaKind.SERIES,
        title = series.name,
        // Not playable, and not meant to be: opening a show navigates to its
        // seasons, which is what triggers the episode import.
        streamUrl = "",
        artworkUrl = series.coverUrl,
        groupId = groupId,
        providerOrder = order,
        seriesId = StableIds.seriesByProviderId(sourceId, series.seriesId),
        seriesTitle = series.name,
        seasonNumber = null,
        episodeNumber = null,
    )

    private fun flush(batch: MutableList<CatalogItem>): Int {
        if (batch.isEmpty()) return 0
        val size = batch.size
        writer.writeItems(batch)
        batch.clear()
        writer.commit()
        return size
    }

    private fun IntArray.toKindMap(): Map<MediaKind, Int> {
        val map = LinkedHashMap<MediaKind, Int>(MediaKind.entries.size)
        for (kind in MediaKind.entries) {
            val count = this[kind.ordinal]
            if (count > 0) map[kind] = count
        }
        return map
    }

    companion object {
        val DEFAULT_KINDS: Set<MediaKind> = setOf(MediaKind.LIVE, MediaKind.MOVIE, MediaKind.SERIES)
    }
}
