package com.castivio.feature.activation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.castivio.core.design.icons.CastivioIcons
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.CastivioType
import com.castivio.core.design.theme.Radius
import com.castivio.core.design.theme.Sizing
import com.castivio.core.design.theme.Spacing

/**
 * The last band inside a chooser's container: a rule, Back, a rule.
 *
 * ## Why this is its own file
 *
 * It was written twice. The media source drew it first, the source choice was then
 * asked to match it "exactly", and matching it by copying is how two footers that are
 * identical today become two footers that differ in six months — one of them gets a
 * fix and the other does not, and nobody notices because both look right on their own
 * screen. Invariant 6 says a shared component is declared once; a footer that two
 * screens are required to render identically is exactly that, so it lives here and
 * both call it.
 *
 * ## What it is
 *
 * Back on the container's **true centre**, with a hairline reaching out to each side.
 * The centring is why this is three flex children rather than a centred button with
 * decoration around it: both rules are `weight(1f)`, so they resolve to the same width
 * whatever Back measures in whatever language, and Back lands on the middle of the
 * container rather than on the middle of what is left over. Those are the same point
 * only when the two sides are equal, which is the property being claimed — so it is
 * built that way rather than arranged and hoped for.
 *
 * The row is Back's own height, so nothing above it moves.
 */
@Composable
internal fun ChooserFooter(
    onBack: () -> Unit,
    tag: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FooterRule(Modifier.weight(1f))
        BackControl(onBack, tag)
        FooterRule(Modifier.weight(1f))
    }
}

/**
 * Half of the hairline. Decoration, and nothing else.
 *
 * No text, no icon, no semantics and no click, so a D-pad and a screen reader both pass
 * straight over it. `glassBorder` is the same 23.9% every edge on these screens is
 * drawn at: full weight where it meets Back, nothing by its far end. A line that stops
 * dead needs a reason to stop at that particular pixel and there is none, so it does
 * not stop — it ends.
 *
 * The colour order is read off the direction because `horizontalGradient` is physical.
 * Both halves fade *away from Back*, which makes them a mirror pair, so they read as
 * one line interrupted by the button rather than as two lines near it.
 */
@Composable
private fun FooterRule(modifier: Modifier) {
    val edge = CastivioTheme.colors.glassBorder
    val clear = edge.copy(alpha = 0f)
    // The first rule is at the start of the row and the second at the end, and which of
    // those is physically left flips with the direction. What must not flip is that the
    // solid end is the one touching the button.
    val leftToRight = LocalLayoutDirection.current == LayoutDirection.Ltr
    Box(
        modifier
            .height(RuleThickness)
            .background(
                Brush.horizontalGradient(
                    if (leftToRight) listOf(clear, edge) else listOf(edge, clear),
                ),
            ),
    )
}

/**
 * Back: a ring around the arrow, and the word beside it.
 *
 * Not `BackButton`, which the two forms and the saved sources step still use and which
 * is a `CastivioButton` with no slot for a ring. Giving the design system a ring
 * variant for two screens would be a wider change than the two screens are worth, so
 * the shape is here and everything inside it is the system's: `glassBorder` for the
 * edge, `focusRing` when it takes focus, `labelLarge` for the word, and
 * `Sizing.minTarget` for the height — the same 48dp, 56 on a television, that Back is
 * everywhere else in the flow.
 *
 * The arrow points at the *start* edge and mirrors with the layout: right in Arabic,
 * left in English. On a remote an arrow that contradicts the direction of travel is
 * worse than no arrow at all.
 */
@Composable
private fun BackControl(onClick: () -> Unit, tag: String) {
    val colors = CastivioTheme.colors
    val interaction = remember { MutableInteractionSource() }
    var focused by remember { mutableStateOf(false) }
    val ring = if (focused) colors.focusRing else colors.glassBorder
    val mirrored = LocalLayoutDirection.current == LayoutDirection.Rtl

    Row(
        modifier = Modifier
            .onFocusChanged { focused = it.isFocused || it.hasFocus }
            .defaultMinSize(minHeight = Sizing.minTarget(CastivioTheme.device.isTv))
            .clip(RoundedCornerShape(Radius.sm))
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = Spacing.xl)
            .testTag(tag),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(RingSize)
                .border(RuleThickness, ring, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = CastivioIcons.ArrowBack,
                contentDescription = null,
                tint = colors.onBackground,
                // Mirrored rather than redrawn: one glyph, pointing at the start edge
                // in both directions.
                modifier = Modifier
                    .size(Sizing.iconMd)
                    .scale(scaleX = if (mirrored) -1f else 1f, scaleY = 1f),
            )
        }
        Text(
            text = stringResource(R.string.action_back),
            style = CastivioType.labelLarge,
            color = colors.onBackground,
        )
    }
}

/**
 * The ring around the arrow: the touch target less [Spacing.md].
 *
 * Written as the subtraction rather than as 36 and 44, because that is what it is —
 * the ring sits inside the control's own height with air around it, so both figures
 * follow from the target rather than being chosen next to it.
 */
private val RingSize: Dp
    @Composable @ReadOnlyComposable get() =
        Sizing.minTarget(CastivioTheme.device.isTv) - Spacing.md

/** One device-independent pixel. A hairline is the whole point. */
internal val RuleThickness = 1.dp
