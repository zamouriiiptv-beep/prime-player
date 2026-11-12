package com.castivio.data.database

import com.castivio.domain.CatalogItem
import com.castivio.domain.ImportMode
import com.castivio.domain.ImportSummary
import com.castivio.domain.MediaGroup
import com.castivio.domain.MediaKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The page cache over a load, against real SQLite: raised before the first row, kept through the commits, and put back
 * by whichever way the load ends -- see [ImportPageCache].
 *
 * The database is in memory, which has the one connection, so the cache a read reports is the one the writer wrote
 * with. On a device a read may be answered by another connection, which is why nothing in the application reads it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportPageCacheLoadTest {

    private lateinit var database: CastivioDatabase

    private val raised = -ImportPageCache.IMPORT_KIB.toLong()
    private val normal = -ImportPageCache.NORMAL_KIB.toLong()

    @Before
    fun setUp() {
        database = CastivioDatabase.inMemory(RuntimeEnvironment.getApplication())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `a load runs with the larger cache and ends with the default`() {
        Fixtures.register(database, "src")
        val writer = writer()

        writer.begin("src", ImportMode.APPEND)
        assertEquals("raised before the first row", raised, cacheSize())
        writer.writeGroups(listOf(MediaGroup("g", "G", MediaKind.LIVE)))
        writer.writeItems(items(0, 3))
        writer.commit()
        assertEquals("and through its commits", raised, cacheSize())
        writer.writeItems(items(3, 5))
        writer.commit()
        assertEquals(raised, cacheSize())

        writer.finish(summaryOf(5))

        assertEquals("put back when the load ends", normal, cacheSize())
        assertEquals("what is written does not depend on the cache", 5, rowsOf("src"))
    }

    @Test
    fun `an abandoned load puts the default back`() {
        Fixtures.register(database, "src")
        val writer = writer()
        writer.begin("src", ImportMode.APPEND)
        writer.writeItems(items(0, 2))
        writer.commit()
        assertEquals(raised, cacheSize())

        writer.abort(java.io.IOException("connection reset"))

        assertEquals(normal, cacheSize())
        assertEquals("what was committed stays", 2, rowsOf("src"))
    }

    @Test
    fun `a load that ends before its first row puts the default back`() {
        Fixtures.register(database, "src")
        val writer = writer()
        writer.begin("src", ImportMode.APPEND)

        writer.abort(null)

        assertEquals(normal, cacheSize())
        assertEquals(0, rowsOf("src"))
    }

    @Test
    fun `a raise that no load ended is put back by the end of the next`() {
        Fixtures.register(database, "src")
        // A load whose end never came: the process went on, and its writer was dropped.
        writer().begin("src", ImportMode.APPEND)
        assertEquals(raised, cacheSize())

        val next = writer()
        next.begin("src", ImportMode.APPEND)
        next.writeItems(items(0, 1))
        next.finish(summaryOf(1))

        assertEquals(normal, cacheSize())
    }

    // ------------------------------------------------------------------ helpers

    private fun writer() = RoomCatalogWriter(database) { 2_000L }

    private fun items(from: Int, to: Int) = (from until to).map {
        CatalogItem(
            id = "row-$it",
            sourceId = "src",
            kind = MediaKind.LIVE,
            title = "Row $it",
            streamUrl = "http://host/a/row-$it.ts",
            providerOrder = it,
        )
    }

    private fun summaryOf(count: Int) =
        ImportSummary(sourceId = "src", items = count, groups = 0, skipped = 0, byKind = mapOf(MediaKind.LIVE to count), durationMs = 1)

    /** The cache size this connection reports: negative KiB when it was set in KiB. */
    private fun cacheSize(): Long =
        database.openHelper.writableDatabase.query("PRAGMA cache_size").use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else Long.MIN_VALUE
        }

    private fun rowsOf(sourceId: String): Int =
        database.openHelper.writableDatabase
            .query("SELECT COUNT(*) FROM media WHERE source_id = ?", arrayOf<Any>(sourceId))
            .use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else -1 }
}
