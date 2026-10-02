package com.castivio.data.parsing

import com.castivio.domain.CatalogItem
import com.castivio.domain.ImportMode
import com.castivio.domain.ImportSummary
import com.castivio.domain.MediaGroup
import com.castivio.domain.MediaKind
import com.castivio.domain.CatalogWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a portal's channel list becomes, and when the asking stops.
 *
 * Paging is the half worth gating. A portal that states a total and one that states
 * none are both common, and an engine that trusted only the first loops forever
 * against the second — which on a device is an import that never finishes and a
 * storage bar that keeps climbing.
 */
class StalkerImportEngineTest {

    /* ------------------------------------------------------------------- fixtures */

    private class Recorder : CatalogWriter {
        val items = mutableListOf<CatalogItem>()
        var began: ImportMode? = null
        var committed = false
        var finished = false

        override fun begin(sourceId: String, mode: ImportMode) { began = mode }
        override fun writeGroups(groups: List<MediaGroup>) = Unit
        override fun writeItems(items: List<CatalogItem>) { this.items += items }
        override fun commit() { committed = true }
        override fun finish(summary: ImportSummary) { finished = true }
        override fun abort(cause: Throwable?) = Unit
    }

    private fun channel(id: String, name: String = "Channel $id", cmd: String = "http://h/$id.ts") =
        StalkerChannel(id = id, name = name, command = cmd, logo = null, number = id, hasArchive = false)

    /** A portal serving [pages], and resolving every command to a playable address. */
    private class Portal(
        private val pages: List<List<StalkerChannel>>,
        private val statesTotal: Boolean,
    ) : StalkerImportEngine.Api {
        var requested = 0
        override fun channels(page: Int, onChannel: (StalkerChannel) -> Unit): Int {
            requested++
            pages.getOrNull(page - 1)?.forEach(onChannel)
            return if (statesTotal) pages.sumOf { it.size } else 0
        }
        override fun items(kind: MediaKind, page: Int, onItem: (StalkerItem) -> Unit): Int = 0
        override fun resolve(kind: MediaKind, command: String): String? = null
    }

    /* --------------------------------------------------------------------- paging */

    @Test
    fun `a portal that states its total stops when it has delivered it`() {
        val portal = Portal(
            pages = listOf(listOf(channel("1"), channel("2")), listOf(channel("3"))),
            statesTotal = true,
        )
        val writer = Recorder()

        val written = StalkerImportEngine().importChannels("src", portal, writer)

        assertEquals(3, written)
        assertEquals(listOf("Channel 1", "Channel 2", "Channel 3"), writer.items.map { it.title })
        assertEquals("it kept asking after the total was in", 2, portal.requested)
    }

    /**
     * And one that states nothing stops on the first empty page.
     *
     * This is the case that has no second signal. Without it the loop has no end at
     * all, which is why it is a test rather than a comment.
     */
    @Test
    fun `a portal that states no total stops on the first empty page`() {
        val portal = Portal(pages = listOf(listOf(channel("1")), listOf(channel("2"))), statesTotal = false)
        val writer = Recorder()

        val written = StalkerImportEngine().importChannels("src", portal, writer)

        assertEquals(2, written)
        assertEquals("it did not probe one page past the end", 3, portal.requested)
    }

    /** A portal with nothing in it imports nothing, and says so by finishing. */
    @Test
    fun `an empty portal writes nothing and still commits`() {
        val writer = Recorder()
        val written = StalkerImportEngine().importChannels("src", Portal(emptyList(), false), writer)

        assertEquals(0, written)
        assertTrue(writer.items.isEmpty())
        assertTrue("an empty import must still close its transaction", writer.committed)
    }

    /* ---------------------------------------------------------------- the channels */

    /**
     * A channel the portal will not give an address for is not a row.
     *
     * The alternative is a catalogue listing names that do nothing when pressed, and
     * a user cannot tell that from a broken player.
     */
    @Test
    fun `a channel with no playable address is skipped`() {
        val portal = object : StalkerImportEngine.Api {
            override fun channels(page: Int, onChannel: (StalkerChannel) -> Unit): Int {
                if (page == 1) {
                    onChannel(channel("1"))
                    onChannel(channel("2", cmd = "/media/2.mpg"))
                }
                return 0
            }
            override fun items(kind: MediaKind, page: Int, onItem: (StalkerItem) -> Unit): Int = 0
            override fun resolve(kind: MediaKind, command: String): String? = null
        }
        val writer = Recorder()

        val written = StalkerImportEngine().importChannels("src", portal, writer)

        assertEquals(1, written)
        assertEquals(listOf("Channel 1"), writer.items.map { it.title })
    }

