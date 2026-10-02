package com.castivio.data.parsing

import com.castivio.domain.CatalogItem
import com.castivio.domain.CatalogWriter
import com.castivio.domain.ImportMode
import com.castivio.domain.ImportProgress
import com.castivio.domain.MediaKind

/**
 * Turns a portal's channel list into catalogue rows.
 *
 * ## Why it is an engine and not twenty lines in the importer
 *
 * The same reason [XtreamImportEngine] is one: this is where the decisions live that
 * have to be testable without a server — how a page becomes rows, when to stop asking
 * for pages, what a channel with nothing playable does to the count — and keeping them
 * in a pure module is what lets them be tested against fixtures and benchmarked on
 * every commit.
 *
 * The HTTP side is `:data:networking`'s, behind [Api]. This never builds a URL and
 * never opens a socket.
 *
 * ## Four kinds, each asked for separately
 *
 * A portal answers `itv`, `vod`, `series` and `radio` on separate calls, so a section
 * is a real request rather than a filter over one download — the same shape Xtream
 * has, and the reason `LoadSection` can fetch one section when it is opened.
 *
 * **Not every portal answers all four.** An installation with no films answers the
 * `vod` call with an empty list, or with the envelope's `false`, or with nothing this
 * reader can use. All three mean the same thing and all three produce no rows: the
 * section is empty, which is the truth, rather than filled with placeholders that fail
 * when pressed.
 *
 * ## A command is not always an address
 *
 * `cmd` is an instruction. Often it carries a URL plainly; often it is `/media/…`,
 * which the portal resolves per play through `create_link`. The engine asks — that is
 * what [Api.resolve] is — and writes nothing for a row the portal would not resolve.
 * No link is ever constructed here from parts.
 */
