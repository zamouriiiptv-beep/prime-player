package com.castivio.tv.ui.components

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
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.castivio.tv.ui.theme.CastivioTheme
import com.castivio.tv.ui.theme.Elevation
import com.castivio.tv.ui.theme.Motion
import com.castivio.tv.ui.theme.Radius

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
 */
@Composable
fun InteractiveGlassCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(Radius.lg),
    content: @Composable () -> Unit,
) {
    val colors = CastivioTheme.colors
    val interaction = remember { MutableInteractionSource() }
    var focused by remember { mutableStateOf(false) }

    val elevation by animateDpAsState(
        if (focused) Elevation.level3 else Elevation.level2, Motion.focusSpec(), label = "elev",
    )
    val border by animateColorAsState(
        if (focused) colors.focusRing else colors.glassBorder, Motion.focusSpec(), label = "border",
    )
    val glow = if (focused) colors.focusGlow else Elevation.spot

    Box(
        modifier
            .castivioFocusScale(Motion.focusScaleCard, interaction)
            .onFocusChanged { focused = it.isFocused || it.hasFocus }
            .shadow(elevation, shape, ambientColor = Elevation.ambient, spotColor = glow)
            .clip(shape)
            .background(colors.glassFillBrush)
            .border(BorderStroke(1.dp, border), shape)
            .clickable(interaction, indication = null, onClick = onClick),
    ) { content() }
}
