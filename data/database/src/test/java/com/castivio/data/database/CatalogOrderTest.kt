package com.castivio.data.database

import com.castivio.domain.CatalogQuery
import com.castivio.domain.MediaKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
 * The viewer's own order, against real SQLite: arranged, saved, cancelled, kept across a refresh,
 * and never written over the provider's.
 *
 * Five channels in one bouquet, `Channel 0` to `Channel 4`, in the order the playlist listed them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CatalogOrderTest {

    private lateinit var database: CastivioDatabase

    @Before
    fun setUp() {
        database = CastivioDatabase.inMemory(RuntimeEnvironment.getApplication())
        Fixtures.import(database, Fixtures.livePlaylist(5) { "Sports" })
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

    private fun groupId() = runBlocking { database.groupDao().groupsNow("LIVE").single().id }

    private fun idOf(title: String) =
        runBlocking { database.mediaDao().window("LIVE", 0, 100).single { it.title == title }.id }

    /** The bouquet's channels, as a list would read them: saved order, or the draft. */
    private fun titles(arranging: Boolean = false, offset: Int = 0, limit: Int = 100): List<String> = runBlocking {
        val group = groupId()
        val scope = if (arranging) OrderScopes.draft(group) else OrderScopes.channels(group)
        if (database.orderDao().has(scope)) {
            database.mediaDao().windowArranged("LIVE", group, scope, offset, limit).map { it.title }
        } else {
            database.mediaDao().windowInGroup("LIVE", group, offset, limit).map { it.title }
        }
    }

    private fun providerOrders(): List<Int> =
        runBlocking { database.mediaDao().window("LIVE", 0, 100).map { it.providerOrder } }

    private val provider = listOf("Channel 0", "Channel 1", "Channel 2", "Channel 3", "Channel 4")

    @Test
    fun `a bouquet nobody arranged is in the provider's order`() {
        assertEquals(provider, titles())
        assertFalse(runBlocking { database.orderDao().has(OrderScopes.channels(groupId())) })
    }

    @Test
    fun `beginning to arrange changes nothing the viewer can see`() = runBlocking {
        repository().begin(groupId())

        assertEquals(provider, titles(arranging = true))
        assertEquals(provider, titles())
    }

    @Test
    fun `a channel moves one place at a time, up and down`() = runBlocking {
        val repository = repository()
        repository.begin(groupId())

        assertTrue(repository.move(groupId(), idOf("Channel 3"), up = true))
        assertEquals(listOf("Channel 0", "Channel 1", "Channel 3", "Channel 2", "Channel 4"), titles(arranging = true))

        assertTrue(repository.move(groupId(), idOf("Channel 3"), up = true))
        assertTrue(repository.move(groupId(), idOf("Channel 3"), up = true))
        assertEquals(listOf("Channel 3", "Channel 0", "Channel 1", "Channel 2", "Channel 4"), titles(arranging = true))

        assertTrue(repository.move(groupId(), idOf("Channel 3"), up = false))
        assertEquals(listOf("Channel 0", "Channel 3", "Channel 1", "Channel 2", "Channel 4"), titles(arranging = true))
    }

    @Test
    fun `the first channel cannot move up and the last cannot move down`() = runBlocking {
        val repository = repository()
        repository.begin(groupId())

        assertFalse(repository.move(groupId(), idOf("Channel 0"), up = true))
        assertFalse(repository.move(groupId(), idOf("Channel 4"), up = false))
        assertEquals(provider, titles(arranging = true))
    }

    @Test
    fun `moving a channel that is not in the bouquet does nothing`() = runBlocking {
        val repository = repository()
        repository.begin(groupId())

        assertFalse(repository.move(groupId(), "no-such-channel", up = true))
        assertEquals(provider, titles(arranging = true))
    }

    @Test
    fun `moves are drafts, and the saved order is untouched until it is saved`() = runBlocking {
        val repository = repository()
        repository.begin(groupId())
        repository.move(groupId(), idOf("Channel 4"), up = true)
        repository.move(groupId(), idOf("Channel 4"), up = true)

        assertEquals(listOf("Channel 0", "Channel 1", "Channel 4", "Channel 2", "Channel 3"), titles(arranging = true))
        // The list the rest of the app reads has not moved.
        assertEquals(provider, titles())
    }

    @Test
    fun `saving makes the draft the order, and it is the order the list reads`() = runBlocking {
        val repository = repository()
        repository.begin(groupId())
        repository.move(groupId(), idOf("Channel 4"), up = true)
        repository.move(groupId(), idOf("Channel 4"), up = true)

        repository.save(groupId())

        val saved = listOf("Channel 0", "Channel 1", "Channel 4", "Channel 2", "Channel 3")
        assertEquals(saved, titles())
        assertFalse("the draft is gone", database.orderDao().has(OrderScopes.draft(groupId())))
    }

    @Test
    fun `cancelling puts back the order there was`() = runBlocking {
        val repository = repository()
        repository.begin(groupId())
        repository.move(groupId(), idOf("Channel 1"), up = false)
        repository.save(groupId())
        val before = titles()

        repository.begin(groupId())
        repository.move(groupId(), idOf("Channel 4"), up = true)
        repository.move(groupId(), idOf("Channel 4"), up = true)
        repository.move(groupId(), idOf("Channel 4"), up = true)
        repository.discard(groupId())

        assertEquals(before, titles())
        assertFalse(database.orderDao().has(OrderScopes.draft(groupId())))
    }

    @Test
    fun `cancelling a first arrangement leaves the provider's order`() = runBlocking {
        val repository = repository()
        repository.begin(groupId())
        repository.move(groupId(), idOf("Channel 2"), up = true)

        repository.discard(groupId())

        assertEquals(provider, titles())
        assertFalse(database.orderDao().has(OrderScopes.channels(groupId())))
    }

    @Test
    fun `an arrangement is begun from the saved order, not the provider's`() = runBlocking {
        val repository = repository()
        repository.begin(groupId())
        repository.move(groupId(), idOf("Channel 0"), up = false)
        repository.save(groupId())

        repository.begin(groupId())

        assertEquals(titles(), titles(arranging = true))
        assertEquals(listOf("Channel 1", "Channel 0", "Channel 2", "Channel 3", "Channel 4"), titles(arranging = true))
    }

    @Test
    fun `saving with nothing being arranged does not erase the saved order`() = runBlocking {
        val repository = repository()
        repository.begin(groupId())
        repository.move(groupId(), idOf("Channel 0"), up = false)
        repository.save(groupId())
        val saved = titles()

        repository.save(groupId())

        assertEquals(saved, titles())
    }

    @Test
    fun `the provider's order is never written`() = runBlocking {
        val before = providerOrders()
        val repository = repository()

        repository.begin(groupId())
        repository.move(groupId(), idOf("Channel 4"), up = true)
        repository.move(groupId(), idOf("Channel 0"), up = false)
        repository.save(groupId())
        repository.begin(groupId())
        repository.move(groupId(), idOf("Channel 2"), up = true)
        repository.discard(groupId())
        repository.saveBouquets(MediaKind.LIVE, listOf(groupId()))

        assertEquals(before, providerOrders())
        assertEquals(listOf(0, 1, 2, 3, 4), before)
    }

    @Test
    fun `a channel the provider adds later follows the arranged ones`() = runBlocking {
        val repository = repository()
        repository.begin(groupId())
        repository.move(groupId(), idOf("Channel 4"), up = true)
        repository.save(groupId())

        // A refresh: the same five, and a sixth the arrangement knows nothing of.
        Fixtures.import(database, Fixtures.livePlaylist(6) { "Sports" })

        assertEquals(
            listOf("Channel 0", "Channel 1", "Channel 2", "Channel 4", "Channel 3", "Channel 5"),
            titles(),
        )
    }

    @Test
    fun `a channel the provider removes leaves no gap to move into`() = runBlocking {
        val repository = repository()
        repository.begin(groupId())
        repository.save(groupId())

        // A refresh that no longer lists Channel 2.
        Fixtures.import(database, Fixtures.livePlaylist(5) { "Sports" }.filterNot { it.endsWith("/2.ts") || it.contains("Channel 2") })
        repository.begin(groupId())

        assertEquals(listOf("Channel 0", "Channel 1", "Channel 3", "Channel 4"), titles(arranging = true))
        assertTrue(repository.move(groupId(), idOf("Channel 3"), up = true))
        // Past Channel 1, not into the place Channel 2 left.
        assertEquals(listOf("Channel 0", "Channel 3", "Channel 1", "Channel 4"), titles(arranging = true))
    }

    @Test
    fun `an arrangement survives a refresh of the same channels`() = runBlocking {
        val repository = repository()
        repository.begin(groupId())
        repository.move(groupId(), idOf("Channel 3"), up = true)
        repository.save(groupId())
        val saved = titles()

        Fixtures.import(database, Fixtures.livePlaylist(5) { "Sports" })

        assertEquals(saved, titles())
    }

    @Test
    fun `a window of an arranged bouquet is the same list, in pieces`() = runBlocking {
        val repository = repository()
        repository.begin(groupId())
        repository.move(groupId(), idOf("Channel 4"), up = true)
        repository.move(groupId(), idOf("Channel 4"), up = true)
        repository.save(groupId())

        val whole = titles()
        val pieces = titles(offset = 0, limit = 2) + titles(offset = 2, limit = 2) + titles(offset = 4, limit = 2)

        assertEquals(whole, pieces)
    }

    @Test
    fun `one bouquet's arrangement does not move another's channels`() = runBlocking {
        Fixtures.import(database, Fixtures.livePlaylist(6) { if (it < 3) "Sports" else "News" })
        val groups = database.groupDao().groupsNow("LIVE")
        val sports = groups.first { it.name == "Sports" }.id
        val news = groups.first { it.name == "News" }.id
        val repository = repository()

        repository.begin(sports)
        val last = runBlocking { database.mediaDao().windowInGroup("LIVE", sports, 0, 10).last().id }
        repository.move(sports, last, up = true)
        repository.save(sports)

        assertEquals(
            listOf("Channel 3", "Channel 4", "Channel 5"),
            database.mediaDao().windowInGroup("LIVE", news, 0, 10).map { it.title },
        )
        assertFalse(database.orderDao().has(OrderScopes.channels(news)))
    }

    @Test
    fun `an arrangement is read only for the subscription it was made for`() = runBlocking {
        val repository = repository()
        repository.begin(groupId())
        repository.move(groupId(), idOf("Channel 4"), up = true)
        repository.save(groupId())

        Fixtures.import(database, Fixtures.livePlaylist(3) { "Sports" }, sourceId = "other")
        Fixtures.activate(database, "other")

        val other = database.groupDao().groupsNow("LIVE").single().id
        assertEquals(
            listOf("Channel 0", "Channel 1", "Channel 2"),
            database.mediaDao().windowInGroup("LIVE", other, 0, 10).map { it.title },
        )
        // Nor does it show through a bouquet it was never made for.
        assertFalse(database.orderDao().has(OrderScopes.channels(other)))
    }

    // ------------------------------------------------------------------------- abandoned drafts

    private fun draftRows(): Int = runBlocking {
        database.openHelper.writableDatabase
            .query("SELECT COUNT(*) FROM custom_order WHERE scope GLOB 'c:*#draft'")
            .use { c -> if (c.moveToFirst()) c.getInt(0) else -1 }
    }

    @Test
    fun `a draft left by an app that closed mid-arrangement is swept, and the saved order kept`() = runBlocking {
        val before = repository()
        before.begin(groupId())
        before.move(groupId(), idOf("Channel 3"), up = true)
        before.save(groupId())
        val saved = titles()
        // Arranging again, and the app is closed with the draft half made.
        before.begin(groupId())
        before.move(groupId(), idOf("Channel 0"), up = false)
        assertTrue(draftRows() > 0)

        // The next process: a new repository, and a board opened.
        repository().sweepDrafts()

        assertEquals(0, draftRows())
        assertEquals(saved, titles())
    }

    @Test
    fun `a sweep in a process that has begun an arrangement leaves its draft alone`() = runBlocking {
        val repository = repository()
        repository.begin(groupId())
        repository.move(groupId(), idOf("Channel 2"), up = true)

        repository.sweepDrafts()

        assertTrue(draftRows() > 0)
        assertEquals(listOf("Channel 0", "Channel 2", "Channel 1", "Channel 3", "Channel 4"), titles(arranging = true))
    }

    @Test
    fun `a sweep is done once a process, and again after an arrangement was abandoned`() = runBlocking {
        val repository = repository()
        val stale = { database.openHelper.writableDatabase.execSQL("INSERT OR REPLACE INTO custom_order VALUES ('c:gone#draft', 'x', 0)") }

        stale()
        repository.sweepDrafts()
        assertEquals(0, draftRows())

        stale()
        repository.sweepDrafts()
        assertEquals("already swept in this process", 1, draftRows())

        repository.draftAbandoned()
        repository.sweepDrafts()
        assertEquals(0, draftRows())
    }

    @Test
    fun `beginning one bouquet clears another's abandoned draft and no saved order`() = runBlocking {
        Fixtures.import(database, Fixtures.livePlaylist(6) { if (it < 3) "Sports" else "News" })
        val groups = database.groupDao().groupsNow("LIVE")
        val sports = groups.first { it.name == "Sports" }.id
        val news = groups.first { it.name == "News" }.id
        val repository = repository()
        repository.begin(sports)
        val last = database.mediaDao().windowInGroup("LIVE", sports, 0, 10).last().id
        repository.move(sports, last, up = true)
        repository.save(sports)
        repository.begin(sports)
        repository.move(sports, last, up = true)

        repository.begin(news)

        assertFalse("the sports draft is gone", database.orderDao().has(OrderScopes.draft(sports)))
        assertTrue("its saved order is not", database.orderDao().has(OrderScopes.channels(sports)))
        assertTrue(database.orderDao().has(OrderScopes.draft(news)))
    }

    @Test
    fun `a sweep touches no saved order of any list`() = runBlocking {
        val repository = repository()
        repository.begin(groupId())
        repository.move(groupId(), idOf("Channel 4"), up = true)
        repository.save(groupId())
        repository.saveBouquets(MediaKind.LIVE, listOf(groupId()))
        val rows = { database.openHelper.writableDatabase.query("SELECT scope, item_id, position FROM custom_order ORDER BY scope, item_id").use { c -> buildList { while (c.moveToNext()) add(Triple(c.getString(0), c.getString(1), c.getLong(2))) } } }
        val before = rows()

        repository().sweepDrafts()

        assertEquals(before, rows())
    }

    // ------------------------------------------------------------------------------ bouquets

    private fun twoBouquets(): Triple<String, String, String> {
        Fixtures.import(
            database,
            Fixtures.livePlaylist(9) {
                when {
                    it < 3 -> "Sports"
                    it < 6 -> "News"
                    else -> "Kids"
                }
            },
        )
        val groups = runBlocking { database.groupDao().groupsNow("LIVE") }
        return Triple(
            groups.first { it.name == "Sports" }.id,
            groups.first { it.name == "News" }.id,
            groups.first { it.name == "Kids" }.id,
        )
    }

    private fun bouquets(): List<String> =
        runBlocking { repository().groups(MediaKind.LIVE).first().map { it.name } }

    @Test
    fun `bouquets are in the provider's order until the viewer sets one`() {
        twoBouquets()

        assertEquals(listOf("Sports", "News", "Kids"), bouquets())
    }

    @Test
    fun `a saved order of bouquets is the order they are listed in`() = runBlocking {
        val (sports, news, kids) = twoBouquets()

        repository().saveBouquets(MediaKind.LIVE, listOf(kids, sports, news))

        assertEquals(listOf("Kids", "Sports", "News"), bouquets())
    }

    @Test
    fun `a bouquet the provider adds later follows the arranged ones`() = runBlocking {
        val (sports, news, kids) = twoBouquets()
        repository().saveBouquets(MediaKind.LIVE, listOf(news, kids, sports))

        Fixtures.import(
            database,
            Fixtures.livePlaylist(12) {
                when {
                    it < 3 -> "Sports"
                    it < 6 -> "News"
                    it < 9 -> "Kids"
                    else -> "Docs"
                }
            },
        )

        assertEquals(listOf("News", "Kids", "Sports", "Docs"), bouquets())
    }

    @Test
    fun `saving the bouquets again replaces the order and does not stack on it`() = runBlocking {
        val (sports, news, kids) = twoBouquets()
        repository().saveBouquets(MediaKind.LIVE, listOf(kids, sports, news))

        repository().saveBouquets(MediaKind.LIVE, listOf(news, kids, sports))

        assertEquals(listOf("News", "Kids", "Sports"), bouquets())
    }

    @Test
    fun `another subscription's bouquet order is not reached by a save`() = runBlocking {
        val (sports, news, kids) = twoBouquets()
        repository().saveBouquets(MediaKind.LIVE, listOf(kids, sports, news))

        Fixtures.import(database, Fixtures.livePlaylist(4) { if (it < 2) "Alpha" else "Beta" }, sourceId = "other")
        Fixtures.activate(database, "other")
        val other = runBlocking { database.groupDao().groupsNow("LIVE") }
        repository().saveBouquets(MediaKind.LIVE, other.map { it.id }.reversed())
        assertEquals(listOf("Beta", "Alpha"), bouquets())

        Fixtures.activate(database, "src")
        assertEquals(listOf("Kids", "Sports", "News"), bouquets())
    }

    @Test
    fun `the provider's order of the bouquets is never written`() = runBlocking {
        val (sports, news, kids) = twoBouquets()
        val before = database.groupDao().groupsNow("LIVE").map { it.id to it.providerOrder }

        repository().saveBouquets(MediaKind.LIVE, listOf(kids, news, sports))

        assertEquals(before, database.groupDao().groupsNow("LIVE").map { it.id to it.providerOrder })
    }

    // ------------------------------------------------------------------ the screens are told

    /**
     * A rail open while the bouquets are saved shows the new order without being opened again.
     *
     * On a device it kept the old one: the order was written, and the rail that read it was not
     * told. Collected here the way the board collects it, and waited on.
     */
    @Test
    fun `a rail that is open is told the bouquets' saved order`() = runBlocking {
        val (sports, news, kids) = twoBouquets()
        val repository = repository()
        val seen = Channel<List<String>>(Channel.UNLIMITED)
        val rail = launch(Dispatchers.IO) {
            repository.groups(MediaKind.LIVE).collect { groups -> seen.send(groups.map { it.name }) }
        }
        assertEquals(listOf("Sports", "News", "Kids"), withTimeout(TOLD) { seen.receive() })

        repository.saveBouquets(MediaKind.LIVE, listOf(kids, sports, news))

        withTimeout(TOLD) { while (seen.receive() != listOf("Kids", "Sports", "News")) continue }
        rail.cancel()
    }

    /**
     * A list open on a bouquet being arranged reads it again each time a channel moves.
     *
     * On a device the channel held stayed where it was on screen while the draft had it moving
     * -- the saved list showed every move once Save was pressed. A list is read again as a new
     * generation of its pages, so one is waited for after the move.
     */
    @Test
    fun `a list of a bouquet being arranged is read again when a channel moves`() = runBlocking {
        val repository = repository()
        val group = groupId()
        repository.begin(group)
        val generations = Channel<Unit>(Channel.UNLIMITED)
        val list = launch(Dispatchers.IO) {
            repository.items(CatalogQuery(MediaKind.LIVE, groupId = group, arranging = true))
                .collect { generations.send(Unit) }
        }
        withTimeout(TOLD) { generations.receive() }

        assertTrue(repository.move(group, idOf("Channel 3"), up = true))

        withTimeout(TOLD) { generations.receive() }
        list.cancel()
    }

    // ------------------------------------------------------------------------------ the query

    @Test
    fun `a query asks for the draft only when it says it is arranging`() {
        val plain = CatalogQuery(MediaKind.LIVE, groupId = "g")
        assertFalse(plain.arranging)
        assertTrue(plain.copy(arranging = true).arranging)
        assertFalse(plain == plain.copy(arranging = true))
    }

    // ------------------------------------------------------------------------------ the table

    @Test
    fun `the migration builds the table and the index the entity describes`() {
        val db = database.openHelper.writableDatabase
        val columns = { table: String ->
            db.query("PRAGMA table_info(`$table`)").use { c ->
                buildList {
                    while (c.moveToNext()) {
                        add(listOf(c.getString(1), c.getString(2), c.getInt(3), c.getInt(5)).joinToString("|"))
                    }
                }
            }
        }
        val indices = {
            db.query("PRAGMA index_list(`custom_order`)").use { c ->
                buildSet { while (c.moveToNext()) add(c.getString(1)) }
            }
        }
        val created = columns("custom_order")
        val createdIndices = indices()

        db.execSQL("DROP TABLE `custom_order`")
        CastivioMigrations.ALL.single { it.startVersion == 4 }.migrate(db)

        assertEquals(created, columns("custom_order"))
        assertTrue("idx_custom_order_position" in createdIndices)
        assertEquals(createdIndices, indices())
    }

    @Test
    fun `an order is user data, which no migration may drop`() {
        assertTrue("custom_order" in CastivioMigrations.USER_TABLES)
    }

    @Test
    fun `deleting a subscription keeps the order, as it keeps favourites`() = runBlocking {
        val repository = repository()
        repository.begin(groupId())
        repository.move(groupId(), idOf("Channel 4"), up = true)
        repository.save(groupId())
        val group = groupId()

        RoomSourceRepository(database.sourceDao()) { 1_000L }.delete("src")

        assertTrue(database.orderDao().has(OrderScopes.channels(group)))
    }

    private companion object {
        /** Long enough for a notice that is coming, short enough to fail on one that is not. */
        const val TOLD = 5_000L
    }
}
