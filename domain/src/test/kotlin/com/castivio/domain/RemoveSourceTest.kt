package com.castivio.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Removing a subscription removes what is kept about it in all three places, and
 * nothing kept about any other.
 */
class RemoveSourceTest {

    private val events = mutableListOf<String>()
    private val sources = Sources(events)
    private val marks = Marks(events)
    private val statuses = Statuses(events)
    private val remove = RemoveSource(sources, marks, statuses)

    @Test
    fun `a removed subscription leaves no marks, no status and no row behind`() = runTest {
        sources.save(provider("m3u"))
        marks.markLoaded("m3u", MediaKind.LIVE, 1_000)
        statuses.record("m3u", ProviderStatus(usable = true), 1_000)

        remove("m3u")

        assertNull(sources.get("m3u"))
        assertNull("a mark left behind would say the deleted rows are still here", marks.loadedAt("m3u", MediaKind.LIVE))
        assertNull(statuses.recorded["m3u"])
    }

    @Test
    fun `another subscription keeps everything`() = runTest {
        sources.save(provider("m3u"))
        sources.save(provider("xtream"))
        marks.markLoaded("xtream", MediaKind.LIVE, 1_000)
        marks.markLoaded("xtream", MediaKind.MOVIE, 2_000)
        statuses.record("xtream", ProviderStatus(usable = true), 1_000)

        remove("m3u")

        assertNotNull(sources.get("xtream"))
        assertEquals(1_000L, marks.loadedAt("xtream", MediaKind.LIVE))
        assertEquals(2_000L, marks.loadedAt("xtream", MediaKind.MOVIE))
        assertNotNull(statuses.recorded["xtream"])
    }

    @Test
    fun `the marks are forgotten before the rows are deleted`() = runTest {
        sources.save(provider("m3u"))

        remove("m3u")

        // Interrupted between the two, this order costs a re-download; the other would
        // leave marks claiming rows that are gone.
        assertEquals(listOf("forget marks m3u", "forget status m3u", "delete m3u"), events)
    }

    private fun provider(id: String) = ProviderSource(id = id, kind = SourceKind.M3U_URL, label = id, url = "http://host/$id")

    private class Sources(private val events: MutableList<String>) : SourceRepository {
        private val rows = MutableStateFlow<Map<String, ProviderSource>>(emptyMap())

        override suspend fun register(source: PlaylistSource, label: String?): ProviderSource = error("not used")
        override fun sources(): Flow<List<ProviderSource>> = rows.map { it.values.toList() }
        override fun active(): Flow<ProviderSource?> = rows.map { it.values.firstOrNull { row -> row.isActive } }
        override suspend fun activeNow(): ProviderSource? = rows.value.values.firstOrNull { it.isActive }
        override suspend fun get(id: String): ProviderSource? = rows.value[id]
        override suspend fun save(source: ProviderSource) {
            rows.value = rows.value + (source.id to source)
        }
        override suspend fun setActive(id: String) = Unit
        override suspend fun recordCatalogueImport(id: String, sync: SyncState) = Unit
        override suspend fun recordEpgImport(id: String, atMs: Long) = Unit
        override suspend fun delete(id: String) {
            events += "delete $id"
            rows.value = rows.value - id
        }
    }

    private class Marks(private val events: MutableList<String>) : SectionCatalogue {
        private val at = mutableMapOf<Pair<String, MediaKind>, Long>()

        override suspend fun loadedAt(sourceId: String, kind: MediaKind): Long? = at[sourceId to kind]
        override fun loaded(sourceId: String): Flow<Map<MediaKind, Long>> = MutableStateFlow(emptyMap())
        override suspend fun markLoaded(sourceId: String, kind: MediaKind, atMs: Long) {
            at[sourceId to kind] = atMs
        }
        override suspend fun forget(sourceId: String) {
            events += "forget marks $sourceId"
            at.keys.removeAll { it.first == sourceId }
        }
    }

    private class Statuses(private val events: MutableList<String>) : ProviderStatusCatalogue {
        val recorded = mutableMapOf<String, ProviderStatus>()

        override suspend fun record(sourceId: String, status: ProviderStatus, atMs: Long) {
            recorded[sourceId] = status
        }
        override fun of(sourceId: String): Flow<Recorded?> = MutableStateFlow(null)
        override suspend fun forget(sourceId: String) {
            events += "forget status $sourceId"
            recorded -= sourceId
        }
    }
}
