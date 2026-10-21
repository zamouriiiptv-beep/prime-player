package com.castivio.domain

import com.castivio.core.common.AppError
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fetching one section, and the four decisions that are only visible here.
 *
 * The interesting cases are all about *not* downloading: a section already on the
 * device, a provider that cannot be turned back into a fetchable source, a file from
 * storage whose one reading settles every section, a playlist whose kept download
 * answers the sections after the first. None of them can be seen on a screen — they all
 * render as "the rows appeared" — and each of them is a download the user either pays
 * for or does not.
 */
class LoadSectionTest {

    private val now = 1_700_000_000_000L

    // ------------------------------------------------------------------ the marks

    @Test
    fun `a section already on the device is not fetched again`() = runTest {
        val importer = Importer()
        val marks = Marks().apply { store["xtream|LIVE"] = 1L }
        val load = LoadSection(Sources(xtream()), importer, marks)

        val states = load.load(MediaKind.LIVE, now).toList()

        assertEquals(listOf(SectionLoad.Ready), states)
        assertEquals(0, importer.calls.size)
    }

    @Test
    fun `a section is marked only after the import finishes`() = runTest {
        val importer = Importer(listOf(ImportProgress.Done(1_200, 500)))
        val marks = Marks()
        val load = LoadSection(Sources(xtream()), importer, marks)

        val states = load.load(MediaKind.MOVIE, now).toList()

        assertEquals(SectionLoad.Done(1_200), states.last())
        assertEquals(now, marks.loadedAt("xtream", MediaKind.MOVIE))
        assertEquals(listOf(MediaKind.MOVIE), importer.calls)
    }

    /**
     * The defect this ordering exists to prevent.
     *
     * A mark written before the import turns a failed download into a section that is
     * permanently empty *and* permanently "loaded": the user presses it, gets nothing,
     * and has no way left to ask again.
     */
    @Test
    fun `a failed import leaves the section unmarked, so it can be asked for again`() = runTest {
        val importer = Importer(listOf(ImportProgress.Failed(AppError.TIMEOUT)))
        val marks = Marks()
        val load = LoadSection(Sources(xtream()), importer, marks)

        val states = load.load(MediaKind.LIVE, now).toList()

        assertEquals(SectionLoad.Failed(AppError.TIMEOUT, retryable = true), states.last())
        assertNull(marks.loadedAt("xtream", MediaKind.LIVE))
    }

    /** A refusal is not worth a second press, and the screen is told so. */
    @Test
    fun `a rejected provider is reported as not worth retrying`() = runTest {
        val importer = Importer(listOf(ImportProgress.Failed(AppError.UNAUTHORIZED)))
        val load = LoadSection(Sources(xtream()), importer, Marks())

        val failure = load.load(MediaKind.LIVE, now).toList().last()

        assertEquals(SectionLoad.Failed(AppError.UNAUTHORIZED, retryable = false), failure)
    }

    /**
     * The figures are the importer's and the screen's, and this class is only between
     * them. Dropping them here is how a failure that said "HTTP 458, nothing read"
     * reached the viewer as "something went wrong".
     */
    @Test
    fun `a failure keeps the importer's diagnosis on its way to the screen`() = runTest {
        val diagnosis = FetchDiagnosis(FetchStage.ANSWER, httpStatus = 458, bytesReceived = null)
        val importer = Importer(listOf(ImportProgress.Failed(AppError.UNKNOWN, diagnosis)))
        val load = LoadSection(Sources(xtream()), importer, Marks())

        val failure = load.load(MediaKind.LIVE, now).toList().last()

        assertEquals(SectionLoad.Failed(AppError.UNKNOWN, retryable = true, diagnosis = diagnosis), failure)
    }

    // ------------------------------------------------------- one file, one section

