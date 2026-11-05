package com.castivio.feature.home

import androidx.paging.PagingSource
import com.castivio.domain.DirectoryFailure
import com.castivio.domain.StationRecord
import com.castivio.domain.WorldListing
import com.castivio.domain.WorldStation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** One list of the directory, read a page at a time and screened against what this device has seen of its stations. */
class WorldStationsSourceTest {

    private val now = 2_000_000L
    private val library = MemoryWorldLibrary()
    private val directory = FakeWorldDirectory()

    private fun source(listing: WorldListing = WorldListing.Top) = WorldStationsSource(directory, library, listing) { now }

    private fun refresh(source: WorldStationsSource, key: Int? = null) =
        PagingSource.LoadParams.Refresh(key, loadSize = SIZE, placeholdersEnabled = false).let { params ->
            runBlocking { source.load(params) }
        }

    private fun page(result: PagingSource.LoadResult<Int, WorldStation>) =
        result as PagingSource.LoadResult.Page<Int, WorldStation>

    private fun stations(count: Int, from: Int = 0) = (from until from + count).map { worldStation("s$it") }

    private fun resting(uuid: String) {
        library.recordsByUuid[uuid] = StationRecord(uuid, lastFailedAtMs = now - 1_000L, consecutiveFailures = 1)
    }

    @Test
    fun `a full page is listed in the directory's order, and the next page is where it ended`() {
        directory.stationsOf = { stations(150) }

        val loaded = page(refresh(source()))

        assertEquals((0 until SIZE).map { "s$it" }, loaded.data.map { it.uuid })
        assertNull("nothing before the first page", loaded.prevKey)
        assertEquals(SIZE, loaded.nextKey)
        assertEquals(listOf(Triple(WorldListing.Top, 0, SIZE)), directory.pageAsks)
    }

    @Test
    fun `a short page is the last one`() {
        directory.stationsOf = { stations(20) }

        val loaded = page(refresh(source()))

        assertEquals(20, loaded.data.size)
        assertNull(loaded.nextKey)
    }

    @Test
    fun `a station that failed lately is left out of the list`() {
        directory.stationsOf = { stations(5) }
        resting("s1")

        val loaded = page(refresh(source()))

        assertEquals(listOf("s0", "s2", "s3", "s4"), loaded.data.map { it.uuid })
    }

    @Test
    fun `a station the directory says does not play, and that never played here, is left out`() {
        directory.stationsOf = { listOf(worldStation("ok"), worldStation("dead", checkedOk = false)) }

        val loaded = page(refresh(source()))

        assertEquals(listOf("ok"), loaded.data.map { it.uuid })
    }

    @Test
    fun `a station the directory has not checked for long is listed after the others of its page`() {
        directory.stationsOf = { listOf(worldStation("unknown", checkedAtMs = null), worldStation("fine"), worldStation("fine2")) }

        val loaded = page(refresh(source()))

        assertEquals(listOf("fine", "fine2", "unknown"), loaded.data.map { it.uuid })
    }

    @Test
    fun `a page the screening empties does not end the list, the next one is read`() {
        directory.stationsOf = { stations(SIZE + 10) }
        (0 until SIZE).forEach { resting("s$it") }

        val loaded = page(refresh(source()))

        assertEquals((SIZE until SIZE + 10).map { "s$it" }, loaded.data.map { it.uuid })
        assertNull("the second page was a short one", loaded.nextKey)
        assertEquals(listOf(0, SIZE), directory.pageAsks.map { it.second })
    }

    @Test
    fun `a list the screening empties as far as a load reads ends there`() {
        directory.stationsOf = { stations(SIZE * 6) }
        (0 until SIZE * 6).forEach { resting("s$it") }

        val loaded = page(refresh(source()))

        assertTrue(loaded.data.isEmpty())
        assertNull(loaded.nextKey)
        assertEquals("it stopped after a few raw pages and did not read the whole directory", 4, directory.pageAsks.size)
    }

    @Test
    fun `a station that comes on two pages is on the list once, on the first`() {
        // The directory's order moved between the two reads: s59 is the last of the first page and the first of the second.
        val first = stations(SIZE)
        val second = listOf(worldStation("s${SIZE - 1}")) + stations(SIZE - 1, from = SIZE)
        directory.stationsOf = { first + second }
        val source = source()

        val one = page(refresh(source, key = 0))
        val two = page(refresh(source, key = SIZE))

        assertTrue(one.data.any { it.uuid == "s${SIZE - 1}" })
        assertTrue("the second page does not carry it again", two.data.none { it.uuid == "s${SIZE - 1}" })
        assertEquals(SIZE - 1, two.data.size)
    }

    @Test
    fun `a page read again gives the stations it gave before`() {
        val first = stations(SIZE)
        directory.stationsOf = { first + listOf(worldStation("s${SIZE - 1}")) + stations(SIZE - 1, from = SIZE) }
        val source = source()
        refresh(source, key = 0)
        val once = page(refresh(source, key = SIZE)).data.map { it.uuid }

        val again = page(refresh(source, key = SIZE)).data.map { it.uuid }

        assertEquals("a page dropped from memory and read again must not lose its stations to the page before it", once, again)
    }

    @Test
    fun `a page that cannot be had is an error that says why`() {
        directory.pageFailure = DirectoryFailure.OFFLINE

        val loaded = refresh(source())

        val error = (loaded as PagingSource.LoadResult.Error).throwable as WorldDirectoryException
        assertEquals(DirectoryFailure.OFFLINE, error.reason)
    }

    @Test
    fun `going back reads the page before, one raw page, and goes no further`() {
        directory.stationsOf = { stations(SIZE * 4) }
        val params = PagingSource.LoadParams.Prepend(key = SIZE, loadSize = SIZE, placeholdersEnabled = false)

        val loaded = page(runBlocking { source().load(params) })

        assertEquals((SIZE until SIZE * 2).map { "s$it" }, loaded.data.map { it.uuid })
        assertEquals("the page before that one", 0, loaded.prevKey)
        assertNull(loaded.nextKey)
    }

    @Test
    fun `what is asked is the list that was opened, and a search is asked by its text`() {
        directory.stationsOf = { stations(3) }

        refresh(source(WorldListing.Search("med")))
        refresh(source(WorldListing.OfCountry("MA")))

        assertEquals(listOf<WorldListing>(WorldListing.Search("med"), WorldListing.OfCountry("MA")), directory.pageAsks.map { it.first })
    }

    private companion object {
        const val SIZE = 60
    }
}
