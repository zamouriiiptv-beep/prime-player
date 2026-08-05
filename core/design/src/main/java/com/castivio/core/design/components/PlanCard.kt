package com.castivio.core.design.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.CastivioType
import com.castivio.core.design.theme.Elevation
import com.castivio.core.design.theme.Motion
import com.castivio.core.design.theme.MotionLevel
import com.castivio.core.design.theme.Radius

/**
 * The four numbers a plan card needs, which differ per frame.
 *
 * Told, not inferred: the component has no opinion about televisions, and the
 * caller owns its own per-frame table. Same arrangement as [CapsuleMetrics], for
 * the same reason.
 */
data class PlanCardMetrics(
    /**
     * The floor. The card is usually taller than this — the content decides —
     * but two cards side by side must be the same height even when one plan's
     * period wraps and the other's does not.
     */
    val minHeight: Dp,
    val horizontalPadding: Dp,
    val verticalPadding: Dp,
    /**
     * `headlineLarge` on the reference phone and `displayMedium` on a
     * television; `headlineMedium` on the shortest frame, where the eight dp of
     * leading this saves is what keeps the column inside its band.
     */
    val priceStyle: TextStyle,
)

/**
 * A plan, and pressing it buys that plan.
 *
 * ## The card is the button
 *
 * There is no select-then-continue: no radio, no selected state, no Continue.
 * On a television that removes an entire interaction — no invisible selection to
 * communicate, no second press — and it makes the project's standing rule
 * *focused ≠ selected* trivially satisfiable, because nothing is ever selected.
 * The only states this has are resting, focused and working.
 *
 * ## Nothing is recommended
 *
 * Locked by product decision: both plans are presented at equal weight and the
 * user decides. `selectedFill` and `selectedBorder` therefore carry *focus* and
 * nothing else, which removes the one confusion those tokens could have caused
 * here — "the one we suggest" and "the one the remote is on" would have been
 * painted the same colour.
 *
 * @param description the whole card as one sentence — name, price and period —
 *   because a screen reader walking three disconnected nodes reads a price with
 *   nothing attached to it. The card is a single focusable node.
 * @param working true while this card's portal is being opened. Dimmed and
 *   unfocusable, so a second press cannot start a second handoff.
 */
