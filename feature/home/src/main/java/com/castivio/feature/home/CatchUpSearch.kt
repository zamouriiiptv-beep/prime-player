package com.castivio.feature.home

import com.castivio.domain.ArchiveGroup
import com.castivio.domain.searchTermOf
import kotlin.math.floor

/**
 * The categories whose name a search matches, in the order they were given.
 *
 * The rule the index applies to channels, applied to the few hundred categories a subscription has, which are in memory
 * already: every word of the search is the start of some word of the name, so `spo` finds `FR ❖ SPORTS`, `sport fr` finds
 * it too, and a capital, a mark or the order of two words changes nothing. Nothing searchable matches nothing, and the
 * caller does not ask: no search is the list of categories, not an empty one.
 *
 * @param term a [searchTermOf] result, the words as the index reads them.
 */
internal fun matchingGroups(groups: List<ArchiveGroup>, term: String): List<ArchiveGroup> {
    val words = term.split(' ').filter { it.isNotEmpty() }
    if (words.isEmpty()) return emptyList()
    return groups.filter { group ->
        val own = searchTermOf(group.name).split(' ')
        words.all { word -> own.any { it.startsWith(word) } }
    }
}

/**
 * How many columns the search's two sections have, and the grid that holds both.
 *
 * The categories are narrower than the channels, so a row of each has a different number of cells, and one grid has one
 * column count. The grid has [total] columns -- the product of the two -- so that a category spans [groupSpan] of them
 * and a channel [channelSpan], and each section's cards are exactly as wide as in a page of their own.
 */
internal data class SearchColumns(val groups: Int, val channels: Int) {

    val total: Int get() = groups * channels

    /** The grid's columns a category takes: as many as there are channel columns. */
    val groupSpan: Int get() = channels

    /** The grid's columns a channel takes: as many as there are category columns. */
    val channelSpan: Int get() = groups
}

/**
 * The columns the two sections of a search have in [widthDp], by the rule an adaptive grid has: as many cards of at least
 * the minimum width, with [gapDp] between them, as fit -- and always one.
 */
internal fun searchColumns(widthDp: Float, gapDp: Float, groupMinDp: Float, channelMinDp: Float): SearchColumns =
    SearchColumns(
        groups = columnsFor(widthDp, gapDp, groupMinDp),
        channels = columnsFor(widthDp, gapDp, channelMinDp),
    )

private fun columnsFor(widthDp: Float, gapDp: Float, minDp: Float): Int =
    floor((widthDp + gapDp) / (minDp + gapDp)).toInt().coerceAtLeast(1)

/**
 * The programmes whose title a search matches, in the order they were given: the rule [matchingGroups] applies to
 * categories, applied to a day's cards -- every word of the search is the start of some word of the title.
 *
 * @param term a [searchTermOf] result; nothing searchable matches nothing, and the caller does not ask.
 */
internal fun matchingCards(cards: List<CatchUpCard>, term: String): List<CatchUpCard> {
    val words = term.split(' ').filter { it.isNotEmpty() }
    if (words.isEmpty()) return emptyList()
    return cards.filter { card ->
        val own = searchTermOf(card.title).split(' ')
        words.all { word -> own.any { it.startsWith(word) } }
    }
}

/** How long a search waits for the viewer to pause: the channels' board's own, and the same in every box of Catch-up. */
internal const val CATCHUP_SEARCH_PAUSE_MS = 120L

/**
 * The search box of Catch-up against the channels' board's own: larger, because here it stands alone in a header -- but
 * only a third larger. It was 1.8 and, on the owner's phone, was a third of the screen's width, which is what `m.search`
 * (about a fifth of the width) times 1.8 comes to on every screen: the box is a share of the screen, not a size, so a
 * television draws it in the same proportion and no larger. At 1.35 it is about three tenths of the width and a
 * height that sits with the line of text beside it, and it is a tool and not the page's subject.
 */
internal const val CATCHUP_SEARCH_SCALE = 1.35f
