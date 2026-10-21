package com.castivio.feature.home

import com.castivio.core.common.AppError
import com.castivio.domain.Channel
import com.castivio.domain.Episode
import com.castivio.domain.MediaGroup
import com.castivio.domain.MediaKind
import com.castivio.domain.Movie
import com.castivio.domain.SectionLoad
import com.castivio.domain.SectionRun
import com.castivio.domain.Series
import com.castivio.domain.SortOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules the browse screens are built on, proved without a device.
 *
 * All three of them are the kind that only fail on somebody else's provider: a category
 * that disappeared overnight, an episode the provider did not number, a show pressed
 * where a film was expected. None of them can be found by looking at a screen with a
 * healthy catalogue behind it, which is exactly why they are asserted here.
 */
class CatalogTest {

    private val groups = listOf(
        MediaGroup(id = "sports", name = "Sports", kind = MediaKind.LIVE),
        MediaGroup(id = "news", name = "News", kind = MediaKind.LIVE),
    )

    // ------------------------------------------------------- the surviving category

    @Test
    fun `a category that still exists stays selected`() {
        assertEquals("sports", surviving("sports", groups))
    }

    /**
     * The defect this rule exists for.
     *
     * Providers rename and drop categories between imports, and the selection is stored
     * by id. Kept, a stale id leaves the pane with nothing highlighted and the content
     * pane querying a group that is gone — an empty screen with no explanation and no
     * way out except reinstalling.
     */
    @Test
    fun `a category that was dropped in a re-import falls back to all`() {
        assertNull(surviving("sports", listOf(groups[1])))
    }

    @Test
    fun `all is a selection and survives anything`() {
        assertNull(surviving(null, groups))
        assertNull(surviving(null, emptyList()))
    }

    @Test
    fun `every category is stale when the import has not run`() {
        assertNull(surviving("sports", emptyList()))
    }

    // ------------------------------------------------------------ the listed categories

    /**
     * The rail lists a category once it has a count: complete, while the section arrives,
     * in the provider's order -- and a category with nothing in it never, since it is a row
     * that opens onto nothing.
     */
    @Test
    fun `the rail lists the categories with something in them, in their order`() {
        val arriving = listOf(
            MediaGroup(id = "sports", name = "Sports", kind = MediaKind.LIVE, itemCount = 117),
            MediaGroup(id = "news", name = "News", kind = MediaKind.LIVE, itemCount = 0),
            MediaGroup(id = "kids", name = "Kids", kind = MediaKind.LIVE, itemCount = 26),
        )

        assertEquals(listOf("sports", "kids"), listedOf(arriving).map { it.id })
        assertTrue("nothing complete yet lists nothing", listedOf(groups).isEmpty())
    }

    // ------------------------------------------------------ "lists no categories"

    private val listedOne = listOf(
        MediaGroup(id = "sports", name = "Sports", kind = MediaKind.LIVE, itemCount = 117),
    )
    private val loading = SectionLoad.Loading(items = 0, groups = 0)
    private val failed = SectionLoad.Failed(AppError.NETWORK_UNAVAILABLE, retryable = true)

    @Test
    fun `the rail says the playlist lists no categories when none is listed and none is coming`() {
        assertTrue(saysNoCategories(emptyList(), SectionLoad.Done(items = 840)))
        assertTrue(saysNoCategories(emptyList(), SectionLoad.Ready))
        assertTrue("nothing asked of the section since the app started", saysNoCategories(emptyList(), null))

        assertFalse(saysNoCategories(listedOne, SectionLoad.Done(items = 840)))
        assertFalse(saysNoCategories(listedOne, null))
    }

    /**
     * The defect this rule exists for.
     *
     * A category joins the rail when it is complete, so a reload that has completed none yet
     * lists none. The sentence used to be held back only for a section's first load, and a
     * refresh of a section that listed nothing -- or one still waiting behind another
     * section's load -- said "This playlist lists no categories" over the load that was
     * bringing them.
     */
    @Test
    fun `a reload that has listed nothing yet does not say the playlist lists none`() {
        assertFalse("waiting its turn", saysNoCategories(emptyList(), SectionLoad.Queued))
        assertFalse("arriving, nothing complete", saysNoCategories(emptyList(), loading))
        assertFalse(
            "arriving with rows, nothing complete",
            saysNoCategories(emptyList(), SectionLoad.Loading(items = 3_400, groups = 41, listed = 0)),
        )
    }

    @Test
    fun `a load that failed says its failure rather than that there are no categories`() {
        assertFalse(saysNoCategories(emptyList(), failed))
    }

