package com.castivio.data.playlist

import com.castivio.core.common.AppDispatchers
import com.castivio.core.common.AppError
import com.castivio.core.common.Outcome
import com.castivio.data.parsing.SourceIds
import com.castivio.data.parsing.XtreamImportEngine
import com.castivio.domain.CatalogSectionStore
import com.castivio.domain.CatalogSections
import com.castivio.domain.CatalogWriter
import com.castivio.domain.MediaGroup
import com.castivio.domain.MediaKind
import com.castivio.domain.PlaylistSource
import com.castivio.domain.ProviderSource
import com.castivio.domain.SourceKind
import com.castivio.domain.SourceRepository
import kotlinx.coroutines.withContext

/**
 * One section at a time, from the provider the app is signed in to.
 *
 * ## What this replaces
 *
 * Signing in used to import the catalogue: every category of every kind, before the
 * user had said whether they came for football or for films. On a real provider that
 * is tens of thousands of rows and minutes of waiting, almost all of it for data
 * nobody opens. This is the other half of removing that — the part that fetches a
 * section when a section is asked for.
 *
 * ## What each call costs
 *
 *  - [categories] — one request. Opening Live TV asks `get_live_categories` and
 *    nothing else; films and series are not touched.
 *  - [items] — one request, for the one category the user opened.
 *  - [episodes] — one request, for the one show the user opened.
 *
 * ## Why it can be called freely
 *
 * A screen asks on every visit, and this answers most of those from what is already
 * stored: a section listed recently is not re-listed, and a category whose rows are on
 * the device is not re-fetched. So "load when shown" does not become "load every time
 * it is shown", and the freshness rule lives here rather than being remembered by each
 * screen.
 *
 * A [Freshness] window rather than "once, forever": providers add channels, and a
 * category that can never be refreshed is a category that is wrong until the app is
 * reinstalled.
 */