class StalkerImportEngine(
    private val batchSize: Int = DEFAULT_BATCH,
) {

    /** What the engine needs from the network, and nothing more. */
    interface Api {
        /**
         * One page of channels, handed over a channel at a time.
         *
         * @return the total the portal states, or zero when it states none — then an
         *   empty page is the only end-of-list signal there is.
         */
        fun channels(page: Int, onChannel: (StalkerChannel) -> Unit): Int

        /**
         * One page of films, shows or stations, by the same contract as [channels].
         *
         * A portal that does not serve [kind] answers with nothing, and nothing is
         * what this hands over. It is not an error: a portal with no film library is
         * an ordinary portal.
         */
        fun items(kind: MediaKind, page: Int, onItem: (StalkerItem) -> Unit): Int

        /**
         * The playable address for a command, asked of the portal when it has to be.
         *
         * A command that carries a URL plainly needs no call and this is not asked.
         * A `/media/…` command is resolved through the protocol's own `create_link`,
         * which is the only honest way to turn one into an address. Null means the
         * portal would not resolve it, and then no row is written — an invented link
         * is a row that fails in the player instead of a row that is absent.
         */
        fun resolve(kind: MediaKind, command: String): String?
    }

    /**
     * Imports the portal's channels into [writer].
     *
     * Paged, because one response holding 50,000 channels is a response that cannot be
     * held — and batched into the writer for the same reason, so the memory in flight
     * is one batch rather than one catalogue.
     *
     * @param isCancelled checked between pages and between batches, so a cancelled
     *   import stops within one round trip rather than draining the portal.
     */
    fun importChannels(
        sourceId: String,
        api: Api,
        writer: CatalogWriter,
        mode: ImportMode = ImportMode.REPLACE,
        onProgress: (ImportProgress) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): Int = importKind(sourceId, MediaKind.LIVE, api, writer, mode, onProgress, isCancelled)

    /**
     * Imports one section.
     *
     * [MediaKind.LIVE] reads the channel list; the other three read the item list for
     * their own type. Everything after that — paging, batching, resolving a command,
     * refusing a row that cannot be played — is the same for all four, which is why
     * there is one loop rather than two that drift.
     */
    fun importKind(
        sourceId: String,
        kind: MediaKind,
        api: Api,
        writer: CatalogWriter,
        mode: ImportMode = ImportMode.REPLACE,
        onProgress: (ImportProgress) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): Int {
        writer.begin(sourceId, mode)

        val batch = ArrayList<CatalogItem>(batchSize)
        var written = 0
        var order = 0
        var page = 1
        var total = 0

        val flush = {
            writer.writeItems(batch)
            written += batch.size
            batch.clear()
            onProgress(ImportProgress.Importing(written, groupsReady = 0, kind = kind))
        }

        while (!isCancelled()) {
            var onThisPage = 0
            val stated = if (kind == MediaKind.LIVE) {
                api.channels(page) { channel ->
                    onThisPage++
                    // A row the portal will not give an address for is not a row. A
                    // catalogue that listed it would be a list of names that do nothing
                    // when pressed, which is worse than a shorter list.
                    playable(api, MediaKind.LIVE, channel.command)?.let { url ->
                        batch += channel.asItem(sourceId, url, order++)
                        if (batch.size >= batchSize) flush()
                    }
                }
            } else {
                api.items(kind, page) { item ->
                    onThisPage++
                    val url = item.command?.let { playable(api, kind, it) }
                    if (url != null) {
                        batch += item.asItem(sourceId, url, order++)
                        if (batch.size >= batchSize) flush()
                    }
                }
            }
            if (stated > 0) total = stated

            // Two ways to know there is no more, and both are needed. A portal that
            // states a total stops when the pages have delivered it; one that states
            // none stops on the first page that brought nothing. Relying on the total
            // alone loops forever against the portals that send zero.
            if (onThisPage == 0) break
            if (total > 0 && order >= total) break
            if (page >= MAX_PAGES) break
            page++
        }

        if (batch.isNotEmpty() && !isCancelled()) {
            writer.writeItems(batch)
            written += batch.size
        }
        if (!isCancelled()) {
            writer.commit()
        }
        return written
    }

    /**
     * The address for a command, asked of the portal only when it has to be.
     *
     * A command that already carries one costs no request, which on a list of fifty
     * thousand channels is the difference between an import and a denial of service
     * against the user's own provider.
     */
    private fun playable(api: Api, kind: MediaKind, command: String): String? =
        StalkerParser.addressIn(command) ?: api.resolve(kind, command)

    private fun StalkerItem.asItem(sourceId: String, url: String, order: Int) = CatalogItem(
        id = StableIds.item(sourceId, "portal:${kind.name}:$id"),
        sourceId = sourceId,
        kind = kind,
        title = name,
        streamUrl = url,
        artworkUrl = posterUrl,
        providerRef = id,
        providerOrder = order,
        durationSeconds = durationSeconds,
        epgChannelId = null,
    )

    private fun StalkerChannel.asItem(sourceId: String, url: String, order: Int) = CatalogItem(
        id = StableIds.item(sourceId, "portal:$id"),
        sourceId = sourceId,
        kind = MediaKind.LIVE,
        title = name,
        streamUrl = url,
        artworkUrl = logo,
        // The portal's own id, kept for the same reason Xtream's is: calls that are
        // addressed by it — a catch-up window on a channel with an archive — are
        // impossible once a row id has been hashed.
        providerRef = id,
        providerOrder = order,
        // A portal does not hand out an XMLTV id. Joining the guide on the channel's
        // own id would join it to nothing, and inventing one from the name would join
        // it to the wrong programme.
        epgChannelId = null,
    )

    private companion object {
        const val DEFAULT_BATCH = 500

        /**
         * A ceiling, so a portal that pages forever cannot hold an import open.
         *
         * Fourteen pages of the size portals serve is well past the largest real
         * subscription seen; what it protects against is an installation that answers
         * every page with the same content, which is a real failure mode and one that
         * otherwise ends as a device filling its storage.
         */
        const val MAX_PAGES = 400
    }
}
