package com.castivio.core.design.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.CastivioType
import com.castivio.core.design.theme.Motion
import com.castivio.core.design.theme.Radius
import com.castivio.core.design.theme.Spacing

/**
 * The state language, as components — `UI_ARCHITECTURE.md` §5.1.
 *
 * Everything the app knows about an item's history is one 3 dp bar in one place:
 * the bottom edge of its artwork. Four readings, one grammar, no badges fighting
 * for a corner.
 *
 *  - [WatchState.None]        nothing. Absence is the cheapest signal there is.
 *  - [WatchState.InProgress]  partial, in brand violet; the width is the resume point.
 *  - [WatchState.Watched]     full width, neutral, artwork dimmed. History is not a
 *                             status, so it takes no colour — which is what keeps it
 *                             clear of "playing".
 *  - [WatchState.Playing]     full width, aqua, with the only moving mark on screen.
 *
 * The marks read at every motion level; the meter animating is the only difference
 * [FULL] makes, and the aqua bar and the word say the same thing without it.
 */
sealed interface WatchState {
    data object None : WatchState

    /** @param fraction resume point, 0..1. */
    data class InProgress(val fraction: Float) : WatchState

    data object Watched : WatchState
    data object Playing : WatchState
}

/**
 * Draws the history bar, the watched dim and the playing edge over artwork.
 *
 * Call inside the [Box] that holds a card's image, after the image. The bar is
 * cleared from the accessibility tree because the card's own label already carries
 * "resume", "watched" or "playing" in words.
 */
@Composable
fun BoxScope.WatchMarks(state: WatchState, cornerRadius: androidx.compose.ui.unit.Dp = Radius.sm) {
    val colors = CastivioTheme.colors

    // Watched dims the artwork a third; the past should recede, not compete.
    if (state is WatchState.Watched) {
        Box(
            Modifier
                .matchParentSize()
                .clip(RoundedCornerShape(cornerRadius))
                .background(colors.scrim.copy(alpha = 0.34f)),
        )
    }
    // Playing takes an inner aqua edge as well as the bar, so it reads as "now"
    // even before the eye reaches the bottom of the card.
    if (state is WatchState.Playing) {
        Box(
            Modifier
                .matchParentSize()
                .clip(RoundedCornerShape(cornerRadius))
                .border(1.5.dp, colors.live, RoundedCornerShape(cornerRadius)),
        )
    }

    val barColor: Color? = when (state) {
        is WatchState.None -> null
        is WatchState.InProgress -> colors.secondary
        is WatchState.Watched -> colors.onBackgroundMuted.copy(alpha = 0.55f)
        is WatchState.Playing -> colors.live
    }
    val fraction = when (state) {
        is WatchState.InProgress -> state.fraction.coerceIn(0f, 1f)
        is WatchState.Watched, is WatchState.Playing -> 1f
        is WatchState.None -> 0f
    }

    if (barColor != null && fraction > 0f) {
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = Spacing.sm, vertical = Spacing.sm)
                .height(3.dp)
                .clip(RoundedCornerShape(Radius.pill))
                .background(colors.onBackground.copy(alpha = 0.16f))
                .clearAndSetSemantics {},
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction)
                    .clip(RoundedCornerShape(Radius.pill))
                    .background(barColor),
            )
        }
    }
}

/**
 * A "PLAYING" pill with a small equaliser — the loudest label in the interface,
 * because only one item is ever playing.
 *
 * The equaliser moves only at [com.castivio.core.design.theme.MotionLevel.FULL];
 * at every other level it is three static bars, and the word carries the meaning.
 */
@Composable
fun NowPlayingBadge(modifier: Modifier = Modifier) {
    val colors = CastivioTheme.colors
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(Radius.pill))
            .background(colors.background.copy(alpha = 0.78f))
            .border(1.dp, colors.live.copy(alpha = 0.55f), RoundedCornerShape(Radius.pill))
            .padding(horizontal = Spacing.sm, vertical = Spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Equaliser()
        Text("PLAYING", style = CastivioType.labelSmall, color = colors.live)
    }
}

/** A quiet "Watched" tag for the recently-watched state. Neutral, never coloured. */
@Composable
fun WatchedTag(text: String, modifier: Modifier = Modifier) {
    val colors = CastivioTheme.colors
    Text(
        text = text,
        style = CastivioType.labelSmall,
        color = colors.onBackgroundVariant,
        modifier = modifier
            .clip(RoundedCornerShape(Radius.pill))
            .background(colors.background.copy(alpha = 0.72f))
            .border(1.dp, colors.glassBorderSoft, RoundedCornerShape(Radius.pill))
            .padding(horizontal = Spacing.sm, vertical = Spacing.xxs),
    )
}

/** A small live dot — the same aqua "now" mark, for a now/next line. */
@Composable
fun LiveDot(modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(6.dp)
            .clip(RoundedCornerShape(Radius.pill))
            .background(CastivioTheme.colors.live),
    )
}

/** Three bars that bounce at FULL and stand still otherwise. */
@Composable
private fun Equaliser() {
    val colors = CastivioTheme.colors
    val animate = CastivioTheme.motionLevel.meterAnimates
    val transition = rememberInfiniteTransition(label = "eq")

    @Composable
    fun bar(baseHeightDp: Int, delay: Int) {
        val h = if (animate) {
            val v by transition.animateFloat(
                initialValue = 0.4f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    tween(durationMillis = 620, delayMillis = delay),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "eqbar$delay",
            )
            baseHeightDp * v
        } else {
            baseHeightDp.toFloat()
        }
        Box(
            Modifier
                .width(2.dp)
                .height(h.dp)
                .clip(RoundedCornerShape(Radius.pill))
                .background(colors.live),
        )
    }

    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(1.5.dp),
        modifier = Modifier.height(10.dp),
    ) {
        bar(5, 0)
        bar(10, 140)
        bar(7, 280)
    }
}

/** A small quality/number chip used on channel rows and the player OSD. */
@Composable
fun MetaChip(text: String, modifier: Modifier = Modifier, tint: Color? = null) {
    val colors = CastivioTheme.colors
    Text(
        text = text,
        style = CastivioType.labelSmall,
        color = tint ?: colors.onBackgroundVariant,
        modifier = modifier
            .clip(RoundedCornerShape(Radius.xs))
            .background(colors.glassFill)
            .padding(horizontal = Spacing.xs, vertical = Spacing.xxs),
    )
}

/** A logo/avatar placeholder tile, coloured deterministically from [seed]. */
@Composable
fun LogoTile(
    initials: String,
    seed: Int,
    modifier: Modifier = Modifier,
) {
    val colors = CastivioTheme.colors
    val tint = colors.logoTints[(seed % colors.logoTints.size + colors.logoTints.size) % colors.logoTints.size]
    Box(
        modifier
            .clip(RoundedCornerShape(Radius.xs))
            .background(tint),
        contentAlignment = Alignment.Center,
    ) {
        Text(initials, style = CastivioType.labelLarge, color = colors.onBackground)
    }
}

/** Icon + text used inside a now/next line. Kept here so rows and the OSD agree. */
@Composable
fun IconLabel(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    modifier: Modifier = Modifier,
) {
    val colors = CastivioTheme.colors
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Icon(icon, null, tint = colors.onBackgroundMuted, modifier = Modifier.size(14.dp))
        Text(text, style = CastivioType.bodySmall, color = colors.onBackgroundVariant)
    }
}