    /**
     * A playlist is one file holding every kind, and it is downloaded once and kept: each
     * section is read from the copy and written on its own, so the first section opened
     * waits for its own rows rather than for the whole library's -- and marks only itself.
     * The next section reads the copy rather than downloading again.
     */
    @Test
    fun `a playlist marks only the section it read`() = runTest {
        val importer = Importer(listOf(ImportProgress.Done(40_000, 900)))
        val marks = Marks()
        val load = LoadSection(Sources(m3u()), importer, marks)

        load.load(MediaKind.LIVE, now).toList()

        assertEquals(now, marks.loadedAt("m3u", MediaKind.LIVE))
        for (kind in listOf(MediaKind.MOVIE, MediaKind.SERIES, MediaKind.RADIO)) {
            assertNull("$kind was marked by a load of the channels", marks.loadedAt("m3u", kind))
        }
        assertEquals(listOf(MediaKind.LIVE), importer.calls)
    }

    /**
     * A file picked from storage is read whole, so the first section opened has paid for
     * the other three. Marking only the one asked for would read the same file again on
     * the next section.
     */
    @Test
    fun `a file from storage marks every section, because one reading carried them all`() = runTest {
        val importer = Importer(listOf(ImportProgress.Done(40_000, 900)))
        val marks = Marks()
        val load = LoadSection(Sources(localFile()), importer, marks)

        load.load(MediaKind.LIVE, now).toList()

        for (kind in MediaKind.entries) {
            assertEquals("$kind was not marked", now, marks.loadedAt("file", kind))
        }
    }

    /** Xtream addresses films, series and live separately, so a films fetch settles films. */
    @Test
    fun `xtream marks only the section that was asked for`() = runTest {
        val importer = Importer(listOf(ImportProgress.Done(600, 12)))
        val marks = Marks()
        val load = LoadSection(Sources(xtream()), importer, marks)

        load.load(MediaKind.MOVIE, now).toList()

        assertEquals(now, marks.loadedAt("xtream", MediaKind.MOVIE))
        assertNull(marks.loadedAt("xtream", MediaKind.LIVE))
        assertNull(marks.loadedAt("xtream", MediaKind.SERIES))
        assertNull(marks.loadedAt("xtream", MediaKind.RADIO))
    }

    // ------------------------------------------------------ live and radio, each alone

    /**
     * An Xtream panel's stations are live categories, but each section is loaded on its
     * own: the channels write no station and the radio no channel, so each marks itself.
     */
    @Test
    fun `xtream channels and radio are each marked alone`() = runTest {
        for ((asked, other) in listOf(MediaKind.LIVE to MediaKind.RADIO, MediaKind.RADIO to MediaKind.LIVE)) {
            val marks = Marks()
            val load = LoadSection(Sources(xtream()), Importer(listOf(ImportProgress.Done(55_202, 48_750))), marks)

            load.load(asked, now).toList()

            assertEquals("$asked was not marked", now, marks.loadedAt("xtream", asked))
            assertNull("$other was marked by a load of $asked", marks.loadedAt("xtream", other))
            assertNull(marks.loadedAt("xtream", MediaKind.MOVIE))
            assertNull(marks.loadedAt("xtream", MediaKind.SERIES))
        }
    }

    /** Opening the radio after the channels fetches the radio, and only the radio. */
    @Test
    fun `opening radio after the channels fetches the radio alone`() = runTest {
        val importer = Importer(listOf(ImportProgress.Done(1_083, 2)))
        val marks = Marks()
        val load = LoadSection(Sources(xtream()), importer, marks)

        load.load(MediaKind.LIVE, now).toList()
        val radio = load.load(MediaKind.RADIO, now).toList()

        assertEquals(SectionLoad.Done(1_083), radio.last())
        assertEquals(listOf(MediaKind.LIVE, MediaKind.RADIO), importer.calls)
        assertEquals("and the channels are not fetched again", listOf(SectionLoad.Ready), load.load(MediaKind.LIVE, now).toList())
    }

    /** A playlist read through its panel is read section by section, the Xtream way. */
    @Test
    fun `a playlist read through its panel marks only the section it loaded`() = runTest {
        val marks = Marks()
        val load = LoadSection(Sources(panelPlaylist()), Importer(listOf(ImportProgress.Done(55_202, 48_750))), marks)

        load.load(MediaKind.LIVE, now).toList()

        assertEquals(now, marks.loadedAt("panel", MediaKind.LIVE))
        assertNull(marks.loadedAt("panel", MediaKind.RADIO))
        assertNull(marks.loadedAt("panel", MediaKind.MOVIE))
        assertNull(marks.loadedAt("panel", MediaKind.SERIES))
    }

