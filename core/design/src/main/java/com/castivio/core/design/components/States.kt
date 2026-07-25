package com.castivio.core.design.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.CastivioType
import com.castivio.core.design.theme.LocalPerformanceProfile
import com.castivio.core.design.theme.Motion
import com.castivio.core.design.theme.Radius
import com.castivio.core.design.theme.Sizing
import com.castivio.core.design.theme.Spacing
import kotlinx.coroutines.delay

/**
 * The three states every screen has, and one rule each.
 *
 * These exist as shared components because they are where products get sloppy: a
 * spinner over a populated list, an error that says "something went wrong", an
 * empty screen with no way out. Making each one a component with a *required*
 * action means a screen cannot ship the lazy version of it.
 */

/**
 * A placeholder shaped like the content that is coming.
 *
 * Skeletons rather than a spinner, because the layout should not jump when data
 * arrives — and because a shape that matches the result tells the user what to
 * expect while a spinner tells them only that something is happening.
 *
 * The shimmer is capability-gated: on a low-memory box it is a static block, since
 * an animation that competes with the list it is standing in for is a bad trade.
 */
@Composable
fun Skeleton(
    modifier: Modifier = Modifier,
    height: Dp = 20.dp,
    width: Dp? = null,
    cornerRadius: Dp = Radius.sm,
) {
    val colors = CastivioTheme.colors
    val animate = LocalPerformanceProfile.current.animatedBackdrop

    val shimmer = if (animate) {
        val transition = rememberInfiniteTransition(label = "skeleton")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = SHIMMER_MS, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "shimmer",
        ).value
    } else {
        STATIC_SHIMMER
    }

    // Glass tokens rather than a grey: a skeleton should read as the surface
    // it will become, not as a hole in it.
    val base = colors.glassFill
    val highlight = colors.glassFillStrong

    Box(
        modifier = modifier
            .then(if (width != null) Modifier.width(width) else Modifier.fillMaxWidth())
            .height(height)
            .clip(RoundedCornerShape(cornerRadius))
            .background(
                Brush.horizontalGradient(
                    colorStops = arrayOf(
                        (shimmer - SHIMMER_WIDTH).coerceIn(0f, 1f) to base,
                        shimmer.coerceIn(0f, 1f) to highlight,
                        (shimmer + SHIMMER_WIDTH).coerceIn(0f, 1f) to base,
                    ),
                ),
            )
            // One announcement for a loading region, not one per placeholder.
            .clearAndSetSemantics { },
    )
}

/**
 * A row of card placeholders, sized like the row it stands in for.
 *
 * Focusable by contract: a list that has not loaded yet still accepts focus, so
 * the user's first keypress is never dropped. The placeholders simply have no
 * action until they resolve.
 */
@Composable
fun SkeletonRow(
    modifier: Modifier = Modifier,
    cards: Int = 6,
    cardWidth: Dp = 200.dp,
    cardHeight: Dp = 112.dp,
    label: String? = null,
) {
    Column(
        modifier = modifier.semantics {
            contentDescription = label ?: "Loading"
        },
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Skeleton(width = 160.dp, height = 18.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            repeat(cards) {
                Skeleton(
                    width = cardWidth,
                    height = cardHeight,
                    cornerRadius = Radius.md,
                )
            }
        }
    }
}

/**
 * A spinner for an action the user just took — and only after a delay.
 *
 * Below [SPINNER_DELAY_MS] the work usually finishes first, and a spinner that
 * flashes for 80 ms makes a fast app look unstable. Anything that is not a direct
 * response to a keypress should use [Skeleton] instead.
 */
@Composable
fun DelayedSpinner(
    modifier: Modifier = Modifier,
    label: String? = null,
    delayMs: Long = SPINNER_DELAY_MS,
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(delayMs)
        visible = true
    }
    if (!visible) return

    val colors = CastivioTheme.colors
    Row(
        modifier = modifier.semantics { contentDescription = label ?: "Working" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        androidx.compose.material3.CircularProgressIndicator(
            modifier = Modifier.size(Sizing.iconMd),
            color = colors.accent,
            strokeWidth = 2.dp,
        )
        if (label != null) {
            Text(text = label, style = CastivioType.bodyMedium, color = colors.onBackgroundVariant)
        }
    }
}

/**
 * Something failed, and here is the one thing that helps.
 *
 * The action is required, not optional. "Something went wrong" with no way forward
 * is the error message this component exists to prevent — every failure the app can
 * produce has either a retry, an edit, or a way back.
 */
@Composable
fun ErrorState(
    title: String,
    detail: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
) {
    StateBlock(
        title = title,
        detail = detail,
        icon = icon,
        tint = CastivioTheme.colors.danger,
        modifier = modifier,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            CastivioButton(text = actionLabel, onClick = onAction, weight = ButtonWeight.Primary)
            if (secondaryActionLabel != null && onSecondaryAction != null) {
                CastivioButton(
                    text = secondaryActionLabel,
                    onClick = onSecondaryAction,
                    weight = ButtonWeight.Secondary,
                )
            }
        }
    }
}

/**
 * There is nothing here, and here is how to change that.
 *
 * Same contract as [ErrorState]: an empty screen without an action is a dead end,
 * and on a remote a dead end means pressing Back and guessing.
 */
@Composable
fun EmptyState(
    title: String,
    detail: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    StateBlock(
        title = title,
        detail = detail,
        icon = icon,
        tint = CastivioTheme.colors.onBackgroundVariant,
        modifier = modifier,
    ) {
        CastivioButton(text = actionLabel, onClick = onAction, weight = ButtonWeight.Primary)
    }
}

@Composable
private fun StateBlock(
    title: String,
    detail: String,
    icon: ImageVector?,
    tint: Color,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit,
) {
    val colors = CastivioTheme.colors
    Column(
        modifier = modifier.padding(Spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null, // the title says it; this would repeat it
                tint = tint,
                modifier = Modifier.size(Sizing.iconXl),
            )
        }
        Text(text = title, style = CastivioType.titleMedium, color = colors.onBackground)
        Text(
            text = detail,
            style = CastivioType.bodyMedium,
            color = colors.onBackgroundVariant,
            textAlign = TextAlign.Center,
        )
        actions()
    }
}

/**
 * Below this, the work usually finishes first and the spinner only flashes.
 * Matches the 300 ms in the UI architecture.
 */
const val SPINNER_DELAY_MS = 300L

private const val SHIMMER_MS = Motion.deliberate * 2
private const val SHIMMER_WIDTH = 0.25f

/** Where the static highlight sits when animation is off: a fixed sheen, not flat grey. */
private const val STATIC_SHIMMER = 0.5f
