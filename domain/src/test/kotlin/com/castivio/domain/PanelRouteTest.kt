package com.castivio.domain

import com.castivio.core.common.AppError
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which way a playlist subscription is read, decided once for every reader of it.
 *
 * A `get.php?username=…&password=…` link names a panel account, and such a playlist is
 * read through the panel's API — one section at a time — while staying, everywhere it is
 * shown and stored, the playlist the user added. The exceptions are what this file is
 * about: a link that names nothing, a subscription whose catalogue already came from the
 * file, and a reading that fell back to the file halfway through.
 */
class PanelRouteTest {

    private val now = 1_700_000_000_000L

    // ------------------------------------------------------------- the decision

    @Test
    fun `a playlist link that names a panel account is read through the panel`() {
        val source = playlist(PANEL_LINK).asPlaylistSource() as PlaylistSource.M3u

        assertEquals(PANEL_LINK, source.url)
        assertEquals(PlaylistSource.Xtream("http://line.example.com:8080", "sam", "hunter2"), source.xtream)
        assertFalse("read section by section", source.carriesEveryKind)
    }

    @Test
    fun `a playlist link that names no account is read as a playlist`() {
        val source = playlist("http://cdn.example.com/lists/family.m3u").asPlaylistSource() as PlaylistSource.M3u

        assertNull(source.xtream)
        assertTrue(source.carriesEveryKind)
    }

    /**
     * A playlist read to its end left rows keyed on stream URLs; the API keys the same
     * channels on stream ids. Switching would show every channel twice, and favourites
     * and history would stay with the old rows — so a finished playlist stays one.
     */
    @Test
    fun `a subscription whose catalogue came from the playlist keeps being read as one`() {
        val legacy = playlist(PANEL_LINK, SyncState(contentHash = "crc32:1a2b3c4d", lastImportAtMs = now, itemCount = 7_622))

        val source = legacy.asPlaylistSource() as PlaylistSource.M3u

        assertNull(source.xtream)
        assertTrue(source.carriesEveryKind)
    }

    /** An import that never finished recorded no catalogue, so it does not hold the route. */
    @Test
    fun `a subscription whose playlist never finished is read through the panel`() {
        val unfinished = playlist(PANEL_LINK, SyncState(lastImportAtMs = null, itemCount = 0))

        assertNotNull((unfinished.asPlaylistSource() as PlaylistSource.M3u).xtream)
    }

    /** The reading changes; the identity does not. */
    @Test
    fun `reading through the panel keeps the playlist's own row`() {
        val row = playlist(PANEL_LINK)

        val source = row.asPlaylistSource() as PlaylistSource.M3u

        assertEquals(SourceKind.M3U_URL, row.kind)
        assertEquals(PlaylistSource.M3u(PANEL_LINK), source.copy(xtream = null))
    }

    // ------------------------------------------------------------ the sections

    @Test
    fun `a playlist read through its panel marks only the section it fetched`() = runTest {
        val row = playlist(PANEL_LINK)
        val importer = Importer(listOf(ImportProgress.Done(1_600, 900)))
        val marks = Marks()

        LoadSection(Sources(row), importer, marks).load(MediaKind.LIVE, now).toList()

        assertNotNull("the loader was handed the panel account", (importer.asked.single() as PlaylistSource.M3u).xtream)
        assertEquals(now, marks.loadedAt(row.id, MediaKind.LIVE))
        assertNull("films are their own request", marks.loadedAt(row.id, MediaKind.MOVIE))
        assertNull(marks.loadedAt(row.id, MediaKind.SERIES))
    }

    /**
     * The panel said no and the playlist was read instead: every section arrived in that
     * one download, so every section is marked, exactly as for any playlist.
     */
    @Test
    fun `a playlist read in its panel's place marks every section`() = runTest {
        val sources = Sources(playlist(PANEL_LINK))
        val importer = Importer(listOf(ImportProgress.Done(7_622, 4_000))) {
            // What the importer's fallback leaves behind: a playlist catalogue, recorded.
            sources.row = sources.row.copy(sync = SyncState(contentHash = "crc32:1a2b3c4d", lastImportAtMs = now))
        }
        val marks = Marks()

        LoadSection(sources, importer, marks).load(MediaKind.LIVE, now).toList()

        for (kind in MediaKind.entries) {
            assertEquals(kind.name, now, marks.loadedAt(sources.row.id, kind))
        }
    }