    /** A portal answers `radio` on a call of its own, so its channels bring no stations. */
    @Test
    fun `a portal's channels do not claim its radio`() = runTest {
        val marks = Marks()
        val load = LoadSection(Sources(portal()), Importer(listOf(ImportProgress.Done(900, 30))), marks)

        load.load(MediaKind.LIVE, now).toList()

        assertEquals(now, marks.loadedAt("portal", MediaKind.LIVE))
        assertNull(marks.loadedAt("portal", MediaKind.RADIO))
    }

    // ------------------------------------------------------------------ the edges

    @Test
    fun `no provider is not an error, and fetches nothing`() = runTest {
        val importer = Importer()
        val load = LoadSection(Sources(null), importer, Marks())

        assertEquals(listOf(SectionLoad.NoSource), load.load(MediaKind.LIVE, now).toList())
        assertEquals(0, importer.calls.size)
    }

    /**
     * A portal registration has no stored address to go back to, so pressing again
     * reconstructs exactly the same nothing.
     */
    @Test
    fun `a source that cannot be rebuilt fails once and says so`() = runTest {
        val portal = ProviderSource(id = "portal", kind = SourceKind.PORTAL, label = "Portal", url = null)
        val load = LoadSection(Sources(portal), Importer(), Marks())

        val failure = load.load(MediaKind.LIVE, now).toList().last()

        assertEquals(SectionLoad.Failed(AppError.NOT_CONFIGURED, retryable = false), failure)
    }

    /** Force is what a refresh is: the same fetch, asked for on purpose. */
    @Test
    fun `force fetches a section that is already marked`() = runTest {
        val importer = Importer(listOf(ImportProgress.Done(7, 1)))
        val marks = Marks().apply { store["xtream|LIVE"] = 1L }
        val load = LoadSection(Sources(xtream()), importer, marks)

        load.load(MediaKind.LIVE, now, force = true).toList()

        assertEquals(listOf(MediaKind.LIVE), importer.calls)
        assertEquals(now, marks.loadedAt("xtream", MediaKind.LIVE))
    }

    /**
     * A refresh wants the provider's latest; a first open takes what the device already
     * holds of it. The importer is told which, because a playlist's kept copy answers the
     * one and must not answer the other.
     */
    @Test
    fun `a refresh asks the provider, and a first open may be answered from the device`() = runTest {
        val importer = Importer(listOf(ImportProgress.Done(7, 1)))
        val load = LoadSection(Sources(m3u()), importer, Marks())

        load.load(MediaKind.MOVIE, now).toList()
        load.load(MediaKind.MOVIE, now, force = true).toList()

        assertEquals(listOf(false, true), importer.fresh)
    }

    /** Progress rises and never falls, whatever order the engine reports kinds in. */
    @Test
    fun `progress never goes backwards`() = runTest {
        val importer = Importer(
            listOf(
                ImportProgress.Importing(1_000, 4, MediaKind.MOVIE),
                ImportProgress.Importing(300, 9, MediaKind.SERIES),
                ImportProgress.Done(1_300, 40),
            ),
        )
        val load = LoadSection(Sources(m3u()), importer, Marks())

        val seen = load.load(MediaKind.MOVIE, now).toList()
            .filterIsInstance<SectionLoad.Loading>()
            .map { it.items }

        assertEquals(seen.sorted(), seen)
        assertTrue(seen.contains(1_000))
    }

