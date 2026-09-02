package com.castivio.core.design.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.CastivioType
import com.castivio.core.design.theme.Motion
import com.castivio.core.design.theme.Radius
import com.castivio.core.design.theme.Spacing
import com.castivio.core.design.theme.posterPlaceholderBrush

/**
 * The one card, with variants as parameters — design invariant 6.
 *
 * A poster and a landscape thumbnail are the same component at two aspect ratios,
 * both carrying the same [WatchState] mark, the same focus behaviour, and the same
 * one optional badge. A second card that was "nearly the same" is the thing this
 * exists to prevent.
 */
enum class CardShape(val ratio: Float) {
    Poster(2f / 3f),
    Landscape(16f / 9f),
}

@Composable
fun MediaCard(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    shape: CardShape = CardShape.Landscape,
    width: Dp = 176.dp,
    watchState: WatchState = WatchState.None,
    artworkSeed: Int = 0,
    /** One badge, top-start — a NowPlayingBadge, a WatchedTag or an episode chip. */
    badge: (@Composable BoxScope.() -> Unit)? = null,
    /** A single line under the artwork: now/next, or "42 min left". */
    caption: String? = null,
) {
    val colors = CastivioTheme.colors
    val interaction = remember { MutableInteractionSource() }
    var focused by remember { mutableStateOf(false) }
    val cardShape = RoundedCornerShape(Radius.sm)
    val border by animateColorAsState(
        if (focused) colors.focusRing else colors.glassBorderSoft,
        Motion.focusSpec(), label = "cardBorder",
    )

    // The accessible name says in words what the marks say in colour.
    val spoken = buildString {
        append(title)
        if (subtitle != null) append(", $subtitle")
        when (watchState) {
            is WatchState.InProgress -> append(", partly watched")
            is WatchState.Watched -> append(", watched")
            is WatchState.Playing -> append(", playing now")
            is WatchState.None -> {}
        }
        if (caption != null) append(", $caption")
    }

    Column(
        // Width first, then the caller's modifier, so a fillMaxWidth() from a grid
        // cell wins while a bare row still gets the default card width.
        modifier = Modifier
            .width(width)
            .then(modifier)
            .castivioFocusScale(Motion.focusScaleCard, interaction)
            .onFocusChanged { focused = it.isFocused || it.hasFocus }
            .clickable(interaction, indication = null, onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = spoken
            },
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(shape.ratio)
                .clip(cardShape)
                .background(posterPlaceholderBrush(artworkSeed))
                .border(1.dp, border, cardShape),
        ) {
            WatchMarks(watchState)
            if (badge != null) {
                Box(Modifier.align(Alignment.TopStart).padding(Spacing.sm)) {
                    Box { badge() }
                }
            }
        }
        Text(
            title,
            style = CastivioType.bodyMedium,
            color = colors.onBackgroundVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(top = Spacing.sm)
                .clearAndSetSemantics {},
        )
        val under = caption ?: subtitle
        if (under != null) {
            Text(
                under,
                style = CastivioType.bodySmall,
                color = colors.onBackgroundMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clearAndSetSemantics {},
            )
        }
    }
}

/**
 * A channel or station row card: logo tile, name, and what is on now.
 *
 * The history bar runs down the *leading* edge here rather than the bottom — a row
 * has no bottom edge to spare — but it is the same four readings and the same
 * colours, rotated. Playing takes the aqua edge; watched dims the name.
 */
@Composable
fun ChannelCard(
    name: String,
    nowPlaying: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    seed: Int = 0,
    number: String? = null,
    watchState: WatchState = WatchState.None,
    width: Dp = 200.dp,
) {
    val colors = CastivioTheme.colors
    val interaction = remember { MutableInteractionSource() }
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(Radius.sm)
    val border by animateColorAsState(
        when {
            focused -> colors.focusRing
            watchState is WatchState.Playing -> colors.live.copy(alpha = 0.45f)
            else -> colors.glassBorderSoft
        },
        Motion.focusSpec(), label = "chBorder",
    )
    val leadingBar = when (watchState) {
        is WatchState.Playing -> colors.live
        is WatchState.Watched -> colors.onBackgroundMuted.copy(alpha = 0.55f)
        is WatchState.InProgress -> colors.secondary
        is WatchState.None -> null
    }

    Box(
        modifier = Modifier
            .width(width)
            .then(modifier)
            .castivioFocusScale(Motion.focusScaleButton, interaction)
            .onFocusChanged { focused = it.isFocused || it.hasFocus }
            .clip(shape)
            .background(colors.glassFill)
            .border(1.dp, border, shape)
            .clickable(interaction, indication = null, onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = "$name, now $nowPlaying" +
                    if (watchState is WatchState.Playing) ", playing now" else ""
            },
    ) {
        if (leadingBar != null) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .padding(vertical = Spacing.sm)
                    .width(3.dp)
                    .size(width = 3.dp, height = 34.dp)
                    .clip(RoundedCornerShape(Radius.pill))
                    .background(leadingBar),
            )
        }
        Row(
            modifier = Modifier.padding(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LogoTile(
                initials = name.take(2).uppercase(),
                seed = seed,
                modifier = Modifier.size(34.dp),
            )
            Column(
                Modifier
                    .padding(start = Spacing.sm)
                    .weight(1f),
            ) {
                Text(
                    name,
                    style = CastivioType.bodyMedium,
                    color = colors.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    nowPlaying,
                    style = CastivioType.bodySmall,
                    color = colors.onBackgroundMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (number != null) {
                Text(
                    number,
                    style = CastivioType.labelSmall,
                    color = colors.onBackgroundMuted,
                    modifier = Modifier.padding(start = Spacing.xs),
                )
            }
        }
    }
}
