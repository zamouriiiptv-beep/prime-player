package com.castivio.feature.home

import org.junit.Assert.assertEquals
import org.junit.Test

/** Where a show's page begins each of its sections, and which the rail lights as the page is scrolled. */
class ShowSectionsTest {

    @Test
    fun `a page with only seasons has an overview and the seasons`() {
        val sections = showSections(hasSeasons = true, episodes = 6, hasCast = false, hasSimilar = false)

        assertEquals(listOf(SectionAt(ShowSection.Overview, 0), SectionAt(ShowSection.Seasons, 1)), sections)
    }

    @Test
    fun `the cast begins after the chips and every episode, and the similar shows after the cast`() {
        val sections = showSections(hasSeasons = true, episodes = 6, hasCast = true, hasSimilar = true)

        // 0 the top, 1 the chips, 2..7 the six episodes, 8 the cast, 9 the similar shows.
        assertEquals(
            listOf(
                SectionAt(ShowSection.Overview, 0),
                SectionAt(ShowSection.Seasons, 1),
                SectionAt(ShowSection.Cast, 8),
                SectionAt(ShowSection.Similar, 9),
            ),
            sections,
        )
    }

    @Test
    fun `a show with no seasons held has no seasons section, and the placeholder takes the item the chips would`() {
        val sections = showSections(hasSeasons = false, episodes = 0, hasCast = true, hasSimilar = false)

        assertEquals(listOf(SectionAt(ShowSection.Overview, 0), SectionAt(ShowSection.Cast, 2)), sections)
    }

    @Test
    fun `a section nothing was sent for is not in the page`() {
        val sections = showSections(hasSeasons = true, episodes = 3, hasCast = false, hasSimilar = true)

        assertEquals(listOf(ShowSection.Overview, ShowSection.Seasons, ShowSection.Similar), sections.map { it.section })
    }

    @Test
    fun `the rail lights the last section the page has reached`() {
        val sections = showSections(hasSeasons = true, episodes = 6, hasCast = true, hasSimilar = true)

        assertEquals(ShowSection.Overview, litSection(sections, firstVisible = 0, atEnd = false))
        assertEquals(ShowSection.Seasons, litSection(sections, firstVisible = 1, atEnd = false))
        assertEquals(ShowSection.Seasons, litSection(sections, firstVisible = 5, atEnd = false))
        assertEquals(ShowSection.Cast, litSection(sections, firstVisible = 8, atEnd = false))
    }

    @Test
    fun `a page scrolled as far as it goes lights the last section, even when that is too short to reach the top`() {
        val sections = showSections(hasSeasons = true, episodes = 6, hasCast = true, hasSimilar = true)

        assertEquals(ShowSection.Similar, litSection(sections, firstVisible = 8, atEnd = true))
    }

    @Test
    fun `a page that does not scroll is at its top, and lights the first section`() {
        val sections = showSections(hasSeasons = true, episodes = 2, hasCast = true, hasSimilar = true)

        assertEquals(ShowSection.Overview, litSection(sections, firstVisible = 0, atEnd = true))
    }
}
