package com.castivio.core.design.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.CastivioType
import com.castivio.core.design.theme.Elevation
import com.castivio.core.design.theme.Motion
import com.castivio.core.design.theme.Radius
import com.castivio.core.design.theme.Sizing
import com.castivio.core.design.theme.Spacing

/**
 * Buttons.
 *
 * Exactly three weights, and a screen should show at most one [Primary]. The
 * hierarchy is carried by *fill*, not by size — every button on a row shares
 * the same height so the row reads as a single band.
 */
enum class ButtonWeight {
    /** One per screen: the action we want taken. Gradient fill. */
    Primary,
    /** The common case: glass with a hairline border. */
    Secondary,
    /** Lowest emphasis: no fill until focused. Toolbars, inline actions. */
    Ghost,
}

@Composable
fun CastivioButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    weight: ButtonWeight = ButtonWeight.Secondary,
    icon: ImageVector? = null,
    tint: Color? = null,
) {
    val colors = CastivioTheme.colors
    val interaction = remember { MutableInteractionSource() }
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(Radius.sm)

    val elevation by animateDpAsState(
        when {
            weight == ButtonWeight.Ghost -> Elevation.level0
            focused -> Elevation.level3
            else -> Elevation.level1
        },
        Motion.focusSpec(), label = "btnElev",
    )
    val border by animateColorAsState(
        if (focused) colors.focusRing else colors.glassBorder, Motion.focusSpec(), label = "btnBorder",
    )
    val glow = when {
        weight == ButtonWeight.Primary -> colors.focusGlow
        focused -> colors.focusGlow
        else -> Elevation.spot
    }
    val contentColor = tint ?: colors.onBackground

    Box(
        modifier = modifier
            .castivioFocusScale(Motion.focusScaleButton, interaction)
            .onFocusChanged { focused = it.isFocused || it.hasFocus }
            .defaultMinSize(minHeight = Sizing.minTouchTarget)
            .shadow(elevation, shape, ambientColor = Elevation.ambient, spotColor = glow)
            .clip(shape)
            .then(
                when (weight) {
                    ButtonWeight.Primary -> Modifier.background(colors.primaryBrush)
                    ButtonWeight.Secondary -> Modifier
                        .background(colors.glassFillBrush)
                        .border(BorderStroke(1.dp, border), shape)
                    ButtonWeight.Ghost -> if (focused) {
                        Modifier.background(colors.glassFill)
                    } else {
                        Modifier
                    }
                }
            )
            .clickable(interaction, indication = null, onClick = onClick)
            .padding(horizontal = Spacing.xl, vertical = Spacing.md),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            if (icon != null) {
                Icon(icon, null, tint = contentColor, modifier = Modifier.size(Sizing.iconMd))
            }
            Text(text, color = contentColor, style = CastivioType.labelLarge)
        }
    }
}

/**
 * A square icon-only button. Always pair with a content description — on a
 * television these are the only controls a screen reader can announce.
 */
@Composable
fun CastivioIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color? = null,
) {
    val colors = CastivioTheme.colors
    val interaction = remember { MutableInteractionSource() }
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(Radius.xs)
    val border by animateColorAsState(
        if (focused) colors.focusRing else colors.glassBorder, Motion.focusSpec(), label = "iconBorder",
    )

    Box(
        modifier = modifier
            .castivioFocusScale(Motion.focusScaleIcon, interaction)
            .onFocusChanged { focused = it.isFocused || it.hasFocus }
            .clip(shape)
            .background(colors.glassFill)
            .border(BorderStroke(1.dp, border), shape)
            .clickable(interaction, indication = null, onClick = onClick)
            .padding(Spacing.sm),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription,
            tint = tint ?: colors.primary,
            modifier = Modifier.size(Sizing.iconMd),
        )
    }
}

/** A rounded glass pill: language switchers, filters, status badges. */
@Composable
fun CastivioChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    val colors = CastivioTheme.colors
    val interaction = remember { MutableInteractionSource() }
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(Radius.pill)
    val border by animateColorAsState(
        if (focused) colors.focusRing else colors.glassBorder, Motion.focusSpec(), label = "chipBorder",
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        modifier = modifier
            .castivioFocusScale(Motion.focusScaleButton, interaction)
            .onFocusChanged { focused = it.isFocused || it.hasFocus }
            .clip(shape)
            .background(colors.glassFillBrush)
            .border(BorderStroke(1.dp, border), shape)
            .clickable(interaction, indication = null, onClick = onClick)
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
    ) {
        if (icon != null) {
            Icon(icon, null, tint = colors.onBackground, modifier = Modifier.size(Sizing.iconSm))
        }
        Text(text, color = colors.onBackground, style = CastivioType.labelLarge)
    }
}

/** Default content padding for buttons, exposed for bespoke layouts. */
val CastivioButtonPadding = PaddingValues(horizontal = Spacing.xl, vertical = Spacing.md)
