package com.castivio.core.design.theme

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.platform.LocalLayoutDirection

/**
 * The stage's margins: the frame's, or what the system takes at the sides, whichever
 * is larger.
 *
 * ## The fault this ends, measured off a real handset
 *
 * A 2340×1080 phone in landscape, running the shipped build. The frame says
 * `edge = 32dp` on both sides. What the photograph measures is **69dp on the left and
 * 33 on the right** — an outer margin twice as wide on one side as the other, on a
 * screen whose whole subject is four cards in a grid.
 *
 * Neither number was wrong on its own. The surface applied `safeDrawing` — the union of
 * the system bars and the display cutout — and the screen then applied `edge` *inside*
 * it. The handset's cutout is 37dp, so the leading margin came out **37 + 32**. The two
 * were stacking, and nothing said they should not, because nothing had said what a
 * margin token means when the system already demands one.
 *
 * ## What a margin token means
 *
 * A **floor**, not an addition. `edge = 32` says *no content closer than 32dp to the
 * display's edge*; a 37dp cutout already satisfies that, and asking for 32 more is
 * asking twice. So each side takes `max(frame, inset)`:
 *
 * | side | frame | cutout | was | now |
 * |---|---|---|---|---|
 * | leading | 32 | 37 | 69 | 37 |
 * | trailing | 32 | 0 | 32 | 32 |
 *
 * Five dp of difference across an 851dp screen is nothing; thirty-seven is the
 * composition sitting visibly off centre. And content still never enters the cutout,
 * because the margin is never *below* the inset either — that is the half of `max` that
 * `safeDrawing` was there for, and it is kept.
 *
 * ## Only the sides
 *
 * The vertical insets are still paid by the surface, above this, and that is deliberate:
 * the frame is chosen from the height the composition actually has, and every budget in
 * the project is written against that measurement. Paying them here instead would move
 * the frame threshold and silently rewrite what the budget tests are asserting.
 *
 * A cutout at the side says nothing about how much composition fits down the screen, so
 * the two halves belong in different places.
 *
 * ## Why a modifier and not seven paddings
 *
 * Seven screens wrote `padding(start = edge, end = edge, top = stageTop, …)` by hand.
 * Seven copies of a rule is a rule that holds until one of them is edited — the same
 * failure the frame table itself was created to end. This is the rule, once.
 *
 * The insets resolve per edge rather than per direction, so it is already right in both
 * scripts; on a television both are zero and the frame's own numbers stand unaltered.
 */
fun Modifier.castivioStage(frame: CastivioFrame): Modifier = composed {
    val insets = WindowInsets.safeDrawing.asPaddingValues()
    val direction = LocalLayoutDirection.current

    padding(
        start = maxOf(frame.edge, insets.calculateStartPadding(direction)),
        end = maxOf(frame.edge, insets.calculateEndPadding(direction)),
        top = frame.stageTop,
        bottom = frame.stageBottom,
    )
}
