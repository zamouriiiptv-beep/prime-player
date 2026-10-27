package com.castivio.feature.home

/** The parts of a show's page the rail takes the viewer to, in the order the page holds them. */
internal enum class ShowSection { Overview, Seasons, Cast, Similar }

/** A section, and the index of the page's list item it begins at. */
internal data class SectionAt(val section: ShowSection, val index: Int)

/**
 * The sections a page has, and where each begins.
 *
 * The page is one list: the top (the poster, the facts, the buttons), then the season chips with the chosen season's
 * [episodes] under them -- or one placeholder where there are no seasons to show -- then the cast, then the similar
 * shows. A section the provider has told nothing about is not in the list, so it is not in the rail either.
 */
internal fun showSections(hasSeasons: Boolean, episodes: Int, hasCast: Boolean, hasSimilar: Boolean): List<SectionAt> {
    val sections = mutableListOf(SectionAt(ShowSection.Overview, 0))
    var next = 1
    if (hasSeasons) {
        sections += SectionAt(ShowSection.Seasons, next)
        // The chips, then each of the episodes.
        next += 1 + episodes
    } else {
        next += 1
    }
    if (hasCast) {
        sections += SectionAt(ShowSection.Cast, next)
        next += 1
    }
    if (hasSimilar) sections += SectionAt(ShowSection.Similar, next)
    return sections
}

/**
 * The section the rail lights: the last one whose first item the page has reached.
 *
 * A short last section never reaches the top of the screen, so a page scrolled as far as it goes lights the last
 * section -- which is where the viewer has got to. A page that does not scroll at all is at its top, and lights the
 * first.
 */
internal fun litSection(sections: List<SectionAt>, firstVisible: Int, atEnd: Boolean): ShowSection {
    if (atEnd && firstVisible > 0) return sections.last().section
    return sections.lastOrNull { it.index <= firstVisible }?.section ?: ShowSection.Overview
}
