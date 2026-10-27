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
    /** Waits between two tries of a page; a parameter so a test does not sleep. */
    private val pause: (Long) -> Unit = { Thread.sleep(it) },
) {

    /** What the engine needs from the network, and nothing more. */
    interface Api {
        /**
         * One page of channels, handed over a channel at a time.
         *
         * **Throws when the page could not be read** -- a timeout, a dropped connection, an
         * answer that is not a portal's. It must not return zero for that: zero means the
         * portal has nothing more, and a page that failed read as the end of the list is how
         * an import stopped at page 37 of 300 and called itself done.
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
        /** Told what the pages came to, once, when the reading ends. */
        onReport: (PortalReport) -> Unit = {},
    ): Int {
        writer.begin(sourceId, mode)

        val batch = ArrayList<CatalogItem>(batchSize)
        var written = 0
        var order = 0
        var page = 1
        var total = 0
        var pages = 0
        var received = 0
        var unplayable = 0
        var firstOfPrevious: String? = null

        val flush = {
            writer.writeItems(batch)
            written += batch.size
            batch.clear()
            onProgress(ImportProgress.Importing(written, groupsReady = 0, kind = kind))
        }

        while (!isCancelled()) {
            // The page is read whole before any of it is used, so a try that fails halfway
            // through leaves nothing behind to be counted twice by the next.
            val fetched = readPage(page, isCancelled) { rows ->
                if (kind == MediaKind.LIVE) {
                    api.channels(page) { channel ->
                        rows += Row(channel.id, channel.command) { url, at -> channel.asItem(sourceId, url, at) }
                    }
                } else {
                    api.items(kind, page) { item ->
                        rows += Row(item.id, item.command) { url, at -> item.asItem(sourceId, url, at) }
                    }
                }
            } ?: break
            if (fetched.stated > 0) total = fetched.stated
            val rows = fetched.rows

            // Two ways to know there is no more, and both are needed. A portal that
            // states a total stops when the pages have delivered it; one that states
            // none stops on the first page that brought nothing. Relying on the total
            // alone loops forever against the portals that send zero.
            if (rows.isEmpty()) break

            // A third, for the portal that ignores the page number and answers every
            // request with the first page. Without it the only thing standing between that
            // portal and an import that never ends was a fixed count of pages -- which
            // stopped every honest library longer than the count, at the same row.
            val first = rows.first().id
            if (first == firstOfPrevious) break
            firstOfPrevious = first

            pages++
            received += rows.size
            for (row in rows) {
                // A row the portal will not give an address for is not a row. A
                // catalogue that listed it would be a list of names that do nothing
                // when pressed, which is worse than a shorter list.
                val url = row.command?.let { playable(api, kind, it) }
                if (url == null) {
                    unplayable++
                    continue
                }
                batch += row.asItem(url, order++)
                if (batch.size >= batchSize) flush()
            }
            // Counted by what the portal delivered, not by what could be played: a portal
            // that states its total counts the rows it listed, playable or not.
            if (total > 0 && received >= total) break
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
        onReport(PortalReport(pages = pages, received = received, written = written, unplayable = unplayable, stated = total))
        return written
    }

    /** One row of a page, whichever list it came from, before its address is known. */
    private class Row(val id: String, val command: String?, val asItem: (url: String, order: Int) -> CatalogItem)

    private class Page(val rows: List<Row>, val stated: Int)

    /**
     * One page, tried again when it fails, and never mistaken for the end of the list.
     *
     * A page that cannot be read is retried [PAGE_RETRIES] times. If it still cannot, the
     * failure is thrown -- except for the first page, which is read as an empty section:
     * a portal that does not serve a kind may answer the call with something this reader
     * cannot use, and that has always meant "nothing here". After the first page the portal
     * has proved it serves the kind, so a failure is a loss and is reported as one: the
     * import ends in failure, keeps what it committed, and is retried by the user, instead
     * of ending as a short library that says it is whole.
     *
     * @return null when the first page could not be read at all.
     */
    private fun readPage(page: Int, isCancelled: () -> Boolean, read: (MutableList<Row>) -> Int): Page? {
        var attempt = 0
        while (true) {
            val rows = ArrayList<Row>()
            try {
                val stated = read(rows)
                return Page(rows, stated)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw e
            } catch (e: Exception) {
                if (isCancelled()) throw e
                if (attempt >= PAGE_RETRIES) {
                    if (page == 1) return null
                    throw e
                }
                attempt++
                pause(PAGE_BACKOFF_MS * attempt)
            }
        }
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
        details = details,
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

    companion object {
        const val DEFAULT_BATCH = 500

        /**
         * A ceiling, so a portal that pages forever cannot hold an import open.
         *
         * It used to be 400, on the belief that it was far past any real library. A portal
         * serves fourteen rows a page as often as a hundred, and 400 pages of fourteen is
         * 5,600 rows: a film library of two hundred thousand stopped there, silently, and
         * finished as a success. The portal that answers every page with the same content,
         * which this once guarded against, is caught by comparing a page with the one before
         * it; what is left here is only the bound on a portal that never ends and never
         * repeats, which is far beyond any library a device can hold.
         */
        const val MAX_PAGES = 100_000

        /** How many more times a page is asked for after it fails. */
        const val PAGE_RETRIES = 2

        /** The wait before the first retry; the second waits twice as long. */
        const val PAGE_BACKOFF_MS = 500L
    }
}

/**
 * What a portal's pages came to, told once when the reading ends.
 *
 * Figures only. [received] against [stated] is the check that a library is whole: a portal
 * that states a total and delivers fewer rows lost some, and [unplayable] says how many it
 * listed but would not give an address for.
 */
data class PortalReport(
    /** Pages that delivered rows. */
    val pages: Int,
    /** Rows the portal listed. */
    val received: Int,
    /** Rows written: the listed ones that had an address. */
    val written: Int,
    /** Listed rows the portal would not give an address for, and so were not written. */
    val unplayable: Int,
    /** The total the portal said it holds; zero when it said nothing. */
    val stated: Int,
)