@Composable
fun PlanCard(
    metrics: PlanCardMetrics,
    name: String,
    price: String,
    period: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    working: Boolean = false,
) {
    val colors = CastivioTheme.colors
    val interaction = remember { MutableInteractionSource() }
    var focused by remember { mutableStateOf(false) }

    // A rounded rectangle, not a pill. The identity capsules are pills because
    // they hold one line; this holds two, and a 50% corner on a 78dp box is a
    // lozenge. Same material, same hairline, at the shape the content needs.
    val shape = RoundedCornerShape(Radius.xl)

    // One spec for all four properties, so the card arrives as a single thing.
    //
    // 200ms, in the middle of the 180-220ms the design asks for. A `tween`
    // rather than a spring: a spring on a purchase control overshoots, and a
    // card that bounces under a remote reads as a toy. `snap` when the user has
    // turned animation off -- the card still changes, it just stops travelling.
    val level = CastivioTheme.motionLevel
    val spec: FiniteAnimationSpec<Float> = remember(level) { focusSpec(level) }
    val colourSpec: FiniteAnimationSpec<Color> = remember(level) { focusSpec(level) }
    val sizeSpec: FiniteAnimationSpec<Dp> = remember(level) { focusSpec(level) }

    val fill by animateColorAsState(
        if (focused) colors.selectedFill else colors.glassFill,
        colourSpec, label = "planFill",
    )
    val border by animateColorAsState(
        if (focused) colors.selectedBorder else colors.glassBorderSoft,
        colourSpec, label = "planBorder",
    )
    // Two dp focused, one at rest. The hairline is what every Castivio surface
    // wears; doubling it is the cheapest way to say "this one" at three metres
    // without introducing a shape the resting card does not have.
    val stroke by animateDpAsState(
        if (focused) FOCUSED_STROKE else RESTING_STROKE,
        sizeSpec, label = "planStroke",
    )
    // The glow, as a shadow rather than a second border. Violet, because the
    // fill and the border under the remote are already violet -- the card gets
    // nearer, it does not change colour. Zero at rest, so an unfocused card
    // casts nothing and the row stays flat.
    val lift by animateDpAsState(
        if (focused) FOCUSED_LIFT else Elevation.level0,
        sizeSpec, label = "planLift",
    )
    // Press wins over focus, and presses down. Unchanged from what the shared
    // focus modifier did: this is the touch behaviour, and a thumb expects the
    // surface to give.
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        when {
            pressed -> Motion.pressScale
            focused -> FOCUSED_SCALE
            else -> 1f
        },
        spec, label = "planScale",
    )

    Column(
        modifier
            .alpha(if (working) WORKING_ALPHA else 1f)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .focusProperties { canFocus = !working }
            .onFocusChanged { focused = it.isFocused || it.hasFocus }
            .defaultMinSize(minHeight = metrics.minHeight)
            .shadow(lift, shape, ambientColor = Elevation.ambient, spotColor = colors.selectedGlow)
            .clip(shape)
            .background(fill)
            .border(BorderStroke(stroke, border), shape)
            .clickable(interaction, indication = null, enabled = !working, onClick = onClick)
            .padding(
                horizontal = metrics.horizontalPadding,
                vertical = metrics.verticalPadding,
            )
            // One node, one sentence. Everything inside is decoration as far as
            // a reader is concerned, and the sentence says the price as money
            // rather than as a run of digits and a symbol.
            .clearAndSetSemantics { contentDescription = description },
        verticalArrangement = Arrangement.Center,
    ) {
        // The same register as MAC ADDRESS and DEVICE KEY: a label naming the
        // thing below it, not a heading.
        Text(text = name, style = CastivioType.overline, color = colors.onBackgroundVariant)

        Row(
            horizontalArrangement = Arrangement.spacedBy(PRICE_GAP),
            // Baselines, not centres. A 28sp price beside a 12.5sp period
            // centred looks like two unrelated things that happen to be
            // adjacent; sharing a baseline is what makes "€6 per year" one
            // phrase.
            verticalAlignment = Alignment.Bottom,
        ) {
            // Not monospace, deliberately. `CastivioType.Mono` is for
            // identifiers that get transcribed, spoken and compared character by
            // character. A price is read, not copied.
            Text(
                text = price,
                style = metrics.priceStyle,
                color = colors.onBackground,
                maxLines = 1,
            )
            Text(
                text = period,
                style = CastivioType.bodySmall,
                color = colors.onBackgroundMuted,
                maxLines = 1,
            )
        }
    }
}

/**
 * The focus spec every animated property on the card shares.
 *
 * Not `Motion.focusSpec()`, which is 180ms and shared with every control in the
 * product. This is 200ms and belongs to the plan cards, because they are the
 * one surface where focus does four things at once and they have to arrive
 * together.
 */
private fun <T> focusSpec(level: MotionLevel): FiniteAnimationSpec<T> =
    planFocusMillis(level).let { if (it == 0) snap() else tween(it, easing = Motion.standard) }

/**
 * How long a plan card takes to arrive at, or leave, focus.
 *
 * Split out from the spec so it can be asserted without a device:
 * `PlanCardFocusTest` checks it against the window the design states, and checks
 * that motion off is a snap rather than a fast animation. A frame-stepped test
 * of the real thing needs a clock this environment cannot drive reliably, and
 * three attempts at one taught nothing except how long CI takes.
 *
 * @return 0 when the change must be instant.
 */
internal fun planFocusMillis(level: MotionLevel): Int =
    if (level == MotionLevel.DISABLED) 0 else FOCUS_MS

/** Inside the 180-220ms window, and deliberately in the middle of it. */
internal const val FOCUS_MS = 200

/**
 * Slight. 1.05 was the shared button value and it is too much for a card this
 * wide -- at 210dp it moves the outer edge five dp, which on a row of two reads
 * as the pair shifting rather than one of them lifting.
 */
internal const val FOCUSED_SCALE = 1.03f

internal val RESTING_STROKE = 1.dp
internal val FOCUSED_STROKE = 2.dp

/** Enough for the violet to be seen around the card, not enough to detach it. */
internal val FOCUSED_LIFT = Elevation.level2

/** Visibly busy, still legible. The card is not gone, it is occupied. */
private const val WORKING_ALPHA = 0.5f

/** Enough to separate the amount from what it buys, at every price length. */
private val PRICE_GAP = 8.dp
