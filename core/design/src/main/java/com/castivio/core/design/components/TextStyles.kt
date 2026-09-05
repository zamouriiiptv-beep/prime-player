package com.castivio.core.design.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.CastivioType

/*
 * The four type steps, as the three expressions that set them.
 *
 * `CastivioFrame` says how large each step is on each frame. These say what a step
 * *is* — which face it sits on, how much leading it takes, and what tracking. A screen
 * that copies those three lines has made a fourth opinion about the same step, and the
 * copies had already begun to disagree: a card's description was written five times
 * across the flow, on two different base styles, at two different leadings.
 */

/**
 * The style the screen's name is set in, from the frame's own title step.
 *
 * The tracking is zero and is stated rather than inherited. `headlineMedium` carries a
 * small negative track, which is a sensible Latin display correction and wrong for
 * Arabic at any size: the script joins, and pulling the letters together closes the
 * joins rather than tightening the word.
 *
 * @param fsTitle [com.castivio.core.design.theme.CastivioFrame.fsTitle].
 */
@Composable
@ReadOnlyComposable
fun castivioTitleStyle(fsTitle: Dp): TextStyle = CastivioType.headlineMedium.copy(
    fontSize = fsTitle.value.sp,
    lineHeight = (fsTitle.value * TITLE_LEADING).sp,
    letterSpacing = 0.sp,
)

/**
 * The style a chip's own words are set in, from the frame's chip step.
 *
 * @param fsChip [com.castivio.core.design.theme.CastivioFrame.fsChip].
 */
@Composable
@ReadOnlyComposable
fun castivioChipStyle(fsChip: Dp): TextStyle = CastivioType.bodyMedium.copy(
    fontSize = fsChip.value.sp,
    lineHeight = (fsChip.value * CHIP_LEADING).sp,
    letterSpacing = 0.sp,
)

/**
 * The style every description is set in, from the frame's body step.
 *
 * ## What a description is
 *
 * The secondary prose that explains the thing beside it: a source card's sentence, the
 * caption under a QR plate, the line that says how many languages there are, the
 * address under a saved subscription's name, the sentence an empty library shows. Not
 * a *name* — those are the label step — and not a legal notice, which is quieter on
 * purpose and says so with a different colour.
 *
 * ## Why it is here
 *
 * It was written five times, and the copies had already drifted. Two screens set it on
 * `bodySmall` at one-and-a-half leading; two set it on `bodyMedium` through the chip
 * helper at the same leading, which is a different face; one set it through the chip
 * helper *without* the override, so at 1.45; and three more took the fixed `bodySmall`
 * token outright, which ignores the frame — a description on a television came out the
 * same 12.5sp as one on the shortest phone, on the screen that is read from three
 * metres.
 *
 * None of that was visible in any one file. It is the kind of drift that only a reader
 * moving between two screens can see, which is exactly the kind this project keeps
 * finding late.
 *
 * ## The leading
 *
 * One and a half, on every frame. Arabic hangs marks above the line and drops tails
 * below it, so leading that looks generous in a Latin face is tight here — and this is
 * the one step that regularly runs to three and four lines, where the difference
 * compounds.
 *
 * @param fsBody [com.castivio.core.design.theme.CastivioFrame.fsBody].
 */
@Composable
@ReadOnlyComposable
fun castivioBodyStyle(fsBody: Dp): TextStyle = CastivioType.bodySmall.copy(
    fontSize = fsBody.value.sp,
    lineHeight = (fsBody.value * BODY_LEADING).sp,
    letterSpacing = 0.sp,
)

/**
 * The ink a description is set in.
 *
 * One role, so no screen picks it. It is `onBackgroundVariant` — the palette's existing
 * secondary ink, not a new colour — and it is the value five of the six description
 * sites already used; the sixth had taken the chip helper's default and come out the
 * same by luck rather than by rule.
 *
 * Deliberately **not** the ink of everything quiet. `onBackgroundMuted` is a step
 * further back and belongs to text that is present without asking to be read: the legal
 * line under the activation screen, the expiry date beneath a status sentence, the
 * second clause inside a card's description. Folding those in would make the disclaimer
 * louder, which is a decision about the product rather than about typography.
 */
val castivioDescriptionColor: Color
    @Composable @ReadOnlyComposable get() = CastivioTheme.colors.onBackgroundVariant

/**
 * The leadings, as ratios, because the sizes step per frame.
 *
 * [BODY_LEADING] is public because one thing outside this file needs it and must not
 * hold a second copy: the activation screen's code panel budgets its caption at
 * `fsBody × leading × lines`, and a budget computed from a different number than the
 * text is drawn at is a budget that is quietly wrong.
 */
private const val TITLE_LEADING = 1.35f
private const val CHIP_LEADING = 1.45f
const val BODY_LEADING = 1.5f