    /**
     * The categories listed reach the screen as the importer says them, and never fall: a
     * board that opened with its first category is never told it has none.
     */
    @Test
    fun `the categories listed reach the screen and never go back`() = runTest {
        val importer = Importer(
            listOf(
                ImportProgress.Importing(40, 3, MediaKind.LIVE),
                ImportProgress.Importing(80, 3, MediaKind.LIVE, categoriesListed = 1),
                ImportProgress.Importing(90, 3, MediaKind.LIVE, categoriesListed = 0),
                ImportProgress.Importing(120, 3, MediaKind.LIVE, categoriesListed = 2),
                ImportProgress.Done(120, 40),
            ),
        )
        val load = LoadSection(Sources(xtream()), importer, Marks())

        val listed = load.load(MediaKind.LIVE, now).toList()
            .filterIsInstance<SectionLoad.Loading>()
            .map { it.listed }

        assertEquals(listOf(0, 1, 1, 2), listed)
    }

    // -------------------------------------------------------------------- fixtures

    private fun xtream() = ProviderSource(
        id = "xtream",
        kind = SourceKind.XTREAM,
        label = "Provider",
        url = "http://panel.example.com",
        username = "user",
        password = "secret",
    )

    private fun m3u() = ProviderSource(
        id = "m3u",
        kind = SourceKind.M3U_URL,
        label = "Playlist",
        url = "http://example.com/list.m3u",
    )

    private fun localFile() = ProviderSource(
        id = "file",
        kind = SourceKind.LOCAL_FILE,
        label = "Playlist file",
        url = "content://downloads/list.m3u",
    )

    /** A `get.php` link that names a panel account, so it is read through the panel. */
    private fun panelPlaylist() = ProviderSource(
        id = "panel",
        kind = SourceKind.M3U_URL,
        label = "Playlist 1",
        url = "http://line.example.com:8080/get.php?username=sam&password=hunter2&type=m3u_plus&output=ts",
    )

    private fun portal() = ProviderSource(
        id = "portal",
        kind = SourceKind.PORTAL,
        label = "Portal",
        url = "http://portal.example.com/c/",
    )

    private class Sources(private val active: ProviderSource?) : SourceRepository {
        override suspend fun activeNow(): ProviderSource? = active
        override suspend fun register(source: PlaylistSource, label: String?): ProviderSource =
            throw UnsupportedOperationException()
        override fun sources(): Flow<List<ProviderSource>> = throw UnsupportedOperationException()
        override fun active(): Flow<ProviderSource?> = flowOf(active)
        override suspend fun get(id: String): ProviderSource? = active?.takeIf { it.id == id }
        override suspend fun save(source: ProviderSource) = Unit
        override suspend fun setActive(id: String) = Unit
        override suspend fun recordCatalogueImport(id: String, sync: SyncState) = Unit
        override suspend fun recordEpgImport(id: String, atMs: Long) = Unit
        override suspend fun delete(id: String) = Unit
    }

    private class Importer(private val script: List<ImportProgress> = emptyList()) : CatalogImporter {
        val calls = mutableListOf<MediaKind>()

        /** Whether each section import asked for the provider's latest, in order. */
        val fresh = mutableListOf<Boolean>()

        override fun import(source: PlaylistSource): Flow<ImportProgress> = flow {
            for (step in script) emit(step)
        }

        override fun importKind(source: PlaylistSource, kind: MediaKind, fresh: Boolean): Flow<ImportProgress> {
            calls += kind
            this.fresh += fresh
            return import(source)
        }

        override suspend fun isUpToDate(source: PlaylistSource): Boolean = false
    }

    private class Marks : SectionCatalogue {
        val store = mutableMapOf<String, Long>()

        override suspend fun loadedAt(sourceId: String, kind: MediaKind): Long? =
            store["$sourceId|${kind.name}"]

        override fun loaded(sourceId: String): Flow<Map<MediaKind, Long>> = flowOf(
            MediaKind.entries.mapNotNull { kind ->
                store["$sourceId|${kind.name}"]?.let { kind to it }
            }.toMap(),
        )

        override suspend fun markLoaded(sourceId: String, kind: MediaKind, atMs: Long) {
            store["$sourceId|${kind.name}"] = atMs
        }

        override suspend fun forget(sourceId: String) {
            store.keys.removeAll { it.startsWith("$sourceId|") }
        }
    }
}
