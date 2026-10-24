package com.castivio.data.database

import com.castivio.domain.CatalogQuery
import com.castivio.domain.HiddenKind
import com.castivio.domain.MediaKind
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
 * The rail's Recently watched list, against real SQLite: a channel is recorded when it is watched, the
 * list is the channels watched with the last first, a channel watched again goes back to the top and is
 * listed once, and the record is not a position -- nothing is put in Continue Watching, and a position a
 * row already had is kept.
 *
 * Five channels, `Channel 0` to `Channel 4`: the first three in Sports, the last two in News.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecentChannelsTest {

    private lateinit var database: CastivioDatabase

    /** The clock the records are written with: each watch below is a second after the one before. */
    private var now = 1_000L

    @Before
    fun setUp() {
        database = CastivioDatabase.inMemory(RuntimeEnvironment.getApplication())
        Fixtures.import(database, Fixtures.livePlaylist(5) { if (it < 3) "Sports" else "News" })
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

    private fun progress(onWatched: () -> Unit = {}) =
        RoomProgressRepository(database.progressDao(), onWatched = onWatched) { now }

    /** Read from the table and not through the lists, which leave a hidden channel out. */
    private fun idOf(title: String) = database.openHelper.readableDatabase
        .query("SELECT id FROM media WHERE title = ?", arrayOf<Any>(title))
        .use { it.moveToFirst(); it.getString(0) }

    private val recent = CatalogQuery(kind = MediaKind.LIVE, recent = true)

    private fun titles() = runBlocking { repository().window(recent, offset = 0, limit = 100).items.map { it.title } }

    private fun total() = runBlocking { repository().window(recent, offset = 0, limit = 1).totalCount }

    private fun watch(vararg numbers: Int) = runBlocking {
        numbers.forEach { number ->
            now += 1_000
            progress().watched(idOf("Channel $number"))
        }
    }

    private fun channels(vararg numbers: Int) = numbers.map { "Channel $it" }

    // ------------------------------------------------------------------------------------- the list

    @Test
    fun `nothing watched is an empty list`() {
        assertEquals(emptyList<String>(), titles())
        assertEquals(0, total())
    }

    @Test
    fun `the channels watched are listed from every bouquet, the one watched last first`() {
        watch(2, 0, 4)

        assertEquals(channels(4, 0, 2), titles())
        assertEquals(3, total())
    }

    @Test
    fun `a channel watched again goes back to the top, and is listed once`() {
        watch(1, 2, 3)
        watch(1)

        assertEquals(channels(1, 3, 2), titles())
        assertEquals(3, total())
    }

    @Test
    fun `the list is read in windows, in the same order`() = runBlocking {
        watch(0, 1, 2, 3, 4)

        val second = repository().window(recent, offset = 2, limit = 2)

        assertEquals(channels(2, 1), second.items.map { it.title })
        assertEquals(5, second.totalCount)
    }

    @Test
    fun `a channel the viewer hid is not in the list`() = runBlocking {
        watch(0, 1, 2)

        RoomHiddenItems(database.hiddenDao(), clock = { now }).hide(HiddenKind.CHANNEL, listOf(idOf("Channel 1")))

        assertEquals(channels(2, 0), titles())
        assertEquals(2, total())
    }

    @Test
    fun `a record of a channel the subscription does not carry is not listed`() = runBlocking {
        progress().watched("no-such-channel")

        assertEquals(emptyList<String>(), titles())
        assertEquals(0, total())
    }

    // ------------------------------------------------------------------------------- the record

    @Test
    fun `watching makes one row, and watching again moves its time and makes no other`() = runBlocking {
        val id = idOf("Channel 3")

        now = 5_000L
        progress().watched(id)
        now = 9_000L
        progress().watched(id)

        val stored = progress().progress(id)!!
        assertEquals(9_000L, stored.updatedAtEpochMs)
        assertEquals(1, database.progressDao().pageHistory().firstPage().size)
    }

    @Test
    fun `watching keeps a position the row already had, and only moves its time`() = runBlocking {
        val id = idOf("Channel 0")
        now = 2_000L
        progress().save(id, positionMs = 60_000, durationMs = 600_000)

        now = 7_000L
        progress().watched(id)

        val stored = progress().progress(id)!!
        assertEquals(60_000L, stored.positionMs)
        assertEquals(600_000L, stored.durationMs)
        assertEquals(7_000L, stored.updatedAtEpochMs)
    }

    @Test
    fun `a channel watched is not put in Continue Watching`() = runBlocking {
        watch(0, 1)

        assertTrue(database.progressDao().pageInProgress().firstPage().isEmpty())
        assertEquals(2, database.progressDao().pageHistory().firstPage().size)
    }

    // ----------------------------------------------------------------------- telling the list

    @Test
    fun `each channel watched tells the list to read again`() = runBlocking {
        var told = 0
        val progress = progress(onWatched = { told++ })

        progress.watched(idOf("Channel 0"))
        progress.watched(idOf("Channel 1"))
        progress.watched(idOf("Channel 0"))

        assertEquals(3, told)
    }

    @Test
    fun `a channel watched through the module's wiring is at the top of the list at once`() = runBlocking {
        val catalogue = repository()
        val progress = RoomProgressRepository(database.progressDao(), onWatched = catalogue::watchedChanged) { now }

        now = 3_000L
        progress.watched(idOf("Channel 2"))
        now = 4_000L
        progress.watched(idOf("Channel 4"))

        assertEquals(channels(4, 2), catalogue.window(recent, offset = 0, limit = 100).items.map { it.title })
    }

    // ------------------------------------------------------------------------ the channel keys

    @Test
    fun `the channel keys have no place in the list to step from, so they keep to the bouquet`() = runBlocking {
        watch(0, 1, 2)

        assertNull(repository().rank(recent, idOf("Channel 1")))
    }
}
