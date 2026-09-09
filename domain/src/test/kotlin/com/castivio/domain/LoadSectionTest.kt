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
 * device, a provider that cannot be turned back into a fetchable source, a single-file
 * provider whose one download settles every section. None of them can be seen on a
 * screen — they all render as "the rows appeared" — and each of them is a download the
 * user either pays for or does not.
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

    // --------------------------------------------------------- one file, every kind

    /**
     * An M3U is one file holding channels, films and episodes together, so the first
     * section opened has already paid for the other three. Marking only the one that
     * was asked for would download the same file again on the next section — three
     * times the bytes for a catalogue that arrived on the first press.
     */
    @Test
    fun `a playlist marks every section, because one file carried them all`() = runTest {
        val importer = Importer(listOf(ImportProgress.Done(40_000, 900)))
        val marks = Marks()
        val load = LoadSection(Sources(m3u()), importer, marks)

        load.load(MediaKind.LIVE, now).toList()

        for (kind in MediaKind.entries) {
            assertEquals("$kind was not marked", now, marks.loadedAt("m3u", kind))
        }
    }

    /** Xtream addresses each kind separately, so a fetch settles exactly one. */
    @Test
    fun `xtream marks only the section that was asked for`() = runTest {
        val importer = Importer(listOf(ImportProgress.Done(600, 12)))
        val marks = Marks()
        val load = LoadSection(Sources(xtream()), importer, marks)

        load.load(MediaKind.MOVIE, now).toList()

        assertEquals(now, marks.loadedAt("xtream", MediaKind.MOVIE))
        assertNull(marks.loadedAt("xtream", MediaKind.LIVE))
        assertNull(marks.loadedAt("xtream", MediaKind.SERIES))
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

        override fun import(source: PlaylistSource): Flow<ImportProgress> = flow {
            for (step in script) emit(step)
        }

        override fun importKind(source: PlaylistSource, kind: MediaKind): Flow<ImportProgress> {
            calls += kind
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
