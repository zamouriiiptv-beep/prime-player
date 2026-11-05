package com.castivio.feature.home

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.text.TextLayoutResult

/**
 * How many lines the text of this node was laid out on.
 *
 * A claim about one line is a claim about lines, and it is asked of the layout that made them. It was asked of the node's
 * height, as "under twice the type size", and Robolectric answers every `Text` with 35dp whatever its style -- see
 * `SourceChoiceBudgetTest` -- so the height of a line there is not the height of a line anywhere else, and a threshold on it is
 * a claim about the harness. The line count is the same on a device and in a test.
 */
internal fun SemanticsNodeInteraction.lineCount(): Int {
    val layouts = mutableListOf<TextLayoutResult>()
    fetchSemanticsNode().config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
    check(layouts.isNotEmpty()) { "the node has no text layout to count the lines of" }
    return layouts.first().lineCount
}
