package com.castivio.data.database

import com.castivio.domain.CatalogQuery
import com.castivio.domain.MediaKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The counts, the rail and the lists read the subscription chosen as soon as they are told, Room or no Room.
 *
 * On a device, switching between two playlists was slow to show: every one of these finds the active
 * subscription through `source`, and was moved to the new one by Room's notice of the write and nothing else.
 * Here the switch is written the way Room does not see -- a raw statement, which does not ask the tracker to
 * look -- so the only thing that can move a reader is [RoomCatalogRepository.activeChanged], which is what
 * `RoomSourceRepository` tells on a switch (`SourceReadersTest` holds that half).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ActiveSwitchReadersTest {

    private lateinit var database: CastivioDatabase

    @Before
    fun setUp() {
        database = CastivioDatabase.inMemory(RuntimeEnvironment.getApplication())
        // The first registered is the active one.
        Fixtures.import(database, Fixtures.livePlaylist(3) { "Sports" }, sourceId = "one")
        Fixtures.import(database, Fixtures.livePlaylist(2) { "News" }, sourceId = "two")
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `the count reads the playlist chosen`() = runBlocking {
        val catalogue = repository()
        val seen = Channel<Int>(Channel.UNLIMITED)
        val count = launch(Dispatchers.IO) { catalogue.count(MediaKind.LIVE).collect { seen.send(it) } }
        assertEquals(3, withTimeout(TOLD) { seen.receive() })

        switchTo("two")
        catalogue.activeChanged()

        withTimeout(TOLD) { while (seen.receive() != 2) continue }
        count.cancel()
    }

    @Test
    fun `the rail reads the playlist chosen`() = runBlocking {
        val catalogue = repository()
        val seen = Channel<List<String>>(Channel.UNLIMITED)
        val rail = launch(Dispatchers.IO) {
            catalogue.groups(MediaKind.LIVE).collect { groups -> seen.send(groups.map { it.name }) }
        }
        assertEquals(listOf("Sports"), withTimeout(TOLD) { seen.receive() })

        switchTo("two")
        catalogue.activeChanged()

        withTimeout(TOLD) { while (seen.receive() != listOf("News")) continue }
        rail.cancel()
    }

    /** A list is read again as a new generation of its pages, so one is waited for after the switch. */
    @Test
    fun `an open list is read again for the playlist chosen`() = runBlocking {
        val catalogue = repository()
        val generations = Channel<Unit>(Channel.UNLIMITED)
        val list = launch(Dispatchers.IO) {
            catalogue.items(CatalogQuery(MediaKind.LIVE)).collect { generations.send(Unit) }
        }
        withTimeout(TOLD) { generations.receive() }

        switchTo("two")
        catalogue.activeChanged()

        withTimeout(TOLD) { generations.receive() }
        list.cancel()
    }

    private fun repository() = RoomCatalogRepository(
        mediaDao = database.mediaDao(),
        groupDao = database.groupDao(),
        favoriteDao = database.favoriteDao(),
        progressDao = database.progressDao(),
        database = database,
    )

    /** The switch, written past Room: nothing asks its tracker to look, so no notice comes of it. */
    private fun switchTo(id: String) =
        database.openHelper.writableDatabase.execSQL(
            "UPDATE source SET is_active = CASE WHEN id = ? THEN 1 ELSE 0 END",
            arrayOf<Any>(id),
        )

    private companion object {
        /** Long enough for a reading that is coming, short enough to fail on one that is not. */
        const val TOLD = 5_000L
    }
}
