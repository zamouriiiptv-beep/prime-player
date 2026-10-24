package com.castivio.data.database

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
 * Several channels of one bouquet moved together, against real SQLite: they keep their order among
 * themselves, they bunch at an edge rather than pass one another, a single channel moves as it always
 * did -- and nothing is kept before Save, or touched in another bouquet.
 *
 * Eight channels, `Channel 0` to `Channel 7`: the first six in Sports, the last two in News.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GroupOrderTest {

    private lateinit var database: CastivioDatabase

    @Before
    fun setUp() {
        database = CastivioDatabase.inMemory(RuntimeEnvironment.getApplication())
        Fixtures.import(database, playlist())
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun playlist() = Fixtures.livePlaylist(8) { if (it < 6) "Sports" else "News" }

    private fun repository() = RoomCatalogRepository(
        mediaDao = database.mediaDao(),
        groupDao = database.groupDao(),
        favoriteDao = database.favoriteDao(),
        progressDao = database.progressDao(),
        database = database,
    )

    private fun groupId(name: String = "Sports") =
        runBlocking { database.groupDao().groupsNow("LIVE").first { it.name == name }.id }

    private fun idOf(title: String) =
        runBlocking { database.mediaDao().window("LIVE", 0, 100).single { it.title == title }.id }

    private fun ids(vararg numbers: Int) = numbers.map { idOf("Channel $it") }

    /** The bouquet as a list would read it: the saved order, or the draft while arranging. */
    private fun titles(arranging: Boolean = false, name: String = "Sports"): List<String> = runBlocking {
        val group = groupId(name)
        val scope = if (arranging) OrderScopes.draft(group) else OrderScopes.channels(group)
        if (database.orderDao().has(scope)) {
            database.mediaDao().windowArranged("LIVE", group, scope, 0, 100).map { it.title }
        } else {
            database.mediaDao().windowInGroup("LIVE", group, 0, 100).map { it.title }
        }
    }

    private fun channels(vararg numbers: Int) = numbers.map { "Channel $it" }

    private val provider = channels(0, 1, 2, 3, 4, 5)

    // ------------------------------------------------------------------------------ moving together

    @Test
    fun `neighbours move up past the channel above them, together`() = runBlocking {
        val repository = repository()
        repository.begin(groupId())

        assertTrue(repository.moveAll(groupId(), ids(2, 3), up = true))

        assertEquals(channels(0, 2, 3, 1, 4, 5), titles(arranging = true))
    }

    @Test
    fun `neighbours move down past the channel below them, together`() = runBlocking {
        val repository = repository()
        repository.begin(groupId())

        assertTrue(repository.moveAll(groupId(), ids(2, 3), up = false))

        assertEquals(channels(0, 1, 4, 2, 3, 5), titles(arranging = true))
    }

    @Test
    fun `scattered channels keep their order and bunch at the top`() = runBlocking {
        val repository = repository()
        repository.begin(groupId())
        val group = ids(1, 3, 5)

        assertTrue(repository.moveAll(groupId(), group, up = true))
        assertEquals(channels(1, 0, 3, 2, 5, 4), titles(arranging = true))

        assertTrue(repository.moveAll(groupId(), group, up = true))
        assertEquals(channels(1, 3, 0, 5, 2, 4), titles(arranging = true))

        assertTrue(repository.moveAll(groupId(), group, up = true))
        assertEquals(channels(1, 3, 5, 0, 2, 4), titles(arranging = true))
    }

    @Test
    fun `scattered channels keep their order and bunch at the bottom`() = runBlocking {
        val repository = repository()
        repository.begin(groupId())
        val group = ids(0, 2, 4)

        repeat(3) { assertTrue(repository.moveAll(groupId(), group, up = false)) }

        assertEquals(channels(1, 3, 5, 0, 2, 4), titles(arranging = true))
    }

    @Test
    fun `a group at the edge it is heading for does not move`() = runBlocking {
        val repository = repository()
        repository.begin(groupId())

        assertFalse(repository.moveAll(groupId(), ids(0, 1), up = true))
        assertFalse(repository.moveAll(groupId(), ids(4, 5), up = false))
        assertEquals(provider, titles(arranging = true))
    }

    @Test
    fun `channels of the group never pass one another`() = runBlocking {
        val repository = repository()
        repository.begin(groupId())
        val group = ids(1, 2, 4)

        repeat(7) { repository.moveAll(groupId(), group, up = true) }
        repeat(7) { repository.moveAll(groupId(), group, up = false) }
        repeat(2) { repository.moveAll(groupId(), group, up = true) }

        assertEquals(channels(1, 2, 4), titles(arranging = true).filter { it in channels(1, 2, 4) })
    }

    @Test
    fun `the channels that are not of the group keep their order among themselves`() = runBlocking {
        val repository = repository()
        repository.begin(groupId())
        val group = ids(2, 4)

        repeat(4) { repository.moveAll(groupId(), group, up = true) }
        repeat(3) { repository.moveAll(groupId(), group, up = false) }

        assertEquals(channels(0, 1, 3, 5), titles(arranging = true).filterNot { it in channels(2, 4) })
    }

    @Test
    fun `no two channels ever share a place`() = runBlocking {
        val repository = repository()
        repository.begin(groupId())
        val group = ids(1, 3, 4)

        repeat(5) { repository.moveAll(groupId(), group, up = true) }
        repeat(5) { repository.moveAll(groupId(), group, up = false) }

        val places = runBlocking {
            ids(0, 1, 2, 3, 4, 5).map { database.orderDao().positionOf(OrderScopes.draft(groupId()), it) }
        }
        assertTrue(places.all { it != null })
        assertEquals(provider.size, places.toSet().size)
        assertEquals(provider.toSet(), titles(arranging = true).toSet())
    }

    @Test
    fun `the order the ids are given in makes no difference`() = runBlocking {
        val repository = repository()
        repository.begin(groupId())

        repository.moveAll(groupId(), ids(5, 1, 3), up = true)

        assertEquals(channels(1, 0, 3, 2, 5, 4), titles(arranging = true))
    }

    // ------------------------------------------------------------------------- one is as it was

    @Test
    fun `a group of one moves exactly as a single channel does`() = runBlocking {
        val repository = repository()
        repository.begin(groupId())
        val viaGroup = repository.moveAll(groupId(), ids(3), up = true)
        val grouped = titles(arranging = true)

        repository.begin(groupId())
        val viaSingle = repository.move(groupId(), idOf("Channel 3"), up = true)

        assertEquals(viaSingle, viaGroup)
        assertEquals(grouped, titles(arranging = true))
        assertEquals(channels(0, 1, 3, 2, 4, 5), grouped)
    }

    @Test
    fun `channels that are not in the bouquet are ignored, and the others still move`() = runBlocking {
        val repository = repository()
        repository.begin(groupId())

        assertTrue(repository.moveAll(groupId(), ids(2) + "no-such-channel", up = true))
        assertEquals(channels(0, 2, 1, 3, 4, 5), titles(arranging = true))

        assertFalse(repository.moveAll(groupId(), listOf("no-such-channel", "nor-this"), up = true))
        assertFalse(repository.moveAll(groupId(), emptyList(), up = true))
    }

    @Test
    fun `a channel of another bouquet is not a neighbour`() = runBlocking {
        val repository = repository()
        repository.begin(groupId())

        // Channel 5 is last in Sports; Channels 6 and 7 are News's, and are not below it.
        assertFalse(repository.moveAll(groupId(), ids(5), up = false))
        assertEquals(provider, titles(arranging = true))
    }

    // ------------------------------------------------------------------ a dragged group, let go

    @Test
    fun `a dragged group goes to the very top, in the order it stood`() = runBlocking {
        val repository = repository()
        repository.begin(groupId())

        assertTrue(repository.moveAllBefore(groupId(), ids(2, 4), beforeId = idOf("Channel 0")))

        assertEquals(channels(2, 4, 0, 1, 3, 5), titles(arranging = true))
    }

    @Test
    fun `a dragged group goes to the end when it is let go after every channel`() = runBlocking {
        val repository = repository()
        repository.begin(groupId())

        assertTrue(repository.moveAllBefore(groupId(), ids(0, 3), beforeId = null))

        assertEquals(channels(1, 2, 4, 5, 0, 3), titles(arranging = true))
    }

    @Test
    fun `a dragged group goes between two channels`() = runBlocking {
        val repository = repository()
        repository.begin(groupId())

        assertTrue(repository.moveAllBefore(groupId(), ids(0, 5), beforeId = idOf("Channel 3")))

        assertEquals(channels(1, 2, 0, 5, 3, 4), titles(arranging = true))
    }

    @Test
    fun `a dragged group keeps the order it stood in, whatever order the ids come in`() = runBlocking {
        val repository = repository()
        repository.begin(groupId())

        assertTrue(repository.moveAllBefore(groupId(), ids(4, 1), beforeId = idOf("Channel 0")))

        assertEquals(channels(1, 4, 0, 2, 3, 5), titles(arranging = true))
    }

    @Test
    fun `a single channel dragged goes exactly where it is let go`() = runBlocking {
        val repository = repository()
        repository.begin(groupId())

        assertTrue(repository.moveAllBefore(groupId(), ids(5), beforeId = idOf("Channel 1")))

        assertEquals(channels(0, 5, 1, 2, 3, 4), titles(arranging = true))
    }

    @Test
    fun `the channels a drag does not carry keep their order among themselves`() = runBlocking {
        val repository = repository()
        repository.begin(groupId())

        repository.moveAllBefore(groupId(), ids(1, 4), beforeId = idOf("Channel 0"))
        repository.moveAllBefore(groupId(), ids(1, 4), beforeId = null)
        repository.moveAllBefore(groupId(), ids(1, 4), beforeId = idOf("Channel 3"))

        assertEquals(channels(0, 2, 3, 5), titles(arranging = true).filterNot { it in channels(1, 4) })
        assertEquals(channels(0, 2, 1, 4, 3, 5), titles(arranging = true))
    }

    @Test
    fun `let go on one of its own channels, the group does not move`() = runBlocking {
        val repository = repository()
        repository.begin(groupId())

        assertFalse(repository.moveAllBefore(groupId(), ids(2, 4), beforeId = idOf("Channel 4")))

        assertEquals(provider, titles(arranging = true))
    }

    @Test
    fun `a place outside the bouquet, or a group with nothing in it, moves nothing`() = runBlocking {
        val repository = repository()
        repository.begin(groupId())

        // Channel 6 is News's: not a place in Sports.
        assertFalse(repository.moveAllBefore(groupId(), ids(1), beforeId = idOf("Channel 6")))
        assertFalse(repository.moveAllBefore(groupId(), ids(1), beforeId = "no-such-channel"))
        assertFalse(repository.moveAllBefore(groupId(), listOf("no-such-channel"), beforeId = null))
        assertFalse(repository.moveAllBefore(groupId(), emptyList(), beforeId = idOf("Channel 0")))

        assertEquals(provider, titles(arranging = true))
    }

    @Test
    fun `no two channels share a place after a drag`() = runBlocking {
        val repository = repository()
        repository.begin(groupId())

        repository.moveAllBefore(groupId(), ids(3, 5), beforeId = idOf("Channel 0"))
        repository.moveAllBefore(groupId(), ids(0), beforeId = null)
        repository.moveAllBefore(groupId(), ids(2, 3, 4), beforeId = idOf("Channel 5"))

        val places = runBlocking {
            ids(0, 1, 2, 3, 4, 5).map { database.orderDao().positionOf(OrderScopes.draft(groupId()), it) }
        }
        assertTrue(places.all { it != null })
        assertEquals(provider.size, places.toSet().size)
        assertEquals(provider.toSet(), titles(arranging = true).toSet())
    }

    @Test
    fun `the arrows go on from where a drag left the group`() = runBlocking {
        val repository = repository()
        repository.begin(groupId())
        repository.moveAllBefore(groupId(), ids(4, 5), beforeId = idOf("Channel 0"))

        assertTrue(repository.moveAll(groupId(), ids(4, 5), up = false))
        assertEquals(channels(0, 4, 5, 1, 2, 3), titles(arranging = true))

        assertTrue(repository.move(groupId(), idOf("Channel 1"), up = true))
        assertEquals(channels(0, 4, 1, 5, 2, 3), titles(arranging = true))
    }

    @Test
    fun `a drag is a draft until it is saved, and saving keeps it`() = runBlocking {
        val repository = repository()
        repository.begin(groupId())

        repository.moveAllBefore(groupId(), ids(3, 5), beforeId = idOf("Channel 0"))
        assertEquals(provider, titles())

        repository.save(groupId())
        assertEquals(channels(3, 5, 0, 1, 2, 4), titles())
        assertFalse(database.orderDao().has(OrderScopes.draft(groupId())))
    }

    @Test
    fun `cancelling a drag puts back the order there was`() = runBlocking {
        val repository = repository()
        repository.begin(groupId())
        repository.moveAllBefore(groupId(), ids(5), beforeId = idOf("Channel 2"))
        repository.save(groupId())
        val before = titles()

        repository.begin(groupId())
        repository.moveAllBefore(groupId(), ids(0, 1), beforeId = null)
        repository.discard(groupId())

        assertEquals(before, titles())
        assertEquals(channels(0, 1, 5, 2, 3, 4), before)
    }

    @Test
    fun `a drag in one bouquet leaves another bouquet alone`() = runBlocking {
        val repository = repository()
        repository.begin(groupId())

        repository.moveAllBefore(groupId(), ids(4, 5), beforeId = idOf("Channel 0"))
        repository.save(groupId())

        assertEquals(channels(6, 7), titles(name = "News"))
        assertFalse(database.orderDao().has(OrderScopes.channels(groupId("News"))))
    }

    // ---------------------------------------------------------------- drafts, saving, cancelling

    @Test
    fun `a group move is a draft, and the saved order is untouched until it is saved`() = runBlocking {
        val repository = repository()
        repository.begin(groupId())

        repository.moveAll(groupId(), ids(3, 4), up = true)

        assertEquals(channels(0, 1, 3, 4, 2, 5), titles(arranging = true))
        assertEquals(provider, titles())
    }

    @Test
    fun `saving makes the group's place the order the list reads`() = runBlocking {
        val repository = repository()
        repository.begin(groupId())
        repository.moveAll(groupId(), ids(3, 4), up = true)
        repository.moveAll(groupId(), ids(3, 4), up = true)

        repository.save(groupId())

        assertEquals(channels(0, 3, 4, 1, 2, 5), titles())
        assertFalse(database.orderDao().has(OrderScopes.draft(groupId())))
    }

    @Test
    fun `cancelling puts back the order there was`() = runBlocking {
        val repository = repository()
        repository.begin(groupId())
        repository.moveAll(groupId(), ids(1, 5), up = false)
        repository.save(groupId())
        val before = titles()

        repository.begin(groupId())
        repository.moveAll(groupId(), ids(0, 2, 4), up = false)
        repository.moveAll(groupId(), ids(0, 2, 4), up = false)
        repository.discard(groupId())

        assertEquals(before, titles())
    }

    @Test
    fun `cancelling a first group arrangement leaves the provider's order`() = runBlocking {
        val repository = repository()
        repository.begin(groupId())
        repository.moveAll(groupId(), ids(2, 3), up = true)

        repository.discard(groupId())

        assertEquals(provider, titles())
        assertFalse(database.orderDao().has(OrderScopes.channels(groupId())))
    }

    @Test
    fun `a saved group order survives a refresh of the same channels`() = runBlocking {
        val repository = repository()
        repository.begin(groupId())
        repository.moveAll(groupId(), ids(4, 5), up = true)
        repository.moveAll(groupId(), ids(4, 5), up = true)
        repository.save(groupId())
        val saved = titles()

        Fixtures.import(database, playlist())

        assertEquals(saved, titles())
        assertEquals(channels(0, 1, 4, 5, 2, 3), saved)
    }

    @Test
    fun `a channel the provider adds later follows a saved group order`() = runBlocking {
        val repository = repository()
        repository.begin(groupId())
        repository.moveAll(groupId(), ids(4, 5), up = true)
        repository.save(groupId())

        Fixtures.import(database, Fixtures.livePlaylist(9) { if (it < 6 || it == 8) "Sports" else "News" })

        assertEquals(channels(0, 1, 2, 4, 5, 3, 8), titles())
    }

    // ----------------------------------------------------------------------- what is left alone

    @Test
    fun `another bouquet's channels are not moved by a group in this one`() = runBlocking {
        val repository = repository()
        repository.begin(groupId())

        repository.moveAll(groupId(), ids(0, 1, 2), up = false)
        repository.save(groupId())

        assertEquals(channels(6, 7), titles(name = "News"))
        assertFalse(database.orderDao().has(OrderScopes.channels(groupId("News"))))
    }

    @Test
    fun `the provider's order is never written`() = runBlocking {
        val before = runBlocking { database.mediaDao().window("LIVE", 0, 100).map { it.providerOrder } }
        val repository = repository()

        repository.begin(groupId())
        repository.moveAll(groupId(), ids(1, 3), up = true)
        repository.moveAll(groupId(), ids(0, 2), up = false)
        repository.save(groupId())
        repository.begin(groupId())
        repository.moveAll(groupId(), ids(4, 5), up = true)
        repository.discard(groupId())
        repository.saveBouquets(MediaKind.LIVE, listOf(groupId("News"), groupId()))

        val after = runBlocking { database.mediaDao().window("LIVE", 0, 100).map { it.providerOrder } }
        assertEquals(before, after)
        assertEquals((0..7).toList(), after)
    }
}