    @Test
    fun `every row is live, carries the provider's id, and keeps the portal's order`() {
        val portal = Portal(listOf(listOf(channel("7"), channel("3"))), statesTotal = true)
        val writer = Recorder()

        StalkerImportEngine().importChannels("src", portal, writer)

        assertTrue(writer.items.all { it.kind == MediaKind.LIVE })
        assertEquals(listOf("7", "3"), writer.items.map { it.providerRef })
        assertEquals(listOf(0, 1), writer.items.map { it.providerOrder })
    }

    /**
     * No EPG id is invented.
     *
     * A portal hands out none. Joining the guide on the channel's own id would join it
     * to nothing; deriving one from the name would join it to the wrong programme,
     * which is the worse of the two because it looks like it worked.
     */
    @Test
    fun `no programme id is invented for a portal channel`() {
        val writer = Recorder()
        StalkerImportEngine().importChannels("src", Portal(listOf(listOf(channel("1"))), true), writer)

        assertEquals(null, writer.items.single().epgChannelId)
    }

    /**
     * The row id survives a re-import and differs between subscriptions.
     *
     * It is what favourites and watch positions hang off, so a portal re-imported
     * tomorrow has to produce the same ids — and the same portal added twice on one
     * box must not collide with itself.
     */
    @Test
    fun `row ids are stable per source and distinct between sources`() {
        val one = Recorder()
        val two = Recorder()
        StalkerImportEngine().importChannels("src", Portal(listOf(listOf(channel("1"))), true), one)
        StalkerImportEngine().importChannels("src", Portal(listOf(listOf(channel("1"))), true), two)
        assertEquals(one.items.single().id, two.items.single().id)

        val other = Recorder()
        StalkerImportEngine().importChannels("other", Portal(listOf(listOf(channel("1"))), true), other)
        assertNotEquals(one.items.single().id, other.items.single().id)
    }

    /* --------------------------------------------------------------- cancellation */

    /**
     * A cancelled import stops within a page and commits nothing.
     *
     * The writer is left to be aborted by the caller, which is the contract the other
     * engines keep: a half-written transaction that committed itself is a catalogue
     * with half a provider in it and no way to tell.
     */
    @Test
    fun `a cancelled import neither finishes its pages nor commits`() {
        val portal = Portal(List(5) { listOf(channel("$it")) }, statesTotal = false)
        val writer = Recorder()

        val written = StalkerImportEngine().importChannels(
            sourceId = "src",
            api = portal,
            writer = writer,
            isCancelled = { true },
        )

        assertEquals(0, written)
        assertEquals("it asked the portal for a page it had been told to stop before", 0, portal.requested)
        assertTrue("a cancelled import must not commit", !writer.committed)
    }

    /* -------------------------------------------------------------------- batching */

    /** Memory in flight is one batch, not one catalogue. */
    @Test
    fun `rows are handed over in batches rather than all at once`() {
        val batches = mutableListOf<Int>()
        val writer = object : CatalogWriter {
            val all = mutableListOf<CatalogItem>()
            override fun begin(sourceId: String, mode: ImportMode) = Unit
            override fun writeGroups(groups: List<MediaGroup>) = Unit
            override fun writeItems(items: List<CatalogItem>) {
                batches += items.size
                all += items
            }
            override fun commit() = Unit
            override fun finish(summary: ImportSummary) = Unit
            override fun abort(cause: Throwable?) = Unit
        }
        val portal = Portal(listOf((1..5).map { channel("$it") }), statesTotal = true)

        val written = StalkerImportEngine(batchSize = 2).importChannels("src", portal, writer)

        assertEquals(5, written)
        assertEquals(listOf(2, 2, 1), batches)
    }

    /* ----------------------------------------------------- films, shows and stations */

    /** A portal serving one page of [items] for [serves], and nothing for anything else. */
    private class Library(
        private val serves: MediaKind,
        private val items: List<StalkerItem>,
        private val resolves: Boolean = true,
    ) : StalkerImportEngine.Api {
        var resolveCalls = 0
        override fun channels(page: Int, onChannel: (StalkerChannel) -> Unit): Int = 0
        override fun items(kind: MediaKind, page: Int, onItem: (StalkerItem) -> Unit): Int {
            if (kind != serves || page != 1) return 0
            items.forEach(onItem)
            return items.size
        }
        override fun resolve(kind: MediaKind, command: String): String? {
            resolveCalls++
            return if (resolves) "http://host/resolved/${command.trimStart('/')}" else null
        }
    }

    private fun film(id: String, name: String, cmd: String?, seconds: Int? = null) = StalkerItem(
        id = id,
        name = name,
        command = cmd,
        kind = MediaKind.MOVIE,
        posterUrl = "http://host/p/$id.jpg",
        year = "2021",
        durationSeconds = seconds,
        episodeCount = 0,
    )

