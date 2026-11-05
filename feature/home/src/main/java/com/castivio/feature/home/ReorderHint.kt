package com.castivio.feature.home

/**
 * The one sentence the reorder band says. Never two: the band is a single line, and a line that has to hold everything that
 * could be said is a line that is read by nobody.
 *
 * The band used to stack every sentence that was true -- how to pick up, how to tick, that "all" has no channels to arrange --
 * five lines over a board that has room for seven channels. Each state has one that matters, and it is that one.
 */
internal sealed interface ReorderHint {

    /** The last save did not reach the database. Said before anything else, because the viewer has to act on it. */
    data object Failed : ReorderHint

    /** A row is held: the arrows move it, OK puts it down. */
    data object Move : ReorderHint

    /** [count] rows are ticked and none is held: OK on one of them takes them all. */
    data class Marked(val count: Int) : ReorderHint

    /** "All" is open and nothing is held or ticked: the channels of "all" have no order, so a category has to be opened. */
    data object AllOpen : ReorderHint

    /** A category is open and nothing is held or ticked: how to select one row, and how to select several. */
    data object Pick : ReorderHint
}

/**
 * What the band says in this state, most pressing first: a failed save, then what is in the viewer's hand, then what is ticked,
 * then what "all" cannot do, then how to begin.
 *
 * A held row wins over ticked ones because a group is moved by its arrows like a row ([Arranging.carried]); and ticked rows win
 * over "all is open" because the bouquets of "all" can still be ticked and moved, so the count is the useful word.
 */
internal fun Arranging.hint(): ReorderHint = when {
    failed -> ReorderHint.Failed
    held != null -> ReorderHint.Move
    marked.isNotEmpty() -> ReorderHint.Marked(marked.size)
    channelsOf == null -> ReorderHint.AllOpen
    else -> ReorderHint.Pick
}
