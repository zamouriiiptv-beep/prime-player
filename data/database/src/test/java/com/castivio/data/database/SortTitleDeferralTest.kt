package com.castivio.data.database

import com.castivio.domain.CatalogItem
import com.castivio.domain.ImportMode
import com.castivio.domain.ImportSummary
import com.castivio.domain.MediaGroup
import com.castivio.domain.MediaKind
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The sort-title index and a subscription's first load: taken out before its first row, built over the
 * finished rows, and never left out -- see [SortTitleIndex].
 *
 * Against real SQLite, because what is held here is what the database does with the statements, and a
 * test of a mock of it would pass while a first load left a list unsorted for ever.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SortTitleDeferralTest {

    private lateinit var database: CastivioDatabase

    @Before
    fun setUp() {
        database = CastivioDatabase.inMemory(RuntimeEnvironment.getApplication())
    }

    @After
    fun tearDown() {
        database.close()
    }

    // ------------------------------------------------------------------ the first load

    @Test
    fun `a first load runs without the index and ends with it`() = runBlocking {
        Fixtures.register(database, "src")
        assertTrue("Room made it", intact())

        val writer = writer()
        writer.begin("src", ImportMode.APPEND)
        assertTrue("begin leaves it: a load that fails at its categories never touches the schema", intact())
        writer.writeGroups(listOf(MediaGroup("g", "G", MediaKind.LIVE)))
        writer.writeItems(items(0, 3))
        assertFalse("out before the first row is written", present())
        writer.commit()
        writer.writeItems(items(3, 6))
        writer.commit()
        assertFalse("and out for the rest of the load", present())

        writer.finish(summaryOf(6))

        assertTrue("put back, as the entity declares it", intact())
        assertEquals(6, rowsOf("src"))
    }

    @Test
    fun `what a load needs while it runs does not depend on the index it left out`() = runBlocking {
        Fixtures.register(database, "src")
        val writer = writer()
        writer.begin("src", ImportMode.APPEND)
        writer.writeGroups(listOf(MediaGroup("first", "First", MediaKind.LIVE), MediaGroup("second", "Second", MediaKind.LIVE)))
        writer.writeItems((0 until 5).map { item("first-$it", "First $it", group = "first", order = it) })
        writer.writeItems((0 until 2).map { item("second-$it", "Second $it", group = "second", order = 10 + it) })
        writer.countGroups(listOf("first"))
        writer.commit()

        // Mid-load, the index out: the count of the section, the first category's count and page, the
        // provider-ordered list, and a list sorted by name -- slower for want of the index, never wrong.
        assertFalse(present())
        val media = database.mediaDao()
        assertEquals(7, media.countNow("LIVE"))
        assertEquals(5, media.countNowInGroup("LIVE", "first"))
        assertEquals((0 until 5).map { "first-$it" }, media.windowInGroup("LIVE", "first", 0, 60).map { it.id })
        assertEquals(7, media.window("LIVE", 0, 60).size)
        assertEquals(
            listOf("First 0", "First 1", "First 2", "First 3", "First 4"),
            media.pageByNameAscInGroup("LIVE", "first").firstPage().map { it.title },
        )
        assertEquals(5, countOf("SELECT item_count FROM media_group WHERE id = 'first'"))

        writer.finish(summaryOf(7))

        assertEquals("the second is counted at the end, as it always was", 2, countOf("SELECT item_count FROM media_group WHERE id = 'second'"))
        assertEquals(
            "sorted by name through the index once it is back",
            listOf("First 0", "First 1", "First 2", "First 3", "First 4", "Second 0", "Second 1"),
            media.pageByNameAsc("LIVE").firstPage().map { it.title },
        )
    }

    @Test
    fun `search is whole when the load ends, and finds what a load without the index wrote`() = runBlocking {
        Fixtures.register(database, "src")
        val writer = writer()
        writer.begin("src", ImportMode.APPEND)
        writer.writeItems(listOf(item("a", "Alpha Sports"), item("b", "Beta News")))
        writer.finish(summaryOf(2))

        assertEquals(listOf("a"), repository().search("Alpha", limit = 10).map { it.id })
        assertEquals(2, countOf("SELECT COUNT(*) FROM media_fts"))
    }

    @Test
    fun `the index is sound after the load, not only present`() = runBlocking {
        Fixtures.register(database, "src")
        val writer = writer()
        writer.begin("src", ImportMode.APPEND)
        writer.writeGroups(listOf(MediaGroup("g", "G", MediaKind.LIVE)))
        repeat(4) { batch -> writer.writeItems(items(batch * 50, batch * 50 + 50)); writer.commit() }
        writer.finish(summaryOf(200))

        assertEquals("ok", text("PRAGMA integrity_check"))
        assertEquals(
            "every row has its entry in the index",
            200,
            countOf("SELECT COUNT(*) FROM media INDEXED BY ${SortTitleIndex.NAME} WHERE source_id = 'src' AND kind = 'LIVE'"),
        )
    }

    // ------------------------------------------------------------------ never while rows are here

    @Test
    fun `a refresh over rows that are here keeps the index in place while it runs`() = runBlocking {
        Fixtures.import(database, Fixtures.livePlaylist(5), sourceId = "src")
        assertTrue(intact())

        val writer = writer()
        writer.begin("src", ImportMode.REPLACE, setOf(MediaKind.LIVE))
        writer.writeGroups(listOf(MediaGroup("g", "G", MediaKind.LIVE)))
        writer.writeItems(items(0, 4))
        writer.commit()
        assertTrue("a list sorted by name may be on screen", intact())
        writer.finish(summaryOf(4))

        assertTrue(intact())
    }

    @Test
    fun `a second section of a subscription whose first is here leaves it in place`() = runBlocking {
        Fixtures.import(database, Fixtures.livePlaylist(5), sourceId = "src")

        val writer = writer()
        writer.begin("src", ImportMode.APPEND, setOf(MediaKind.MOVIE))
        writer.writeItems(listOf(item("film", "A Film").copy(kind = MediaKind.MOVIE)))
        assertTrue(intact())
        writer.finish(summaryOf(1))

        assertTrue(intact())
    }

    @Test
    fun `another subscription's first load defers although a different one has rows`() = runBlocking {
        Fixtures.import(database, Fixtures.livePlaylist(5), sourceId = "other")
        Fixtures.register(database, "src")

        val writer = writer()
        writer.begin("src", ImportMode.APPEND)
        writer.writeItems(items(0, 2))
        assertFalse(present())
        writer.finish(summaryOf(2))

        assertTrue(intact())
        assertEquals(5, rowsOf("other"))
        assertEquals(2, rowsOf("src"))
    }

    @Test
    fun `a writer that is told not to defer leaves the index where it is`() = runBlocking {
        Fixtures.register(database, "src")
        val writer = RoomCatalogWriter(database, defersSortTitleIndex = false) { 2_000L }
        writer.begin("src", ImportMode.APPEND)
        writer.writeItems(items(0, 3))
        writer.commit()

        assertTrue(intact())
        writer.finish(summaryOf(3))
        assertTrue(intact())
    }

    // ------------------------------------------------------------------ cancelled, failed, interrupted

    @Test
    fun `a load cancelled after rows were written puts the index back and keeps the rows`() = runBlocking {
        Fixtures.register(database, "src")
        val writer = writer()
        writer.begin("src", ImportMode.APPEND)
        writer.writeGroups(listOf(MediaGroup("g", "G", MediaKind.LIVE)))
        writer.writeItems(items(0, 3))
        writer.commit()
        assertFalse(present())
        writer.writeItems(items(3, 5))

        writer.abort(null)

        assertTrue(intact())
        assertEquals("the committed batch stays, the one in flight is rolled back", 3, rowsOf("src"))
        assertEquals("ok", text("PRAGMA integrity_check"))
    }

    @Test
    fun `a load that failed before its first commit leaves the index as it found it`() = runBlocking {
        Fixtures.register(database, "src")
        val writer = writer()
        writer.begin("src", ImportMode.APPEND)
        writer.writeGroups(listOf(MediaGroup("g", "G", MediaKind.LIVE)))
        writer.writeItems(items(0, 3))

        writer.abort(java.io.IOException("connection reset"))

        assertTrue("the drop was in the transaction that rolled back, or is undone after it", intact())
        assertEquals(0, rowsOf("src"))
    }

    @Test
    fun `a load that failed at its categories never touched the index`() = runBlocking {
        Fixtures.register(database, "src")
        val writer = writer()
        writer.begin("src", ImportMode.APPEND)
        assertTrue(intact())

        writer.abort(java.io.IOException("timed out"))

        assertTrue(intact())
    }

    /** The process died between the drop and the build: nothing ran `finish` or `abort`. */
    @Test
    fun `the next load puts back an index a load that never ended left out`() = runBlocking {
        Fixtures.register(database, "src")
        val interrupted = writer()
        interrupted.begin("src", ImportMode.APPEND)
        interrupted.writeGroups(listOf(MediaGroup("g", "G", MediaKind.LIVE)))
        interrupted.writeItems(items(0, 4))
        interrupted.commit()
        assertFalse(present())
        // No finish, no abort: the writer is simply gone. Its connection is the same in-memory database.

        val next = writer()
        next.begin("src", ImportMode.APPEND)

        assertTrue("the first step of the next load", intact())
        assertEquals("rows that are here: this load is not a first one, and does not take it out again", 4, rowsOf("src"))
        next.writeItems(items(4, 6))
        next.commit()
        assertTrue(intact())
        next.finish(summaryOf(2))
        assertEquals(6, rowsOf("src"))
        assertEquals("ok", text("PRAGMA integrity_check"))
    }

    // ------------------------------------------------------------------ the declaration

    /**
     * The DDL held beside the entity is Room's own for its index: the migration that restores
     * it and the entity that creates it must mean the same index, or a database is made whole
     * into a shape Room then calls wrong.
     */
    @Test
    fun `the index put back is the index the entity declares`() = runBlocking {
        val declared = indexInfo()
        assertEquals("source_id, kind and sort_title, then the rowid", 4, declared.size)

        val db = database.openHelper.writableDatabase
        db.execSQL(SortTitleIndex.DROP)
        assertFalse(present())
        assertTrue(SortTitleIndex.restore(db))

        assertEquals(declared, indexInfo())
    }

    @Test
    fun `an index of the same name on other columns is mended, and a sound one is left alone`() = runBlocking {
        val db = database.openHelper.writableDatabase
        assertTrue(SortTitleIndex.restore(db))

        db.execSQL(SortTitleIndex.DROP)
        db.execSQL("CREATE INDEX `${SortTitleIndex.NAME}` ON `media` (`title`)")
        assertFalse(SortTitleIndex.isIntact(db))
        assertTrue(SortTitleIndex.restore(db))
        assertEquals(listOf("source_id", "kind", "sort_title"), SortTitleIndex.columnsOf(db))
    }

    // ------------------------------------------------------------------ helpers

    private fun writer() = RoomCatalogWriter(database) { 2_000L }

    private fun item(id: String, title: String, group: String? = null, order: Int = 0) = CatalogItem(
        id = id,
        sourceId = "src",
        kind = MediaKind.LIVE,
        title = title,
        streamUrl = "http://host/a/$id.ts",
        groupId = group,
        providerOrder = order,
    )

    private fun items(from: Int, to: Int) = (from until to).map { item("row-$it", "Row ${"%04d".format(it)}", group = "g", order = it) }

    private fun summaryOf(count: Int) =
        ImportSummary(sourceId = "src", items = count, groups = 0, skipped = 0, byKind = mapOf(MediaKind.LIVE to count), durationMs = 1)

    private fun present(): Boolean =
        countOf("SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = '${SortTitleIndex.NAME}'") == 1

    private fun intact(): Boolean = SortTitleIndex.isIntact(database.openHelper.writableDatabase)

    private fun indexInfo(): List<String> =
        database.openHelper.writableDatabase.query("PRAGMA index_xinfo(`${SortTitleIndex.NAME}`)").use { cursor ->
            val seqno = cursor.getColumnIndexOrThrow("seqno")
            val name = cursor.getColumnIndexOrThrow("name")
            val desc = cursor.getColumnIndexOrThrow("desc")
            val key = cursor.getColumnIndexOrThrow("key")
            buildList {
                while (cursor.moveToNext()) add("${cursor.getInt(seqno)}:${cursor.getString(name)}:${cursor.getInt(desc)}:${cursor.getInt(key)}")
            }
        }

    private fun rowsOf(sourceId: String): Int =
        database.openHelper.writableDatabase
            .query("SELECT COUNT(*) FROM media WHERE source_id = ?", arrayOf<Any>(sourceId))
            .use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else -1 }

    private fun countOf(sql: String): Int =
        database.openHelper.writableDatabase.query(sql).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else -1 }

    private fun text(sql: String): String =
        database.openHelper.writableDatabase.query(sql).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else "" }

    private fun repository() = RoomCatalogRepository(
        mediaDao = database.mediaDao(),
        groupDao = database.groupDao(),
        favoriteDao = database.favoriteDao(),
        progressDao = database.progressDao(),
        database = database,
    )
}
