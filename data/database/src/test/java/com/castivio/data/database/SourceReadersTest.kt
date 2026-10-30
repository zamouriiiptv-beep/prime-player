package com.castivio.data.database

import com.castivio.data.database.dao.SourceDao
import com.castivio.data.database.entity.SourceEntity
import com.castivio.domain.PlaylistSource
import com.castivio.domain.ProviderSource
import com.castivio.domain.SourceKind
import com.castivio.domain.SyncState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The subscriptions are read again after every write, whether or not Room says the table changed.
 *
 * On a device the screens did not move: a subscription added from Home was saved and active, and
 * Home still said there was none seconds later; a deleted one stayed in the list. So the table here
 * is read the way a Room that never sends its notice would read it -- once for each reader, and never
 * again -- and every test is a write that a reader already reading has to see. Room itself is
 * `SourceAndMigrationTest`'s, which runs on real SQLite.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SourceReadersTest {

    private val dao = SilentSourceDao()

    /** How many times the catalogue was told another subscription is active -- see `RoomCatalogRepository.activeChanged`. */
    private var catalogueTold = 0
    private val repository = RoomSourceRepository(dao, onActiveChanged = { catalogueTold++ }) { 1_000L }

    @Test
    fun `a subscription added is read by a screen already reading`() = runTest {
        val active = mutableListOf<ProviderSource?>()
        val all = mutableListOf<List<ProviderSource>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { repository.active().collect { active += it } }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { repository.sources().collect { all += it } }

        val added = repository.register(PlaylistSource.M3u("http://host/list.m3u"))

        assertEquals("Home says which subscription it is showing", added.id, active.last()?.id)
        assertEquals(listOf(added.id), all.last().map { it.id })
    }

    @Test
    fun `a subscription deleted leaves the list a screen is reading`() = runTest {
        repository.save(source("a", createdAt = 1))
        repository.save(source("b", createdAt = 2))
        val all = mutableListOf<List<ProviderSource>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { repository.sources().collect { all += it } }

        repository.delete("a")

        assertEquals(listOf("b"), all.last().map { it.id })
    }

    @Test
    fun `the active one deleted leaves nothing active for a screen that is reading`() = runTest {
        repository.save(source("a", createdAt = 1, active = true))
        val active = mutableListOf<ProviderSource?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { repository.active().collect { active += it } }

        repository.delete("a")

        assertNull(active.last())
    }

    @Test
    fun `another one made active is read by a screen already reading`() = runTest {
        repository.save(source("a", createdAt = 1, active = true))
        repository.save(source("b", createdAt = 2))
        val active = mutableListOf<ProviderSource?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { repository.active().collect { active += it } }

        repository.setActive("b")

        assertEquals("b", active.last()?.id)
    }

    @Test
    fun `a renamed one is read under its new name`() = runTest {
        repository.save(source("a", createdAt = 1))
        val all = mutableListOf<List<ProviderSource>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { repository.sources().collect { all += it } }

        repository.save(repository.get("a")!!.copy(label = "Sport"))

        assertEquals("Sport", all.last().single().label)
    }

    /** Every write is read again, and a reading that found what the last one did is not news. */
    @Test
    fun `a write that changes nothing a screen shows is not told as a change`() = runTest {
        repository.save(source("a", createdAt = 1, active = true))
        val active = mutableListOf<ProviderSource?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { repository.active().collect { active += it } }

        repository.setActive("a")
        repository.setActive("a")

        assertEquals(1, active.size)
        assertEquals("but each write was read again", 3, dao.activeReads)
    }

    @Test
    fun `what an import records is read by a screen already reading`() = runTest {
        repository.save(source("a", createdAt = 1, active = true))
        val active = mutableListOf<ProviderSource?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { repository.active().collect { active += it } }

        repository.recordCatalogueImport("a", SyncState(itemCount = 18_823))

        assertEquals(18_823, active.last()?.sync?.itemCount)
    }

    // ------------------------------------------------------------------ the catalogue is told

    /** Every count, rail and list reads the active subscription through `source`: a switch is theirs to hear of. */
    @Test
    fun `the catalogue is told when another subscription is made active`() = runTest {
        repository.save(source("a", createdAt = 1, active = true))
        repository.save(source("b", createdAt = 2))
        catalogueTold = 0

        repository.setActive("b")

        assertEquals(1, catalogueTold)
    }

    @Test
    fun `the catalogue is told when a subscription is added, which makes it the active one`() = runTest {
        repository.register(PlaylistSource.M3u("http://host/list.m3u"))

        assertEquals(1, catalogueTold)
    }

    @Test
    fun `the catalogue is told when the active one is deleted, and not when another is`() = runTest {
        repository.save(source("a", createdAt = 1, active = true))
        repository.save(source("b", createdAt = 2))
        catalogueTold = 0

        repository.delete("b")
        assertEquals("nothing the catalogue reads has changed", 0, catalogueTold)

        repository.delete("a")
        assertEquals(1, catalogueTold)
    }

    /** Telling it reads every list again: a write that leaves the same subscription active tells nothing. */
    @Test
    fun `the catalogue is not told of a write that leaves the same subscription active`() = runTest {
        repository.save(source("a", createdAt = 1, active = true))
        catalogueTold = 0

        repository.setActive("a")
        repository.save(repository.get("a")!!.copy(label = "Sport"))
        repository.recordCatalogueImport("a", SyncState(itemCount = 18_823))
        repository.recordEpgImport("a", atMs = 2_000L)

        assertEquals(0, catalogueTold)
    }

    private fun source(id: String, createdAt: Long, active: Boolean = false) = ProviderSource(
        id = id,
        kind = SourceKind.M3U_URL,
        label = id,
        url = "http://host/$id.m3u",
        createdAtMs = createdAt,
        isActive = active,
    )

    /** `source`, kept in memory and read as a Room that never sends a notice reads it: once per reader. */
    private class SilentSourceDao : SourceDao {
        private val rows = linkedMapOf<String, SourceEntity>()
        var activeReads = 0
            private set

        private fun activeRow() = rows.values.filter { it.isActive }.minByOrNull { it.createdAt }

        override fun all(): Flow<List<SourceEntity>> = flow { emit(rows.values.sortedBy { it.createdAt }) }

        override fun active(): Flow<SourceEntity?> = flow {
            activeReads++
            emit(activeRow())
        }

        override suspend fun labels(): List<String> = rows.values.map { it.label }
        override suspend fun activeNow(): SourceEntity? = activeRow()
        override suspend fun byId(id: String): SourceEntity? = rows[id]

        override suspend fun upsert(source: SourceEntity) {
            rows[source.id] = source
        }

        override suspend fun clearActive() {
            rows.replaceAll { _, row -> row.copy(isActive = false) }
        }

        override suspend fun setActive(id: String) {
            rows[id]?.let { rows[id] = it.copy(isActive = true) }
        }

        override suspend fun recordImport(
            id: String,
            etag: String?,
            lastModified: String?,
            contentHash: String?,
            importedAt: Long,
            itemCount: Int,
        ) {
            rows[id]?.let {
                rows[id] = it.copy(
                    etag = etag,
                    lastModified = lastModified,
                    contentHash = contentHash,
                    lastImportAt = importedAt,
                    itemCount = itemCount,
                )
            }
        }

        override suspend fun recordEpgImport(id: String, importedAt: Long) {
            rows[id]?.let { rows[id] = it.copy(lastEpgImportAt = importedAt) }
        }

        override suspend fun delete(id: String) {
            rows.remove(id)
        }

        // No catalogue here: what deleting removes from it is `SourceAndMigrationTest`'s.
        override suspend fun orphaned(): List<String> = emptyList()
        override suspend fun mediaRowsOf(id: String, limit: Int): List<Long> = emptyList()
        override suspend fun deleteSearchRows(rows: List<Long>) = Unit
        override suspend fun deleteMediaRows(rows: List<Long>) = Unit
        override suspend fun deleteProgrammesOf(id: String, limit: Int): Int = 0
        override suspend fun deleteGroupsOf(id: String, limit: Int): Int = 0
    }
}
