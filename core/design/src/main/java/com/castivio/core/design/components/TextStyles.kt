package com.castivio.core.design.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.castivio.core.design.theme.CastivioColors
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.CastivioType

/*
 * The four type steps, as the three expressions that set them.
 *
 * `CastivioMetrics` says how large each step is on a given surface. These say what a step
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
 * @param fsTitle [com.castivio.core.design.theme.CastivioMetrics.fsTitle].
 */
@Composable
@ReadOnlyComposable
fun castivioTitleStyle(fsTitle: Dp): TextStyle = CastivioType.headlineMedium.copy(
    fontSize = fsTitle.value.sp,
    lineHeight = (fsTitle.value * TITLE_LEADING).sp,
    letterSpacing = 0.sp,
)

/**
 * The style a card's own name is set in, from the frame's label step.
 *
 * ## Why this exists
 *
 * Because three screens were building it by hand. A card's name is the one step the
 * system did not offer a function for, so the chooser wrote
 * `CastivioType.titleMedium.copy(fontSize = …, lineHeight = … * 1.35f, Bold, 0.sp)`,
 * the licence screen wrote its own, and the activation screen a third — and
 * `TITLE_LEADING` ended up declared privately in two files, which is a constant with
 * two homes and therefore one edit away from being two numbers.
 *
 * Bold rather than the title step's weight: a card's name sits beside a disc and
 * above two lines of description, and at the same weight as its own prose it stops
 * being a heading.
 *
 * @param fsCard [com.castivio.core.design.theme.CastivioMetrics.fsLabel].
 */
@Composable
@ReadOnlyComposable
fun castivioCardNameStyle(fsCard: Dp): TextStyle = CastivioType.titleMedium.copy(
    fontSize = fsCard.value.sp,
    lineHeight = (fsCard.value * TITLE_LEADING).sp,
    fontWeight = FontWeight.Bold,
    letterSpacing = 0.sp,
)

/**
 * The style a value a reader copies out is set in: a MAC address, a device key.
 *
 * Monospace and tracked, because what makes a code readable is that its columns line
 * up and its characters cannot be mistaken for one another — and the tracking is a
 * parameter rather than a constant because six hex pairs and six digits want
 * different air between them.
 *
 * @param fsValue the step the screen sets this value at.
 * @param tracking how far apart the characters sit, in sp.
 */
@Composable
@ReadOnlyComposable
fun castivioValueStyle(fsValue: Dp, tracking: Float): TextStyle = CastivioType.codeHero.copy(
    fontSize = fsValue.value.sp,
    lineHeight = (fsValue.value * VALUE_LEADING).sp,
    letterSpacing = tracking.sp,
)

/**
 * The style a field's name is set in: the word to the left of a value.
 *
 * @param fsLabel [com.castivio.core.design.theme.CastivioMetrics.fsLabel].
 */
@Composable
@ReadOnlyComposable
fun castivioFieldLabelStyle(fsLabel: Dp): TextStyle = CastivioType.labelMedium.copy(
    fontSize = fsLabel.value.sp,
    lineHeight = (fsLabel.value * LABEL_LEADING).sp,
    letterSpacing = 0.sp,
)

/**
 * The style a button's label is set in, from whatever step the screen sizes it at.
 *
 * `CastivioButton` falls back to `labelLarge` at its own fixed size when a caller
 * gives it nothing; this is the responsive form, for the screens whose buttons are a
 * share of the frame.
 *
 * @param fsButton the step the screen sets its buttons at.
 */
@Composable
@ReadOnlyComposable
fun castivioButtonStyle(fsButton: Dp): TextStyle = CastivioType.labelLarge.copy(
    fontSize = fsButton.value.sp,
    lineHeight = (fsButton.value * CHIP_LEADING).sp,
    letterSpacing = 0.sp,
)

/**
 * The style a chip's own words are set in, from the frame's chip step.
 *
 * @param fsChip [com.castivio.core.design.theme.CastivioMetrics.fsChip].
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
 * @param fsBody [com.castivio.core.design.theme.CastivioMetrics.fsBody].
 */
@Composable
@ReadOnlyComposable
fun castivioBodyStyle(fsBody: Dp): TextStyle = CastivioType.bodySmall.copy(
    fontSize = fsBody.value.sp,
    lineHeight = (fsBody.value * BODY_LEADING).sp,
    letterSpacing = 0.sp,
)

/**
 * The ink a description is set in: [CastivioColors.description], `Quartz`.
 *
 * One role, so no screen picks it, and one place to change it. Six screens had written
 * `onBackgroundVariant` by hand — the same fact stated six times, and therefore one
 * edit away from being two — and the role they were borrowing dresses more than
 * descriptions, so it could not simply be moved.
 *
 * Deliberately **not** the ink of everything quiet. `onBackgroundMuted` is a step
 * further back and belongs to text that is present without asking to be read: the legal
 * line under the activation screen, the expiry date beneath a status sentence, the
 * second clause inside a card's description. Folding those in would make the disclaimer
 * louder, which is a decision about the product rather than about typography.
 */
val castivioDescriptionColor: Color
    @Composable @ReadOnlyComposable get() = CastivioTheme.colors.description

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

/** A field's name, which is a label and sits tighter than a chip's sentence. */
private const val LABEL_LEADING = 1.4f

/**
 * A code's leading, which is tighter than prose because a code has no descenders to
 * clear and is read as a block rather than as a line.
 */
private const val VALUE_LEADING = 1.3f

const val BODY_LEADING = 1.5f
