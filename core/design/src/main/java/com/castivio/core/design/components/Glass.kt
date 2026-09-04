package com.castivio.core.design.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.Elevation
import com.castivio.core.design.theme.Motion
import com.castivio.core.design.theme.Radius

/**
 * Glass surfaces.
 *
 * Castivio's glass is *quiet*: a translucent fill with a vertical sheen, a
 * hairline border that fades top-to-bottom, and a wide soft shadow. Android
 * has no true backdrop blur below API 31, so the effect is built from layered
 * translucency instead — which reads the same over the animated backdrop and
 * costs nothing on a cheap TV stick.
 *
 * Three weights:
 *  - [GlassCard]       the default panel.
 *  - [GlassHeroCard]   larger radius and lift, for the one hero per screen.
 *  - [InteractiveGlassCard] a card that can be focused and clicked.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(Radius.lg),
    elevation: Dp = Elevation.level2,
    content: @Composable () -> Unit,
) {
    val colors = CastivioTheme.colors
    Box(
        modifier
            .shadow(elevation, shape, ambientColor = Elevation.ambient, spotColor = Elevation.spot)
            .clip(shape)
            .background(colors.glassFillBrush)
            .border(BorderStroke(1.dp, colors.glassBorderBrush), shape),
    ) { content() }
}

/** The single most important panel on a screen. */
@Composable
fun GlassHeroCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) = GlassCard(
    modifier = modifier,
    shape = RoundedCornerShape(Radius.xxl),
    elevation = Elevation.level3,
    content = content,
)

/**
 * A glass card that behaves as one target: lifts and brightens on focus,
 * dips on press. Use for menu entries, method pickers, channel tiles.
 *
 * @param fill the pane's own glass. The default is `glassFillBrush`, 7.8% of
 *   white falling to 3.9% down the card — right for a list of tiles, where the
 *   fade is what keeps a column of them from reading as a stack of slabs.
 *
 *   A screen whose whole content is two large panes wants the opposite: at that
 *   size the fade lands the bottom half of each pane on 3.9%, which is the fill
 *   of an *inactive* surface, and the card stops reading as a thing you can
 *   choose. Passing `SolidColor(colors.glassFillStrong)` holds it at 7.8%
 *   throughout. Both values are the ones already in the theme; what the caller
 *   picks is whether the pane fades, not what it is made of.
 */
@Composable
fun InteractiveGlassCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(Radius.lg),
    fill: Brush = CastivioTheme.colors.glassFillBrush,
    /**
     * The edge, and the light around it, while the card is **not** focused.
     *
     * A card that means something before anyone touches it — the suggested option
     * on a chooser — has to say so at rest, and it cannot say so the way focus
     * does. Two states drawn with the same ring is a viewer who cannot find the
     * D-pad. So a recommendation gets a colour here and focus still overrides it,
     * which keeps the ring meaning exactly one thing on every screen.
     *
     * Null on both leaves the glass card as it was.
     */
    restBorder: Color? = null,
    restGlow: Color? = null,
    content: @Composable () -> Unit,
) {
    val colors = CastivioTheme.colors
    val interaction = remember { MutableInteractionSource() }
    var focused by remember { mutableStateOf(false) }

    val elevation by animateDpAsState(
        if (focused) Elevation.level3 else Elevation.level2, Motion.focusSpec(), label = "elev",
    )
    val border by animateColorAsState(
        if (focused) colors.focusRing else restBorder ?: colors.glassBorder,
        Motion.focusSpec(),
        label = "border",
    )
    val glow = if (focused) colors.focusGlow else restGlow ?: Elevation.spot

    Box(
        modifier
            .castivioFocusScale(Motion.focusScaleCard, interaction)
            .onFocusChanged { focused = it.isFocused || it.hasFocus }
            .shadow(elevation, shape, ambientColor = Elevation.ambient, spotColor = glow)
            .clip(shape)
            .background(fill)
            .border(BorderStroke(1.dp, border), shape)
            .clickable(interaction, indication = null, onClick = onClick),
    ) { content() }
}
