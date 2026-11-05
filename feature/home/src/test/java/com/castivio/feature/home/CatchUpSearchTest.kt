package com.castivio.feature.home

import com.castivio.domain.ArchiveGroup
import com.castivio.domain.CatchUpProgramme
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What Catch-up's search box finds among the categories, and the columns its two sections share: both pure, because the
 * first is a rule the viewer learns by using it and the second is arithmetic a grid would hide.
 */
class CatchUpSearchTest {

    private fun group(name: String, id: String = name) = ArchiveGroup(id = id, name = name, channels = 1)

    private val groups = listOf(
        group("FR ❖ SPORTS"),
        group("FR ❖ CINÉMA"),
        group("NL ❖ SPORT"),
        group("UK ❖ SPORTS"),
        group("DE ❖ ALLGEMEIN"),
        group("BE ❖ SPORT & FUN"),
    )

    private fun names(term: String) = matchingGroups(groups, term).map { it.name }

    // ----------------------------------------------------------------------------------- the categories

    @Test
    fun `a word is the start of a word of the name`() {
        assertEquals(listOf("FR ❖ SPORTS", "NL ❖ SPORT", "UK ❖ SPORTS", "BE ❖ SPORT & FUN"), names("spo"))
        assertEquals(listOf("FR ❖ CINÉMA"), names("cin"))
    }

    @Test
    fun `every word has to be found, in any order`() {
        assertEquals(listOf("FR ❖ SPORTS"), names("sports fr"))
        assertEquals(listOf("BE ❖ SPORT & FUN"), names("fun sport"))
        assertEquals(emptyList<String>(), names("sport cin"))
    }

    @Test
    fun `the middle of a word is not the start of one`() {
        assertEquals(emptyList<String>(), names("ports"))
    }

    @Test
    fun `nothing searchable finds nothing, in the order the categories were given otherwise`() {
        assertEquals(emptyList<String>(), names(""))
        assertEquals(listOf("FR ❖ SPORTS", "NL ❖ SPORT", "UK ❖ SPORTS", "BE ❖ SPORT & FUN"), names("sport"))
    }

    @Test
    fun `a category whose name is in another script is found by its own letters`() {
        val others = listOf(group("ما فاتك"), group("Новости"))
        assertEquals(listOf("Новости"), matchingGroups(others, "нов").map { it.name })
        assertEquals(listOf("ما فاتك"), matchingGroups(others, "فات").map { it.name })
    }

    // ------------------------------------------------------------------------------------ the programmes

    private fun card(title: String, start: Long = 0L) = CatchUpCard(
        programme = CatchUpProgramme(startMs = start, stopMs = start + 1, title = title, description = null, locator = "L$start$title"),
        title = title,
        timeText = "00:00 – 00:01",
    )

    private val day = listOf(
        card("ICI 19/20 édition toutes régions", 1),
        card("Le journal des talents", 2),
        card("Journal Météo Climat", 3),
        card("Tandem", 4),
        card("Tandem", 5),
        card("-", 6),
    )

    private fun titles(term: String) = matchingCards(day, term).map { it.programme.startMs }

    @Test
    fun `a programme is found by the start of a word of its title, in the order it was listed`() {
        assertEquals(listOf(2L, 3L), titles("jour"))
        assertEquals(listOf(4L, 5L), titles("tan"))
    }

    @Test
    fun `every word has to be found in the title, in any order`() {
        assertEquals(listOf(3L), titles("climat journal"))
        assertEquals(emptyList<Long>(), titles("tandem journal"))
    }

    @Test
    fun `nothing searchable finds no programme, and a title with nothing in it is never found`() {
        assertEquals(emptyList<Long>(), titles(""))
        assertEquals(emptyList<Long>(), titles("-"))
    }

    // --------------------------------------------------------------------------------------- the columns

    @Test
    fun `each section has the columns an adaptive grid of its own would`() {
        // 770dp wide with 6dp between: floor((770+6)/(150+6)) = 4 categories, floor((770+6)/(220+6)) = 3 channels.
        val c = searchColumns(widthDp = 770f, gapDp = 6f, groupMinDp = 150f, channelMinDp = 220f)
        assertEquals(4, c.groups)
        assertEquals(3, c.channels)
    }

    @Test
    fun `a handset's board has four categories in a row, where five cut their names`() {
        // The board's inside is 770dp on a handset, with 4dp between the cards (the screenshot that asked for it): 150 gave 5, 184 gives 4.
        val now = searchColumns(widthDp = 770f, gapDp = 4f, groupMinDp = GROUP_MIN_WIDTH.value, channelMinDp = CHANNEL_MIN_WIDTH.value)
        val before = searchColumns(widthDp = 770f, gapDp = 4f, groupMinDp = 150f, channelMinDp = CHANNEL_MIN_WIDTH.value)

        assertEquals(4, now.groups)
        assertEquals("the old minimum drew five", 5, before.groups)
    }

    @Test
    fun `a television's board has no more than four, and a narrow handset fewer`() {
        val tv = searchColumns(widthDp = 900f, gapDp = 11f, groupMinDp = GROUP_MIN_WIDTH.value, channelMinDp = CHANNEL_MIN_WIDTH.value)
        val narrow = searchColumns(widthDp = 540f, gapDp = 4f, groupMinDp = GROUP_MIN_WIDTH.value, channelMinDp = CHANNEL_MIN_WIDTH.value)

        assertEquals(4, tv.groups)
        assertEquals(2, narrow.groups)
    }

    @Test
    fun `the shared grid has a column for every pair, and each card takes the other section's count`() {
        val c = SearchColumns(groups = 5, channels = 3)
        assertEquals(15, c.total)
        assertEquals(3, c.groupSpan)
        assertEquals(5, c.channelSpan)
        // Five categories of three columns and three channels of five fill the same fifteen.
        assertEquals(c.total, c.groups * c.groupSpan)
        assertEquals(c.total, c.channels * c.channelSpan)
    }

    @Test
    fun `a narrow screen has one column of each, and never none`() {
        val c = searchColumns(widthDp = 120f, gapDp = 6f, groupMinDp = 150f, channelMinDp = 220f)
        assertEquals(SearchColumns(groups = 1, channels = 1), c)
        assertEquals(1, c.total)
    }
}