    /**
     * Each of the three item sections imports on its own call.
     *
     * The claim is that a section is a *request* rather than a filter over one
     * download — which is what lets `LoadSection` fetch Films when Films is opened and
     * leave the rest alone.
     */
    @Test
    fun `films, series and radio each import under their own kind`() {
        for (kind in listOf(MediaKind.MOVIE, MediaKind.SERIES, MediaKind.RADIO)) {
            val writer = Recorder()
            val portal = Library(kind, listOf(film("1", "One", "http://h/1").copy(kind = kind)))

            val written = StalkerImportEngine().importKind("src", kind, portal, writer)

            assertEquals("$kind imported nothing", 1, written)
            assertEquals(kind, writer.items.single().kind)
        }
    }

    /**
     * A portal that does not serve a section imports nothing, and that is not a failure.
     *
     * An installation with no film library is an ordinary installation. What must not
     * happen is rows invented to fill the screen.
     */
    @Test
    fun `a portal that serves no films imports no films`() {
        val writer = Recorder()
        val portal = Library(MediaKind.SERIES, listOf(film("1", "Show", "http://h/1")))

        val written = StalkerImportEngine().importKind("src", MediaKind.MOVIE, portal, writer)

        assertEquals(0, written)
        assertTrue(writer.items.isEmpty())
        assertTrue("an empty section must still close its transaction", writer.committed)
    }

    /** A film's poster and running time reach the row, because a library screen shows them. */
    @Test
    fun `a film keeps its poster and its running time`() {
        val writer = Recorder()
        val portal = Library(MediaKind.MOVIE, listOf(film("7", "Dune", "http://h/7", seconds = 9300)))

        StalkerImportEngine().importKind("src", MediaKind.MOVIE, portal, writer)

        val row = writer.items.single()
        assertEquals("http://host/p/7.jpg", row.artworkUrl)
        assertEquals(9300, row.durationSeconds)
    }

    /* --------------------------------------------------------------- create_link */

    /**
     * A `/media/…` command is resolved through the portal, not guessed at.
     *
     * This is the row that used to be dropped. The protocol has a call for it, so the
     * call is made — and a row that resolves is a row the user can play.
     */
    @Test
    fun `a media command is resolved through the portal and kept`() {
        val writer = Recorder()
        val portal = Library(MediaKind.MOVIE, listOf(film("3", "Film", "/media/file_3.mpg")))

        val written = StalkerImportEngine().importKind("src", MediaKind.MOVIE, portal, writer)

        assertEquals(1, written)
        assertEquals("http://host/resolved/media/file_3.mpg", writer.items.single().streamUrl)
        assertEquals("the portal was asked once", 1, portal.resolveCalls)
    }

    /**
     * A command that already carries an address costs no request.
     *
     * On a list of fifty thousand channels the difference between asking and not is an
     * import and a denial of service against the user's own provider.
     */
    @Test
    fun `a command that already carries an address is not sent back to the portal`() {
        val writer = Recorder()
        val portal = Library(MediaKind.MOVIE, listOf(film("3", "Film", "ffmpeg http://h/3.ts")))

        StalkerImportEngine().importKind("src", MediaKind.MOVIE, portal, writer)

        assertEquals("http://h/3.ts", writer.items.single().streamUrl)
        assertEquals("the portal was asked for an address it had already given", 0, portal.resolveCalls)
    }

    /** And a command the portal will not resolve writes no row at all. */
    @Test
    fun `a command the portal refuses to resolve is not written`() {
        val writer = Recorder()
        val portal = Library(
            serves = MediaKind.MOVIE,
            items = listOf(film("3", "Film", "/media/file_3.mpg")),
            resolves = false,
        )

        val written = StalkerImportEngine().importKind("src", MediaKind.MOVIE, portal, writer)

        assertEquals(0, written)
        assertTrue(writer.items.isEmpty())
    }

    /** A show with no command of its own writes no row, because there is nothing to play. */
    @Test
    fun `a series row with no command is not written`() {
        val writer = Recorder()
        val show = film("9", "The Wire", null).copy(kind = MediaKind.SERIES, episodeCount = 3)
        val portal = Library(MediaKind.SERIES, listOf(show))

        assertEquals(0, StalkerImportEngine().importKind("src", MediaKind.SERIES, portal, writer))
    }

    /**
     * A film and a channel with the same provider id are two rows.
     *
     * Portals number their sections independently, so `id = 1` is a channel and a film
     * on the same installation. Folding the kind into the row id is what keeps one
     * from overwriting the other.
     */
    @Test
    fun `an id that repeats across sections produces two rows`() {
        val channels = Recorder()
        StalkerImportEngine().importChannels(
            "src",
            Portal(listOf(listOf(channel("1"))), statesTotal = true),
            channels,
        )
        val films = Recorder()
        StalkerImportEngine().importKind(
            "src",
            MediaKind.MOVIE,
            Library(MediaKind.MOVIE, listOf(film("1", "Film", "http://h/1"))),
            films,
        )

        assertNotEquals(channels.items.single().id, films.items.single().id)
    }
}
