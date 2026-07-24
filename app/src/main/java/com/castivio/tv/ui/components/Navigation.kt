package com.castivio.tv.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.castivio.tv.ui.theme.CastivioTheme
import com.castivio.tv.ui.theme.CastivioType
import com.castivio.tv.ui.theme.Motion
import com.castivio.tv.ui.theme.Radius
import com.castivio.tv.ui.theme.Sizing
import com.castivio.tv.ui.theme.Spacing

/**
 * Navigation.
 *
 * Castivio never shows a bottom tab bar on a television — the thumb isn't
 * there. Instead:
 *  - [CastivioActionBar] a floating glass toolbar of equal-weight actions.
 *  - [CastivioNavRail]   a vertical rail for top-level destinations (TV/tablet).
 *  - [CastivioTopBar]    brand on one side, contextual action on the other.
 *
 * All three share one selection language: the active item takes the brand
 * colour and a gradient indicator; the focused item lifts and brightens.
 */

/** A single action inside [CastivioActionBar] or [CastivioNavRail]. */
data class NavAction(
    val icon: ImageVector,
    val label: String,
    val tint: Color? = null,
    val onClick: () -> Unit,
)

/**
 * Floating glass toolbar. Items are evenly spaced and identical in size, so
 * no single action visually outweighs another.
 */
@Composable
fun CastivioActionBar(
    actions: List<NavAction>,
    modifier: Modifier = Modifier,
) {
    GlassCard(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(Radius.lg)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = Spacing.md),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            actions.forEach { action -> ActionBarItem(action) }
        }
    }
}

@Composable
private fun ActionBarItem(action: NavAction) {
    val colors = CastivioTheme.colors
    val interaction = remember { MutableInteractionSource() }
    var focused by remember { mutableStateOf(false) }
    val labelColor by animateColorAsState(
        if (focused) colors.onBackground else colors.onBackgroundVariant,
        Motion.focusSpec(), label = "navLabel",
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .castivioFocusScale(Motion.focusScaleButton, interaction)
            .onFocusChanged { focused = it.isFocused || it.hasFocus }
            .clip(RoundedCornerShape(Radius.sm))
            .then(if (focused) Modifier.background(colors.glassFill) else Modifier)
            .clickable(interaction, indication = null, onClick = action.onClick)
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
    ) {
        Icon(
            action.icon,
            contentDescription = null,
            tint = action.tint ?: colors.onBackground,
            modifier = Modifier.size(Sizing.iconMd),
        )
        Spacer(Modifier.width(Spacing.sm))
        Text(action.label, color = action.tint ?: labelColor, style = CastivioType.labelLarge)
    }
}

/**
 * Vertical navigation rail for top-level destinations. Collapsed it shows
 * icons only; [expanded] reveals labels — the pattern Plex and Apple TV use
 * so the rail never steals width from content.
 */
@Composable
fun CastivioNavRail(
    destinations: List<NavAction>,
    selectedIndex: Int,
    modifier: Modifier = Modifier,
    expanded: Boolean = false,
) {
    val colors = CastivioTheme.colors
    GlassCard(modifier = modifier, shape = RoundedCornerShape(Radius.xl)) {
        Column(
            modifier = Modifier.padding(vertical = Spacing.lg, horizontal = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            destinations.forEachIndexed { index, destination ->
                NavRailItem(
                    action = destination,
                    selected = index == selectedIndex,
                    expanded = expanded,
                    accent = colors.primary,
                )
            }
        }
    }
}

@Composable
private fun NavRailItem(
    action: NavAction,
    selected: Boolean,
    expanded: Boolean,
    accent: Color,
) {
    val colors = CastivioTheme.colors
    val interaction = remember { MutableInteractionSource() }
    var focused by remember { mutableStateOf(false) }
    val indicator by animateFloatAsState(
        if (selected) 1f else 0f, Motion.focusSpec(), label = "indicator",
    )
    val tint = when {
        selected -> accent
        focused -> colors.onBackground
        else -> colors.onBackgroundMuted
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .castivioFocusScale(Motion.focusScaleButton, interaction)
            .onFocusChanged { focused = it.isFocused || it.hasFocus }
            .clip(RoundedCornerShape(Radius.sm))
            .then(if (focused) Modifier.background(colors.glassFill) else Modifier)
            .clickable(interaction, indication = null, onClick = action.onClick)
            .padding(horizontal = Spacing.md, vertical = Spacing.md),
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(Sizing.iconLg)
                .clip(RoundedCornerShape(Radius.pill))
                .background(accent.copy(alpha = indicator)),
        )
        Spacer(Modifier.width(Spacing.md))
        Icon(action.icon, action.label, tint = tint, modifier = Modifier.size(Sizing.iconLg))
        if (expanded) {
            Spacer(Modifier.width(Spacing.md))
            Text(action.label, color = tint, style = CastivioType.titleMedium)
        }
    }
}

/** Screen top bar: brand on one edge, a single contextual action on the other. */
@Composable
fun CastivioTopBar(
    modifier: Modifier = Modifier,
    brand: @Composable () -> Unit,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        brand()
        action?.invoke()
    }
}

/** A quiet section heading with optional trailing rule. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = CastivioTheme.colors.onBackgroundMuted,
        style = CastivioType.titleMedium,
        modifier = modifier,
    )
}
