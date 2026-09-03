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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
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
private const val DISABLED_ALPHA = 0.45f

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
    /**
     * A disabled button is dimmed, unfocusable and unclickable — all three, because any
     * one of them alone is a control that still looks or behaves live. A D-pad that can
     * land on a button which does nothing is the television version of the same bug.
     */
    enabled: Boolean = true,
    /**
     * A different ramp for the primary fill.
     *
     * The default is `primaryBrush`, which is the azure every primary action in
     * the app is drawn with. The activation screen's call to action is the width
     * of a whole column, and a two-stop blue across that distance reads as a bar
     * rather than as a button, so it passes `ctaBrush` instead. A parameter
     * rather than a fourth `ButtonWeight`: the weight says what the control means
     * and this says what it is painted with, and conflating the two is how a
     * variant list starts growing.
     */
    fill: Brush? = null,
    /** Overrides the corner. Null keeps `Radius.sm`. */
    corner: Dp? = null,
    /** Overrides the label's type. Null keeps `labelLarge`. */
    labelStyle: TextStyle? = null,
    /** A floor above the frame's own. The D-pad minimum still applies under it. */
    minHeight: Dp? = null,
) {
    val colors = CastivioTheme.colors
    val interaction = remember { MutableInteractionSource() }
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(corner ?: Radius.sm)

    val elevation by animateDpAsState(
        when {
            !enabled -> Elevation.level0
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
    val contentColor = when {
        !enabled -> colors.onBackgroundMuted
        else -> tint ?: colors.onBackground
    }

    Box(
        modifier = modifier
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .castivioFocusScale(Motion.focusScaleButton, interaction)
            .focusProperties { canFocus = enabled }
            .onFocusChanged { focused = it.isFocused || it.hasFocus }
            // The frame's floor, not the touch floor. This was `minTouchTarget`
            // on every device, which made every button in Castivio 48dp on a
            // television -- 8dp under `minTvTarget` and the same defect that was
            // called blocking when the activation screen's copy control had it.
            // A remote is not a thumb, and the constant that says so exists.
            .defaultMinSize(
                minHeight = maxOf(
                    Sizing.minTarget(CastivioTheme.device.isTv),
                    minHeight ?: 0.dp,
                ),
            )
            .shadow(elevation, shape, ambientColor = Elevation.ambient, spotColor = glow)
            .clip(shape)
            .then(
                when (weight) {
                    ButtonWeight.Primary -> Modifier.background(fill ?: colors.primaryBrush)
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
            .clickable(interaction, indication = null, enabled = enabled, onClick = onClick)
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
            Text(text, color = contentColor, style = labelStyle ?: CastivioType.labelLarge)
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
            // Square, and at the frame's floor in both directions: an icon-only
            // control has no label to grow it, so it was the smallest target in
            // the application at 36dp.
            .defaultMinSize(
                minWidth = Sizing.minTarget(CastivioTheme.device.isTv),
                minHeight = Sizing.minTarget(CastivioTheme.device.isTv),
            )
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
            // Sixteen dp of padding around a 20dp line is a 36dp control, which
            // is under the floor on a thumb and well under it on a remote. The
            // padding still decides the *width*; the floor decides the height.
            .defaultMinSize(minHeight = Sizing.minTarget(CastivioTheme.device.isTv))
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
