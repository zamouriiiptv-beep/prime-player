package com.castivio.data.database

import com.castivio.domain.CatalogQuery
import com.castivio.domain.HiddenKind
import com.castivio.domain.MediaKind
import com.castivio.domain.step
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * What a viewer hides -- bouquets and channels, in bulk -- and what the lists then leave out: nothing is
 * deleted, the order is the provider's, and the channel keys do not stop on a channel that is not there.
 *
 * Five channels in two bouquets, in the order the playlist listed them:
 * Sport Alpha, News Beta, Sport Gamma, Sport Delta, News Epsilon.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HiddenItemsTest {

    private lateinit var database: CastivioDatabase

    private val playlist = listOf(
        "#EXTM3U",
        """#EXTINF:-1 group-title="Sports",Sport Alpha""", "http://host/live/u/p/1.ts",
        """#EXTINF:-1 group-title="News",News Beta""", "http://host/live/u/p/2.ts",
        """#EXTINF:-1 group-title="Sports",Sport Gamma""", "http://host/live/u/p/3.ts",
        """#EXTINF:-1 group-title="Sports",Sport Delta""", "http://host/live/u/p/4.ts",
        """#EXTINF:-1 group-title="News",News Epsilon""", "http://host/live/u/p/5.ts",
    )

    @Before
    fun setUp() {
        database = CastivioDatabase.inMemory(RuntimeEnvironment.getApplication())
        Fixtures.import(database, playlist)
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun hidden() = RoomHiddenItems(database.hiddenDao(), clock = { 1_000L })

    private fun repository() = RoomCatalogRepository(
        mediaDao = database.mediaDao(),
        groupDao = database.groupDao(),
        favoriteDao = database.favoriteDao(),
        progressDao = database.progressDao(),
        database = database,
    )

    /** Read from the table and not through the lists, which leave a hidden channel out. */
    private fun idOf(title: String) = database.openHelper.readableDatabase
        .query("SELECT id FROM media WHERE title = ?", arrayOf<Any>(title))
        .use { it.moveToFirst(); it.getString(0) }

    private fun groupId(name: String) =
        runBlocking { database.groupDao().groupsNow("LIVE").single { it.name == name }.id }

    private val whole = CatalogQuery(kind = MediaKind.LIVE)
    private fun sports() = CatalogQuery(kind = MediaKind.LIVE, groupId = groupId("Sports"))

    private fun titles(query: CatalogQuery) =
        runBlocking { repository().window(query, offset = 0, limit = 100).items.map { it.title } }

    private fun total(query: CatalogQuery) =
        runBlocking { repository().window(query, offset = 0, limit = 1).totalCount }

    private fun bouquets() = runBlocking { repository().groups(MediaKind.LIVE).first().map { it.name } }

    // ------------------------------------------------------------------------------- the storage

    @Test
    fun `a batch hides every item in it, and the counts say how many of each kind`() = runBlocking {
        hidden().hide(HiddenKind.CHANNEL, listOf(idOf("News Beta"), idOf("Sport Delta")))
        hidden().hide(HiddenKind.BOUQUET, listOf(groupId("Sports")))

        assertEquals(2, hidden().count(HiddenKind.CHANNEL).first())
        assertEquals(1, hidden().count(HiddenKind.BOUQUET).first())
    }

    @Test
    fun `an empty batch hides nothing, and hiding twice is hiding once`() = runBlocking {
        hidden().hide(HiddenKind.CHANNEL, emptyList())
        assertEquals(0, hidden().count(HiddenKind.CHANNEL).first())

        hidden().hide(HiddenKind.CHANNEL, listOf(idOf("News Beta"), idOf("News Beta")))
        hidden().hide(HiddenKind.CHANNEL, listOf(idOf("News Beta")))
        assertEquals(1, hidden().count(HiddenKind.CHANNEL).first())
    }

    @Test
    fun `a batch larger than one statement lands whole`() = runBlocking {
        val ids = (1..1_000).map { "gone-$it" }

        hidden().hide(HiddenKind.CHANNEL, ids)

        assertEquals(1_000, hidden().count(HiddenKind.CHANNEL).first())
    }

    // ------------------------------------------------------------------------------ the channels

    @Test
    fun `a hidden channel is in no list of channels`() {
        runBlocking { hidden().hide(HiddenKind.CHANNEL, listOf(idOf("News Beta"), idOf("Sport Delta"))) }

        assertEquals(listOf("Sport Alpha", "Sport Gamma", "News Epsilon"), titles(whole))
        assertEquals(listOf("Sport Alpha", "Sport Gamma"), titles(sports()))
    }

    @Test
    fun `the lists say how many channels are left, so the channel keys go round the ones that are`() = runBlocking {
        hidden().hide(HiddenKind.CHANNEL, listOf(idOf("News Beta"), idOf("Sport Delta")))

        assertEquals(3, total(whole))
        assertEquals("News Epsilon", repository().step(whole, idOf("Sport Gamma"), 1)?.channel?.title)
        assertEquals("Sport Alpha", repository().step(whole, idOf("News Epsilon"), 1)?.channel?.title)
        assertEquals("News Epsilon", repository().step(whole, idOf("Sport Alpha"), -1)?.channel?.title)
    }

    @Test
    fun `a hidden channel is not found by a search, and is not a place in the list`() = runBlocking {
        hidden().hide(HiddenKind.CHANNEL, listOf(idOf("Sport Gamma")))
        val sport = CatalogQuery(kind = MediaKind.LIVE, search = "sport")

        assertEquals(listOf("Sport Alpha", "Sport Delta"), titles(sport))
        assertNull(repository().rank(whole, idOf("Sport Gamma")))
    }

    @Test
    fun `a bouquet's rail count is what the viewer can see`() = runBlocking {
        hidden().hide(HiddenKind.CHANNEL, listOf(idOf("Sport Gamma")))

        val counts = repository().groups(MediaKind.LIVE).first().associate { it.name to it.itemCount }

        assertEquals(2, counts["Sports"])
        assertEquals(2, counts["News"])
    }

    // ------------------------------------------------------------------------------ the bouquets

    @Test
    fun `a hidden bouquet leaves the rail and takes its channels out of every list`() {
        runBlocking { hidden().hide(HiddenKind.BOUQUET, listOf(groupId("Sports"))) }

        assertEquals(listOf("News"), bouquets())
        assertEquals(listOf("News Beta", "News Epsilon"), titles(whole))
        assertEquals(0, total(sports()))
    }

    private fun rows(table: String) = database.openHelper.readableDatabase
        .query("SELECT COUNT(*) FROM $table")
        .use { it.moveToFirst(); it.getInt(0) }

    @Test
    fun `hiding changes no row of the catalogue`() {
        val media = rows("media")
        val groups = rows("media_group")

        runBlocking {
            hidden().hide(HiddenKind.BOUQUET, listOf(groupId("Sports")))
            hidden().hide(HiddenKind.CHANNEL, listOf(idOf("News Beta")))
        }

        assertEquals(5, media)
        assertEquals(media, rows("media"))
        assertEquals(groups, rows("media_group"))
    }

    // ------------------------------------------------------------------------------- the way back

    @Test
    fun `restoring brings back every item of its kind and no other`() = runBlocking {
        hidden().hide(HiddenKind.BOUQUET, listOf(groupId("Sports")))
        hidden().hide(HiddenKind.CHANNEL, listOf(idOf("News Beta")))

        hidden().restoreAll(HiddenKind.BOUQUET)

        assertEquals(listOf("Sports", "News"), bouquets())
        assertEquals(listOf("Sport Alpha", "Sport Gamma", "Sport Delta", "News Epsilon"), titles(whole))
        assertEquals(0, hidden().count(HiddenKind.BOUQUET).first())
        assertEquals(1, hidden().count(HiddenKind.CHANNEL).first())

        hidden().restoreAll(HiddenKind.CHANNEL)

        assertEquals(listOf("Sport Alpha", "News Beta", "Sport Gamma", "Sport Delta", "News Epsilon"), titles(whole))
    }

    @Test
    fun `what is hidden stays hidden after the subscription is refreshed`() = runBlocking {
        hidden().hide(HiddenKind.BOUQUET, listOf(groupId("News")))
        hidden().hide(HiddenKind.CHANNEL, listOf(idOf("Sport Gamma")))

        Fixtures.import(database, playlist, now = 2_000L)

        assertEquals(listOf("Sport Alpha", "Sport Delta"), titles(whole))
        assertEquals(listOf("Sports"), bouquets())
    }

    @Test
    fun `hiding is in the tables a migration may never drop`() {
        assertEquals(true, "hidden_item" in CastivioMigrations.USER_TABLES)
    }

    // ----------------------------------------------------------------------------------- the counts
    //
    // What Home's Live TV card and the board draw: the channels the viewer can open, from the indexed count
    // less what was hidden -- and always the number of rows the list itself has.

    private fun sectionCount() = runBlocking { repository().count(MediaKind.LIVE).first() }

    private fun bouquetCount(name: String) = runBlocking { repository().count(MediaKind.LIVE, groupId(name)).first() }

    @Test
    fun `with nothing hidden the counts are the whole section and each bouquet, as before`() {
        assertEquals(5, sectionCount())
        assertEquals(3, bouquetCount("Sports"))
        assertEquals(2, bouquetCount("News"))
    }

    @Test
    fun `a count leaves out the channels hidden one by one`() {
        runBlocking { hidden().hide(HiddenKind.CHANNEL, listOf(idOf("News Beta"), idOf("Sport Delta"))) }

        assertEquals(3, sectionCount())
        assertEquals(2, bouquetCount("Sports"))
        assertEquals(1, bouquetCount("News"))
    }

    @Test
    fun `a count leaves out the whole of a hidden bouquet, and the bouquet itself counts nothing`() {
        runBlocking { hidden().hide(HiddenKind.BOUQUET, listOf(groupId("Sports"))) }

        assertEquals(2, sectionCount())
        assertEquals(0, bouquetCount("Sports"))
        assertEquals(2, bouquetCount("News"))
    }

    @Test
    fun `a channel hidden inside a hidden bouquet is counted out once`() {
        runBlocking {
            hidden().hide(HiddenKind.BOUQUET, listOf(groupId("Sports")))
            hidden().hide(HiddenKind.CHANNEL, listOf(idOf("Sport Alpha"), idOf("News Beta")))
        }

        // Sports, all three, is out; News Beta is out of News: 5 - 3 - 1, and not 5 - 3 - 2.
        assertEquals(1, sectionCount())
        assertEquals(1, bouquetCount("News"))
    }

    @Test
    fun `the counts and the lists agree about what is left, whatever is hidden`() {
        runBlocking {
            hidden().hide(HiddenKind.BOUQUET, listOf(groupId("News")))
            hidden().hide(HiddenKind.CHANNEL, listOf(idOf("Sport Gamma"), idOf("News Beta")))
        }

        assertEquals(titles(whole).size, sectionCount())
        assertEquals(total(whole), sectionCount())
        assertEquals(titles(sports()).size, bouquetCount("Sports"))
        assertEquals(total(sports()), bouquetCount("Sports"))
    }

    @Test
    fun `the counts come back with what was hidden`() {
        runBlocking {
            hidden().hide(HiddenKind.BOUQUET, listOf(groupId("News")))
            hidden().hide(HiddenKind.CHANNEL, listOf(idOf("Sport Gamma")))
        }
        assertEquals(2, sectionCount())

        runBlocking {
            hidden().restoreAll(HiddenKind.BOUQUET)
            hidden().restoreAll(HiddenKind.CHANNEL)
        }

        assertEquals(5, sectionCount())
        assertEquals(3, bouquetCount("Sports"))
        assertEquals(2, bouquetCount("News"))
    }

    @Test
    fun `a hidden id the catalogue does not have is not taken off the count`() {
        runBlocking { hidden().hide(HiddenKind.CHANNEL, listOf("not-a-channel-of-this-subscription")) }

        assertEquals(5, sectionCount())
    }

    // ------------------------------------------------------------ the lists are told, not left to Room

    @Test
    fun `every hide and every restore tells the catalogue, so its lists read again`() = runBlocking {
        var told = 0
        val hidden = RoomHiddenItems(database.hiddenDao(), clock = { 1_000L }, onChanged = { told++ })

        hidden.hide(HiddenKind.CHANNEL, listOf(idOf("Sport Gamma")))
        hidden.hide(HiddenKind.BOUQUET, listOf(groupId("News")))
        hidden.restoreAll(HiddenKind.CHANNEL)

        assertEquals(3, told)
    }

    @Test
    fun `nothing to hide is no write, and tells nobody`() = runBlocking {
        var told = 0
        val hidden = RoomHiddenItems(database.hiddenDao(), clock = { 1_000L }, onChanged = { told++ })

        hidden.hide(HiddenKind.CHANNEL, emptyList())

        assertEquals(0, told)
    }

    @Test
    fun `a channel hidden through the module's wiring is gone from its bouquet's list, and only it`() = runBlocking {
        val catalogue = repository()
        val hidden = RoomHiddenItems(database.hiddenDao(), clock = { 1_000L }, onChanged = catalogue::hiddenChanged)

        hidden.hide(HiddenKind.CHANNEL, listOf(idOf("Sport Gamma")))

        assertEquals(
            listOf("Sport Alpha", "Sport Delta"),
            catalogue.window(sports(), offset = 0, limit = 100).items.map { it.title },
        )
        assertEquals(listOf("Sports", "News"), catalogue.groups(MediaKind.LIVE).first().map { it.name })
    }

    @Test
    fun `a bouquet hidden through the module's wiring takes every one of its channels with it`() = runBlocking {
        val catalogue = repository()
        val hidden = RoomHiddenItems(database.hiddenDao(), clock = { 1_000L }, onChanged = catalogue::hiddenChanged)

        hidden.hide(HiddenKind.BOUQUET, listOf(groupId("Sports")))

        assertEquals(
            listOf("News Beta", "News Epsilon"),
            catalogue.window(whole, offset = 0, limit = 100).items.map { it.title },
        )
        assertEquals(listOf("News"), catalogue.groups(MediaKind.LIVE).first().map { it.name })
    }

    @Test
    fun `the count of what is hidden is read again after each write`() = runBlocking {
        val hidden = hidden()

        hidden.hide(HiddenKind.CHANNEL, listOf(idOf("Sport Gamma"), idOf("News Beta")))

        assertEquals(2, hidden.count(HiddenKind.CHANNEL).first())
        hidden.restoreAll(HiddenKind.CHANNEL)
        assertEquals(0, hidden.count(HiddenKind.CHANNEL).first())
    }

    // ------------------------------------------------------------- shown gone at once, written behind

    @Test
    fun `what is hidden is in the recent set from the press, and stays when it is written`() = runBlocking {
        val hidden = hidden()

        hidden.hide(HiddenKind.CHANNEL, listOf(idOf("Sport Gamma")))
        hidden.hide(HiddenKind.BOUQUET, listOf(groupId("News")))

        assertEquals(setOf(idOf("Sport Gamma")), hidden.recent.value.channels)
        assertEquals(setOf(groupId("News")), hidden.recent.value.bouquets)
    }

    @Test
    fun `a restore takes its kind out of the recent set, and leaves the other`() = runBlocking {
        val hidden = hidden()
        hidden.hide(HiddenKind.CHANNEL, listOf(idOf("Sport Gamma")))
        hidden.hide(HiddenKind.BOUQUET, listOf(groupId("News")))

        hidden.restoreAll(HiddenKind.CHANNEL)

        assertEquals(emptySet<String>(), hidden.recent.value.channels)
        assertEquals(setOf(groupId("News")), hidden.recent.value.bouquets)
    }

    @Test
    fun `a write that fails gives back what it was to hide`() = runBlocking {
        val broken = object : com.castivio.data.database.dao.HiddenDao {
            override suspend fun addAll(items: List<com.castivio.data.database.entity.HiddenEntity>) {
                error("disk full")
            }
            override suspend fun clear(type: String) = Unit
            override fun count(type: String) = kotlinx.coroutines.flow.flowOf(0)
            override fun channelsByGroup() =
                kotlinx.coroutines.flow.flowOf(emptyList<com.castivio.data.database.dao.HiddenInGroup>())
        }
        var told = 0
        val hidden = RoomHiddenItems(broken, clock = { 1_000L }, onChanged = { told++ })

        val failed = runCatching { hidden.hide(HiddenKind.CHANNEL, listOf("c1")) }

        assertEquals(true, failed.isFailure)
        assertEquals(emptySet<String>(), hidden.recent.value.channels)
        assertEquals("nothing was written, so no list is told", 0, told)
    }
}