    @Test
    fun `a first load is one that was asked as first and is still waiting or arriving`() {
        assertTrue(SectionRun(SectionLoad.Queued, forced = false, elapsedMs = null, first = true).isFirstLoad())
        assertTrue(SectionRun(loading, forced = false, elapsedMs = 400, first = true).isFirstLoad())

        assertFalse("it ended", SectionRun(SectionLoad.Done(840), forced = false, elapsedMs = 9_000, first = true).isFirstLoad())
        assertFalse("it failed", SectionRun(failed, forced = false, elapsedMs = 9_000, first = true).isFirstLoad())
        assertFalse("a reload over rows", SectionRun(loading, forced = true, elapsedMs = 400, first = false).isFirstLoad())
        assertFalse("nothing asked", (null as SectionRun?).isFirstLoad())
    }

    // ------------------------------------------------------------- episode numbering

    @Test
    fun `a fully numbered episode reads as season and episode`() {
        assertEquals("S01E04", episodeLabel(1, 4))
        assertEquals("S12E138", episodeLabel(12, 138))
    }

    /**
     * Zero means "the provider did not say", which the import writes rather than
     * dropping the row. It must never be shown as `S00`.
     */
    @Test
    fun `an unnumbered half is left out rather than shown as zero`() {
        assertEquals("E04", episodeLabel(0, 4))
        assertEquals("S02", episodeLabel(2, 0))
    }

    @Test
    fun `an episode numbered by neither gets no label at all`() {
        assertNull(episodeLabel(0, 0))
    }

    // ------------------------------------------------------------------- what plays

    @Test
    fun `a channel carries everything the player needs and nothing it would fetch`() {
        val channel = Channel(
            id = "c1",
            title = "Sky Documentaries",
            artworkUrl = "http://example.invalid/logo.png",
            number = 128,
            groupId = "docs",
            streamUrl = "http://example.invalid/live/u/p/128.ts",
            epgChannelId = "sky.docs",
            catchUpHours = 72,
        )

        val selection = channel.asSelection()

        requireNotNull(selection)
        assertEquals("http://example.invalid/live/u/p/128.ts", selection.url)
        assertEquals("Sky Documentaries", selection.title)
        assertTrue(selection.live)
        assertEquals("128", selection.channelNumber)
        assertEquals("sky.docs", selection.epgChannelId)
        assertEquals(72, selection.catchUpHours)
    }

    /** Catch-up is null rather than zero when the provider does not offer it, so no
     *  rewind control is drawn at all. */
    @Test
    fun `a channel without catch-up says so with a null rather than a zero`() {
        val channel = Channel(
            id = "c2",
            title = "News 24",
            artworkUrl = null,
            number = null,
            groupId = null,
            streamUrl = "http://example.invalid/live/u/p/9.ts",
            epgChannelId = null,
        )

        val selection = channel.asSelection()

        requireNotNull(selection)
        assertNull(selection.catchUpHours)
        assertNull(selection.channelNumber)
    }

    @Test
    fun `a film is not live and carries its year`() {
        val movie = Movie(
            id = "m1",
            title = "Pursuit",
            artworkUrl = null,
            streamUrl = "http://example.invalid/movie/u/p/77.mp4",
            year = 2022,
        )

        val selection = movie.asSelection()

        requireNotNull(selection)
        assertEquals(false, selection.live)
        assertEquals("2022", selection.subtitle)
        assertNull(selection.epgChannelId)
    }

    @Test
    fun `an episode carries its numbering as its subtitle`() {
        val episode = Episode(
            id = "e1",
            title = "The Bells",
            artworkUrl = null,
            streamUrl = "http://example.invalid/series/u/p/3.mp4",
            seasonNumber = 8,
            episodeNumber = 5,
        )

        val selection = episode.asSelection()

        requireNotNull(selection)
        assertEquals("S08E05", selection.subtitle)
        assertEquals(false, selection.live)
    }

    /**
     * A show is not a stream, and this is where that stops being a convention.
     *
     * Null is the only answer that cannot be handed to the engine by accident. A
     * `Series` has no `streamUrl` to give, so anything that returned a request here
     * would have had to invent one.
     */
    @Test
    fun `a show is not playable`() {
        val show = Series(id = "s1", title = "Chernobyl", artworkUrl = null)

        assertNull(show.asSelection())
    }

    // ------------------------------------------------------------- the sort cycle

    /**
     * Every order is reachable, and pressing the chip enough times gets back.
     *
     * A cycling control has exactly one way to be wrong, and it is not a typo in a
     * label: a branch that maps two values to the same successor silently strands one
     * of them, and the user finds out by pressing a button four times and never seeing
     * the order they wanted. That is invisible in review and trivial to assert.
     */
    @Test
    fun `the sort cycle reaches every order and returns to where it started`() {
        val start = SortOrder.PROVIDER
        val visited = mutableListOf(start)
        var current = start
        repeat(SortOrder.entries.size - 1) {
            current = current.next()
            visited += current
        }

        assertEquals(SortOrder.entries.toSet(), visited.toSet())
        assertEquals(SortOrder.entries.size, visited.distinct().size)
        assertEquals(start, current.next())
    }
}