class XtreamCatalogSections(
    private val sources: SourceRepository,
    private val writerFactory: () -> CatalogWriter,
    private val groups: CatalogSectionStore,
    private val apiFactory: (PlaylistSource.Xtream) -> XtreamImportEngine.Api,
    private val dispatchers: AppDispatchers,
    private val clock: () -> Long = System::currentTimeMillis,
) : CatalogSections {

    override suspend fun categories(kind: MediaKind): Outcome<Int> = run(kind.name) { source, sourceId ->
        val stored = groups.groupsNow(kind)
        // Already listed, recently enough. One database read instead of a request, and
        // the difference is visible: returning to a section is instant.
        if (stored.isNotEmpty() && !Freshness.stale(listedAtOf(stored), clock())) {
            return@run stored.size
        }
        XtreamImportEngine(writerFactory(), clock = clock)
            .importCategories(sourceId, apiFactory(source), kind)
            .size
    }

    override suspend fun items(groupId: String): Outcome<Int> = run(groupId) { source, sourceId ->
        val group = groups.group(groupId) ?: return@run 0
        if (!Freshness.stale(group.itemsLoadedAtMs, clock())) return@run ALREADY_LOADED
        val written = XtreamImportEngine(writerFactory(), clock = clock)
            .importCategory(sourceId, apiFactory(source), group)
        // Stamped even when the provider returned nothing, so an empty category is
        // answered from the device next time instead of being asked again on every
        // visit. An empty category is a fact about the provider, not a failed request.
        groups.markItemsLoaded(groupId, clock())
        written
    }

    override suspend fun episodes(seriesId: String): Outcome<Int> = run(seriesId) { source, sourceId ->
        if (groups.hasEpisodes(seriesId)) return@run ALREADY_LOADED
        val show = groups.show(seriesId) ?: return@run 0
        XtreamImportEngine(writerFactory(), clock = clock).importEpisodes(
            sourceId = sourceId,
            api = apiFactory(source),
            providerSeriesId = show.providerRef,
            seriesTitle = show.title,
            groupId = show.groupId,
        )
    }

    /**
     * The active provider, the IO dispatcher, and one place that turns a thrown
     * exception into an answer.
     *
     * Every failure here is scoped to the section being loaded. A category that will
     * not fetch leaves the rest of the app exactly as usable as it was — which is the
     * whole advantage of loading sections separately, and would be given away by
     * letting one failure propagate as an application error.
     *
     * @param what included in nothing but a name for the lambda's readability; the
     *   failure carries the error, not the label.
     */
    private suspend inline fun run(
        @Suppress("UNUSED_PARAMETER") what: String,
        crossinline block: suspend (PlaylistSource.Xtream, String) -> Int,
    ): Outcome<Int> = withContext(dispatchers.io) {
        val active = sources.activeNow()
            ?: return@withContext Outcome.Failure(AppError.NOT_CONFIGURED)
        val source = active.asXtream()
            ?: return@withContext Outcome.Failure(AppError.NOT_CONFIGURED)
        try {
            Outcome.Success(block(source, SourceIds.of(source)))
        } catch (e: Exception) {
            Outcome.Failure(e.toAppError(), e)
        }
    }

    /**
     * The stored provider as credentials, or null when it is not an Xtream panel.
     *
     * Null rather than an exception for a playlist source: its rows came in with the
     * playlist and there is nothing to fetch, which the caller reads as "nothing to do"
     * because the section is already populated.
     */
    private fun ProviderSource.asXtream(): PlaylistSource.Xtream? {
        if (kind != SourceKind.XTREAM) return null
        val host = url ?: return null
        val user = username ?: return null
        val secret = password ?: return null
        return PlaylistSource.Xtream(host, user, secret)
    }

    /**
     * When this section's category list was last written.
     *
     * The newest of them, not the oldest: they are all written by one listing, and a
     * single row left over from a provider's earlier shape must not make the section
     * look stale on every visit.
     */
    private fun listedAtOf(stored: List<MediaGroup>): Long? =
        stored.maxOfOrNull { it.listedAtMs }?.takeIf { it > 0 }

    private fun Exception.toAppError(): AppError = when (this) {
        is java.net.SocketTimeoutException -> AppError.TIMEOUT
        is java.net.UnknownHostException -> AppError.NETWORK_UNAVAILABLE
        is com.castivio.data.parsing.JsonFormatException -> AppError.MALFORMED_PLAYLIST
        is java.io.IOException -> AppError.NETWORK_UNAVAILABLE
        else -> AppError.UNKNOWN
    }

    private companion object {
        /**
         * What a call answers when the section was already on the device.
         *
         * Zero would be indistinguishable from "the provider has nothing here", and
         * those two produce opposite screens — one shows the rows that are already
         * stored, the other explains an empty category.
         */
        const val ALREADY_LOADED = -1
    }
}

/**
 * When a stored section is too old to serve without asking again.
 *
 * Pure, and its own type, because it is the rule that decides how often this app talks
 * to a provider at all — which is exactly the kind of thing that gets quietly changed
 * to "always" by a bug fix if it lives inside a coroutine.
 */
object Freshness {

    /**
     * Twelve hours, matching the catalogue refresh policy.
     *
     * Long enough that a session of browsing never re-fetches anything; short enough
     * that a channel added this morning is there tonight.
     */
    const val MAX_AGE_MS = 12 * 60 * 60 * 1000L

    /**
     * @param loadedAtMs null means never loaded, which is always stale.
     */
    fun stale(loadedAtMs: Long?, nowMs: Long, maxAgeMs: Long = MAX_AGE_MS): Boolean {
        val at = loadedAtMs ?: return true
        // A clock that moved backwards — a TV box with no battery-backed clock, or a
        // timezone set during setup — must not pin a section as fresh forever.
        if (nowMs < at) return true
        return nowMs - at >= maxAgeMs
    }
}
