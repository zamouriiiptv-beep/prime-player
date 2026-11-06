package com.castivio.data.database

import com.castivio.domain.Channel
import com.castivio.domain.Episode
import com.castivio.domain.MediaKind
import com.castivio.domain.Movie
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * What Home's small cards offer to carry on from, against real SQLite: the item of a section watched last, with how far in it the
 * viewer was -- a film, an episode (with the show's name its row carries), a station -- and nothing of another section.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LastWatchedTest {

    private lateinit var database: CastivioDatabase

    /** The clock the records are written with: each write below is a second after the one before. */
    private var now = 1_000L

    @Before
    fun setUp() {
        database = CastivioDatabase.inMemory(RuntimeEnvironment.getApplication())
        Fixtures.import(database, Fixtures.mixedPlaylist())
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun repository() = RoomCatalogRepository(
        mediaDao = database.mediaDao(),
        groupDao = database.groupDao(),
        favoriteDao = database.favoriteDao(),
        progressDao = database.progressDao(),
        database = database,
    )

    private fun progress() = RoomProgressRepository(database.progressDao(), onWatched = {}) { now }

    private fun idOf(title: String) = database.openHelper.readableDatabase
        .query("SELECT id FROM media WHERE title = ?", arrayOf<Any>(title))
        .use { it.moveToFirst(); it.getString(0) }

    private fun last(kind: MediaKind) = runBlocking { repository().lastWatched(kind).first() }

    private fun save(title: String, positionMs: Long, durationMs: Long?) = runBlocking {
        now += 1_000
        progress().save(idOf(title), positionMs, durationMs)
    }

    private fun watched(title: String) = runBlocking {
        now += 1_000
        progress().watched(idOf(title))
    }

    @Test
    fun `nothing watched offers nothing in any section`() {
        for (kind in listOf(MediaKind.MOVIE, MediaKind.SERIES, MediaKind.RADIO)) assertNull(kind.name, last(kind))
    }

    @Test
    fun `the film watched last is offered with how far in it the viewer was`() {
        save("Alpha", positionMs = 1_000, durationMs = 6_000)
        save("[4K] The Matrix", positionMs = 3_600_000, durationMs = 7_200_000)

        val offer = last(MediaKind.MOVIE)!!

        assertTrue(offer.item is Movie)
        assertEquals("[4K] The Matrix", offer.item.title)
        assertEquals(0.5f, offer.progress!!.percent, 0.001f)
        assertNull(offer.showId)
    }

    @Test
    fun `a film only started has no how-far to say`() {
        watched("Alpha")

        val offer = last(MediaKind.MOVIE)!!

        assertEquals("Alpha", offer.item.title)
        assertNull(offer.progress)
    }

    @Test
    fun `the episode watched last is offered with the show's name and id`() {
        save("Breaking Bad S01E01 - Pilot", positionMs = 60_000, durationMs = 3_000_000)
        save("Breaking Bad S02E01 - Seven Thirty-Seven", positionMs = 120_000, durationMs = 3_000_000)

        val offer = last(MediaKind.SERIES)!!

        assertTrue(offer.item is Episode)
        assertEquals("Breaking Bad", offer.showTitle)
        assertNotNull(offer.showId)
        assertEquals(2, (offer.item as Episode).seasonNumber)
    }

    @Test
    fun `the station played last is offered, with no position`() {
        watched("Radio Mars")

        val offer = last(MediaKind.RADIO)!!

        assertTrue(offer.item is Channel)
        assertEquals("Radio Mars", offer.item.title)
        assertNull(offer.progress)
    }

    @Test
    fun `a channel watched is not offered by any other section`() {
        watched("Nova Sports 1")

        assertNull(last(MediaKind.MOVIE))
        assertNull(last(MediaKind.SERIES))
        assertNull(last(MediaKind.RADIO))
    }

    @Test
    fun `a film watched is not offered as an episode or a station`() {
        save("Alpha", positionMs = 1_000, durationMs = 6_000)

        assertNull(last(MediaKind.SERIES))
        assertNull(last(MediaKind.RADIO))
    }
}
