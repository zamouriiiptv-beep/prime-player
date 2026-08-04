package com.castivio.core.design.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.CastivioType
import com.castivio.core.design.theme.Motion
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

    val fill by animateColorAsState(
        if (focused) colors.selectedFill else colors.glassFill,
        Motion.focusSpec(), label = "planFill",
    )
    val border by animateColorAsState(
        if (focused) colors.selectedBorder else colors.glassBorderSoft,
        Motion.focusSpec(), label = "planBorder",
    )

    Column(
        modifier
            .alpha(if (working) WORKING_ALPHA else 1f)
            .castivioFocusScale(Motion.focusScaleButton, interaction)
            .focusProperties { canFocus = !working }
            .onFocusChanged { focused = it.isFocused || it.hasFocus }
            .defaultMinSize(minHeight = metrics.minHeight)
            .clip(shape)
            .background(fill)
            .border(BorderStroke(1.dp, border), shape)
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

/** Visibly busy, still legible. The card is not gone, it is occupied. */
private const val WORKING_ALPHA = 0.5f

/** Enough to separate the amount from what it buys, at every price length. */
private val PRICE_GAP = 8.dp
