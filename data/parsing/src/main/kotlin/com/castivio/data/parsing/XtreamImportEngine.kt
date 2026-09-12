package com.castivio.data.parsing

import com.castivio.domain.CatalogItem
import com.castivio.domain.CatalogWriter
import com.castivio.domain.ImportMode
import com.castivio.domain.ImportProgress
import com.castivio.domain.ImportSummary
import com.castivio.domain.MediaGroup
import com.castivio.domain.MediaKind
import java.io.Reader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.channels.trySendBlocking

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
    /**
     * How many category requests may be in flight at once.
     *
     * Four, and a constructor parameter rather than a constant, so the number can be
     * moved from measurements instead of from opinion. It is deliberately small: the
     * thing on the other end is usually one PHP host serving every customer of the
     * subscription, and the failure mode of getting this wrong is not a slow app but a
     * provider that starts refusing the user.
     */
    private val concurrency: Int = DEFAULT_CONCURRENCY,
) {
    init {
        require(batchSize in 1..CatalogImportEngine.MAX_BATCH) { "batchSize $batchSize out of range" }
        require(concurrency in 1..MAX_CONCURRENCY) { "concurrency $concurrency out of range" }
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
        /**
         * How this write relates to what is already stored.
         *
         * [ImportMode.REPLACE] for a whole catalogue, which is what a refresh is and
         * what the default has always been. [ImportMode.APPEND] when [kinds] is a
         * subset: a section fetched on its own must not prune the sections fetched
         * before it, and a replacing write would do exactly that at `finish()` —
         * every row of another generation, which after a per-kind import means every
         * row of every other kind.
         */
        mode: ImportMode = ImportMode.REPLACE,
    ): ImportSummary {
        val started = clock()
        val batch = ArrayList<CatalogItem>(batchSize)
        val perKind = IntArray(MediaKind.entries.size)
        var imported = 0
        var groups = 0
        var cancelled = false
        val failures = ArrayList<Pair<String, Throwable>>()

        writer.begin(sourceId, mode)
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

                // ------------------------------------------------ bounded concurrency
                //
                // This was a strictly sequential `for (category in categories)`, one
                // round trip at a time, and on a real subscription that is 497 calls at
                // ~0.4s each before the first row could be read. It is replaced by a
                // fixed number of workers pulling from the same list, never by
                // `async` per category: 497 sockets opened at once is a denial of
                // service aimed at the user's own provider, and the panel it talks to
                // is usually a single PHP host.
                //
                // The writer is untouched by all of this. It is one SQLite transaction
                // and is not thread-safe, so workers only ever *parse*; every write
                // happens on this thread, draining the channel below. That is also what
                // keeps memory O(batch) rather than O(category): a worker hands over
                // batches as it parses them and blocks when the channel is full, so the
                // most that is ever in flight is the channel's capacity plus one batch
                // per worker.
                // Groups are written here, in the provider's own order, before a single
                // worker starts — not emitted by the workers as they finish.
                //
                // They arrive out of order otherwise, and `RoomCatalogWriter` numbers
                // them by insertion, so the category rail would come out in a different
                // order after every import. That is the same defect as scrambled channel
                // numbers and it is fixed the same way: order comes from the provider's
                // list, never from whichever reply landed first. Affordable because the
                // category list is one small request already held in memory — the
                // repository's own contract calls it safe to hold.
                val ordered = categories.withIndex().map { (index, category) ->
                    // A category named "RADIO" holds stations, not channels; they get
                    // their own kind so no live query has to exclude them.
                    val kind = if (endpointKind == MediaKind.LIVE && MediaClassifier.isRadioLabel(category.name)) {
                        MediaKind.RADIO
                    } else {
                        endpointKind
                    }
                    CategoryPlan(index, category, kind, StableIds.group(sourceId, kind, category.name))
                }
                for (plan in ordered) {
                    writer.writeGroups(listOf(MediaGroup(plan.groupId, plan.category.name, plan.kind)))
                    groups++
                }

                val events = Channel<CategoryEvent>(capacity = concurrency)

                runBlocking {
                    val producers = launch {
                        val gate = Semaphore(concurrency)
                        coroutineScope {
                            for (plan in ordered) {
                                launch(Dispatchers.IO) {
                                    gate.withPermit {
                                        if (!isCancelled()) {
                                            fetchCategory(sourceId, api, plan, events)
                                        }
                                    }
                                }
                            }
                        }
                        events.close()
                    }

                    for (event in events) {
                        when (event) {
                            is CategoryEvent.Items -> {
                                for (item in event.items) {
                                    perKind[event.kind.ordinal]++
                                    batch.add(item)
                                    if (batch.size >= batchSize) {
                                        imported += flush(batch)
                                        onProgress(ImportProgress.Importing(imported, groups, event.kind))
                                    }
                                }
                                // Committed as each category arrives rather than at the
                                // end, so the rows are readable while the rest is still
                                // downloading. This is what "first content" means here.
                                imported += flush(batch)
                                onProgress(ImportProgress.Importing(imported, groups, event.kind))
                            }

                            // ----------------------------------------- partial failure
                            //
                            // One category that would not load no longer takes the
                            // catalogue with it. Before this, the whole import threw and
                            // `writer.abort` ended it, so a single bad request after 400
                            // good ones left the user with an activation screen. The
                            // failure is counted and named; the other categories carry on.
                            is CategoryEvent.Failed -> failures.add(event.category to event.cause)
                        }
                        if (isCancelled()) {
                            cancelled = true
                            break
                        }
                    }

                    // The channel is cancelled *before* the producers, and the order is
                    // load-bearing rather than tidy. A worker hands batches over with
                    // `trySendBlocking`, which parks the OS thread rather than
                    // suspending the coroutine — so `producers.cancel()` cannot reach a
                    // worker that is waiting for room, and the `join()` below would wait
                    // for a thread that is waiting for a consumer that has already
                    // stopped consuming. Cancelling the channel first makes those sends
                    // throw, which releases the thread and lets the cancel land.
                    events.cancel()
                    if (cancelled) producers.cancel()
                    producers.join()
                }

                // Asked again outside the consumer loop, because a run cancelled before
                // any event arrived would otherwise finish and report itself successful:
                // the only place `cancelled` was set is inside a loop that never ran.
                if (isCancelled()) cancelled = true
            }

            imported += flush(batch)

            // Partial failure survives; total failure does not pretend to be success.
            // If not one category yielded a row and at least one of them failed, the
            // honest outcome is that first failure -- reported with its own cause, so
            // the caller still shows "timed out" or "unreachable" rather than the
            // "your provider carries nothing" that an empty success would produce.
            if (imported == 0 && failures.isNotEmpty() && !cancelled) throw failures.first().second

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
                            providerRef = episode.episodeId,
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
        // What `get_short_epg` and catch-up are addressed by.
        providerRef = stream.streamId,
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
        providerRef = series.seriesId,
        providerOrder = order,
        seriesId = StableIds.seriesByProviderId(sourceId, series.seriesId),
        seriesTitle = series.name,
        seasonNumber = null,
        episodeNumber = null,
    )

    /**
     * One category, fetched and parsed off the writer's thread.
     *
     * Everything here is read-only with respect to the database: the worker produces
     * [CategoryEvent]s and the single consumer performs every write. Batches are handed
     * over as they fill rather than at the end, so a category with 20,000 channels never
     * exists in memory as 20,000 objects — the channel's backpressure blocks the worker
     * instead, which is the same O(batch) guarantee the sequential loop gave.
     *
     * A failure is *reported*, never thrown. That is the whole of requirement 5: this
     * used to propagate out of the loop into `writer.abort` and end the import, so one
     * unlucky request discarded every category that had already succeeded.
     */
    private suspend fun fetchCategory(
        sourceId: String,
        api: Api,
        plan: CategoryPlan,
        events: kotlinx.coroutines.channels.SendChannel<CategoryEvent>,
    ) {
        val kind = plan.kind
        val groupId = plan.groupId
        val category = plan.category

        try {
            // Provider order, preserved under concurrency.
            //
            // The sequential loop used one counter incremented across the whole import,
            // which is the provider's own ordering and — for live television — the
            // channel numbering every remote navigates by. Categories now finish out of
            // order, so a shared counter would shuffle the channel list differently on
            // every import. The position is derived from the category's index instead,
            // which gives the same ordering the sequential loop produced and gives it
            // deterministically.
            var position = plan.index * CATEGORY_STRIDE
            val batch = ArrayList<CatalogItem>(batchSize)

            when (kind) {
                MediaKind.SERIES -> api.series(category.id).use { reader ->
                    XtreamParser.parseSeries(reader) { series ->
                        batch.add(seriesShell(sourceId, series, groupId, position++))
                        if (batch.size >= batchSize) {
                            events.trySendBlocking(CategoryEvent.Items(kind, ArrayList(batch)))
                            batch.clear()
                        }
                    }
                }

                else -> api.streams(kind, category.id).use { reader ->
                    XtreamParser.parseStreams(reader) { stream ->
                        batch.add(item(sourceId, api, kind, stream, groupId, position++))
                        if (batch.size >= batchSize) {
                            events.trySendBlocking(CategoryEvent.Items(kind, ArrayList(batch)))
                            batch.clear()
                        }
                    }
                }
            }
            if (batch.isNotEmpty()) events.send(CategoryEvent.Items(kind, batch))
        } catch (t: Throwable) {
            // Rethrown only for cancellation, which is not a failure of the category:
            // swallowing it would leave a worker running after the import was stopped.
            if (t is kotlinx.coroutines.CancellationException) throw t
            events.send(CategoryEvent.Failed(category.name, t))
        }
    }

    /** One category, with everything decided about it before any request is made. */
    private data class CategoryPlan(
        val index: Int,
        val category: XtreamCategory,
        val kind: MediaKind,
        val groupId: String,
    )

    /**
     * What a worker hands to the writer's thread.
     *
     * Deliberately three cases and no "finished": the consumer stops when the channel
     * closes, which happens exactly once, after every worker has returned.
     */
    private sealed interface CategoryEvent {
        data class Items(val kind: MediaKind, val items: List<CatalogItem>) : CategoryEvent
        data class Failed(val category: String, val cause: Throwable) : CategoryEvent
    }

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

        /**
         * Requests in flight at once, by default.
         *
         * Four is a starting point chosen to be obviously safe rather than optimal: it
         * is four times fewer round trips end to end than the sequential loop it
         * replaces, and it is far below the point at which a single-host Xtream panel
         * starts refusing a subscription. Raise it from measurements on a real
         * provider, never from the fact that a bigger number sounds faster.
         */
        const val DEFAULT_CONCURRENCY = 4

        /**
         * The ceiling, which exists so nobody can turn this into 497 parallel requests.
         *
         * Above this, the thing being optimised stops being the user's wait and starts
         * being someone else's server.
         */
        const val MAX_CONCURRENCY = 8

        /**
         * How much ordering space each category is given.
         *
         * Categories are parsed concurrently and therefore finish out of order, so a
         * row's position is derived from its category's index rather than from a shared
         * counter — see `fetchCategory`. The stride has to exceed the largest category
         * a provider can ship, or two categories would interleave; 100,000 is well past
         * anything observed and still leaves room for 21,000 categories inside an Int.
         */
        const val CATEGORY_STRIDE = 100_000
    }
}
