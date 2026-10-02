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
 * ## One kind, and it is live
 *
 * A Stalker portal's `itv` list is television. Films and series live behind separate
 * calls that many installations do not answer at all, and a portal that has them
 * describes them differently enough that guessing would produce a library of broken
 * rows. So this imports channels, honestly, and the sections that have nothing to
 * import say so rather than showing a partial guess.
 */
class StalkerImportEngine(
    private val batchSize: Int = DEFAULT_BATCH,
) {

    /** What the engine needs from the network, and nothing more. */
    interface Api {
        /**
         * One page, handed over a channel at a time.
         *
         * @return the total the portal states, or zero when it states none — then an
         *   empty page is the only end-of-list signal there is.
         */
        fun channels(page: Int, onChannel: (StalkerChannel) -> Unit): Int

        /**
         * The playable address for a channel.
         *
         * Separate from the channel itself because a portal command is not always a
         * URL: the `/media/…` form is resolved by the portal per play, and only the
         * networking layer can ask it to. Null means this channel cannot be played,
         * and a row is not written for it.
         */
        fun streamUrl(channel: StalkerChannel): String?
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
    ): Int {
        writer.begin(sourceId, mode)

        val batch = ArrayList<CatalogItem>(batchSize)
        var written = 0
        var order = 0
        var page = 1
        var total = 0

        while (!isCancelled()) {
            var onThisPage = 0
            val stated = api.channels(page) { channel ->
                onThisPage++
                val url = api.streamUrl(channel)
                // A channel the portal will not give an address for is not a row. A
                // catalogue that listed it would be a list of names that do nothing
                // when pressed, which is worse than a shorter list.
                if (url != null) {
                    batch += channel.asItem(sourceId, url, order++)
                    if (batch.size >= batchSize) {
                        writer.writeItems(batch)
                        written += batch.size
                        batch.clear()
                        onProgress(ImportProgress.Importing(written, groupsReady = 0, kind = MediaKind.LIVE))
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