    // ---------------------------------------------------------- the fallback rule

    @Test
    fun `the playlist is tried after a refusal, a missing endpoint or an unreadable answer`() {
        for (error in listOf(AppError.UNAUTHORIZED, AppError.NOT_FOUND, AppError.MALFORMED_PLAYLIST, AppError.SERVER_ERROR, AppError.UNKNOWN)) {
            assertTrue(error.name, panelFailureAllowsPlaylist(error, FetchStage.ANSWER))
        }
    }

    /** Same server: no connection or no answer in time would be the playlist's answer too. */
    @Test
    fun `the playlist is not tried when the network is the reason`() {
        assertFalse(panelFailureAllowsPlaylist(AppError.NETWORK_UNAVAILABLE, FetchStage.CONNECT))
        assertFalse(panelFailureAllowsPlaylist(AppError.TIMEOUT, FetchStage.CONNECT))
        assertFalse(panelFailureAllowsPlaylist(AppError.TIMEOUT))
    }

    /** Same database: the playlist's rows would be refused the same way. */
    @Test
    fun `the playlist is not tried when the database is the reason`() {
        assertFalse(panelFailureAllowsPlaylist(AppError.UNKNOWN, FetchStage.SAVE))
    }

    // ------------------------------------------------------------------ fixtures

    private fun playlist(link: String, sync: SyncState = SyncState()) = ProviderSource(
        id = "playlist-1",
        kind = SourceKind.M3U_URL,
        label = "Playlist 1",
        url = link,
        sync = sync,
    )

    private class Importer(
        private val events: List<ImportProgress>,
        private val during: () -> Unit = {},
    ) : CatalogImporter {
        val asked = mutableListOf<PlaylistSource>()

        override fun import(source: PlaylistSource): Flow<ImportProgress> = flowOf()

        override fun importKind(source: PlaylistSource, kind: MediaKind): Flow<ImportProgress> = flow {
            asked += source
            during()
            events.forEach { emit(it) }
        }

        override suspend fun isUpToDate(source: PlaylistSource): Boolean = false
    }

    private class Sources(var row: ProviderSource) : SourceRepository {
        override suspend fun register(source: PlaylistSource, label: String?): ProviderSource = row
        override fun sources(): Flow<List<ProviderSource>> = flowOf(listOf(row))
        override fun active(): Flow<ProviderSource?> = flowOf(row)
        override suspend fun activeNow(): ProviderSource = row
        override suspend fun get(id: String): ProviderSource? = row.takeIf { it.id == id }
        override suspend fun save(source: ProviderSource) = Unit
        override suspend fun setActive(id: String) = Unit
        override suspend fun recordCatalogueImport(id: String, sync: SyncState) = Unit
        override suspend fun recordEpgImport(id: String, atMs: Long) = Unit
        override suspend fun delete(id: String) = Unit
    }

    private class Marks : SectionCatalogue {
        private val store = mutableMapOf<String, Long>()

        override suspend fun loadedAt(sourceId: String, kind: MediaKind): Long? = store["$sourceId|${kind.name}"]

        override fun loaded(sourceId: String): Flow<Map<MediaKind, Long>> = flowOf(
            MediaKind.entries.mapNotNull { kind -> store["$sourceId|${kind.name}"]?.let { kind to it } }.toMap(),
        )

        override suspend fun markLoaded(sourceId: String, kind: MediaKind, atMs: Long) {
            store["$sourceId|${kind.name}"] = atMs
        }

        override suspend fun forget(sourceId: String) {
            store.keys.removeAll { it.startsWith("$sourceId|") }
        }
    }

    private companion object {
        const val PANEL_LINK = "http://line.example.com:8080/get.php?username=sam&password=hunter2&type=m3u_plus&output=ts"
    }
}
