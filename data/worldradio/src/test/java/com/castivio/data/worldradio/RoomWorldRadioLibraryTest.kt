package com.castivio.data.worldradio

import com.castivio.core.common.PlayOutcome
import com.castivio.domain.WorldStation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * What the viewer keeps of the world's stations, in a database of its own: favourites, the stations listened to last, and what
 * listening said about each.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomWorldRadioLibraryTest {

    private lateinit var database: WorldRadioDatabase
    private var now = 1_000L

    @Before
    fun setUp() {
        database = WorldRadioDatabase.inMemory(RuntimeEnvironment.getApplication())
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun library() = RoomWorldRadioLibrary(database, clock = { now })

    private fun station(uuid: String, name: String = "Station $uuid", tags: List<String> = listOf("news", "talk")) = WorldStation(
        uuid = uuid, name = name, url = "http://example.invalid/$uuid", urlResolved = "https://example.invalid/$uuid.mp3",
        favicon = "https://example.invalid/$uuid.png", countryCode = "MA", country = "Morocco", tags = tags, codec = "MP3", bitrate = 128,
        hls = false, serverCheckedOk = true, serverCheckedAtMs = 5_000L, votes = 3, clickCount = 40, sslError = false,
    )

    // -------------------------------------------------------------------------------- favourites

    @Test
    fun `a favourite is kept whole and comes back as it was given`() = runBlocking {
        val library = library()
        val s = station("a")

        library.setFavorite(s, true)

        assertEquals(listOf(s), library.favorites().first())
        assertEquals(setOf("a"), library.favoriteIds().first())
    }

    @Test
    fun `favourites come the one added last first, and a second marking does not move one`() = runBlocking {
        val library = library()
        library.setFavorite(station("a"), true)
        now += 10
        library.setFavorite(station("b"), true)
        now += 10

        library.setFavorite(station("a"), true)

        assertEquals(listOf("b", "a"), library.favorites().first().map { it.uuid })
    }

    @Test
    fun `a favourite let go leaves the list and, when it was never listened to, the store`() = runBlocking {
        val library = library()
        library.setFavorite(station("a"), true)

        library.setFavorite(station("a"), false)

        assertTrue(library.favorites().first().isEmpty())
        assertNull(database.savedStationDao().byUuid("a"))
    }

    @Test
    fun `letting go of a station that was never kept does nothing`() = runBlocking {
        val library = library()

        library.setFavorite(station("ghost"), false)

        assertTrue(library.favorites().first().isEmpty())
    }

    // ----------------------------------------------------------------------------------- listens

    @Test
    fun `listens come the latest first`() = runBlocking {
        val library = library()
        library.recordListened(station("a"), 100)
        library.recordListened(station("b"), 200)
        library.recordListened(station("a"), 300)

        assertEquals(listOf("a", "b"), library.recents(10).first().map { it.uuid })
        assertEquals(listOf("a"), library.recents(1).first().map { it.uuid })
    }

    @Test
    fun `a listen keeps the favourite it already was, and a favourite keeps its listen`() = runBlocking {
        val library = library()
        library.setFavorite(station("a"), true)
        library.recordListened(station("a"), 500)

        library.setFavorite(station("a"), true)
        val row = database.savedStationDao().byUuid("a")!!

        assertEquals(1_000L, row.favoriteAt)
        assertEquals(500L, row.playedAt)
        assertEquals(listOf("a"), library.favorites().first().map { it.uuid })
        assertEquals(listOf("a"), library.recents(10).first().map { it.uuid })
    }

    @Test
    fun `listens past the hundred latest are forgotten, and a favourite is never lost by it`() = runBlocking {
        val library = library()
        library.setFavorite(station("fav"), true)
        library.recordListened(station("fav"), 1)
        for (i in 2..110) library.recordListened(station("s$i"), i.toLong())

        val recents = library.recents(500).first()

        assertEquals(100, recents.size)
        assertTrue("the oldest listens are gone", recents.none { it.uuid == "s2" })
        assertEquals("the favourite stays among the favourites", listOf("fav"), library.favorites().first().map { it.uuid })
    }

    // ------------------------------------------------------------------------------------ health

    @Test
    fun `what listening said is written by the policy`() = runBlocking {
        val library = library()

        library.recordOutcome("a", PlayOutcome.FAILED, 100)
        library.recordOutcome("a", PlayOutcome.FAILED, 200)
        library.recordOutcome("b", PlayOutcome.UNSTABLE, 300)
        library.recordOutcome("c", PlayOutcome.CONFIRMED, 400)

        val records = library.records(listOf("a", "b", "c", "never"))

        assertEquals(2, records.getValue("a").consecutiveFailures)
        assertEquals(200L, records.getValue("a").lastFailedAtMs)
        assertEquals(0, records.getValue("b").consecutiveFailures)
        assertEquals(1, records.getValue("b").unstableCount)
        assertEquals(400L, records.getValue("c").lastOkAtMs)
        assertNull(records["never"])
    }

    @Test
    fun `a confirmed listen clears the failures`() = runBlocking {
        val library = library()
        library.recordOutcome("a", PlayOutcome.FAILED, 100)

        library.recordOutcome("a", PlayOutcome.CONFIRMED, 200)

        assertEquals(0, library.records(listOf("a")).getValue("a").consecutiveFailures)
    }

    @Test
    fun `an inconclusive listen writes nothing`() = runBlocking {
        val library = library()

        library.recordOutcome("a", PlayOutcome.INCONCLUSIVE, 100)

        assertTrue(library.records(listOf("a")).isEmpty())
    }

    @Test
    fun `records are asked for in any number`() = runBlocking {
        val library = library()
        library.recordOutcome("x", PlayOutcome.CONFIRMED, 1)

        val records = library.records((1..1_200).map { "u$it" } + "x")

        assertEquals(setOf("x"), records.keys)
    }

    // ----------------------------------------------------------------------------- refreshing

    @Test
    fun `a favourite is refreshed with the directory's newer word, and a station not kept is not added`() = runBlocking {
        val library = library()
        library.setFavorite(station("a"), true)

        library.refreshSaved(station("a", name = "Renamed"))
        library.refreshSaved(station("ghost"))

        assertEquals(listOf("Renamed"), library.favorites().first().map { it.name })
        assertNull(database.savedStationDao().byUuid("ghost"))
    }

    // ------------------------------------------------------------------------------ the cache

    @Test
    fun `a list the directory sent is kept as it was and replaced by the next`() = runBlocking {
        val cache = RoomDirectoryCache(database.directoryCacheDao())

        assertNull(cache.read("countries"))
        cache.write("countries", "[1]", 10)
        cache.write("countries", "[1,2]", 20)

        val kept = cache.read("countries")!!
        assertEquals("[1,2]", kept.body)
        assertEquals(20L, kept.fetchedAtMs)
        assertNull(cache.read("tags"))
    }
}
