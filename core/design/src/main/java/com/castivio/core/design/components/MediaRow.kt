package com.castivio.core.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.CastivioType
import com.castivio.core.design.theme.Spacing
import com.castivio.core.design.theme.rowEdgeFadeColor
import java.util.Locale

/**
 * A section heading that carries its cached count — `UI_ARCHITECTURE.md` §3.3.
 *
 * The count is rendered from a value the caller already holds; this component never
 * counts anything. It does not animate on arrival either — a changed count is the
 * state holder's business, not the header's.
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    count: Int? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = CastivioTheme.colors
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Text(title, style = CastivioType.titleLarge, color = colors.onBackground)
        if (count != null) {
            Text(
                "· ${formatCount(count)}",
                style = CastivioType.bodyMedium,
                color = colors.onBackgroundMuted,
            )
        }
        if (trailing != null) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { trailing() }
        }
    }
}

/**
 * A titled, horizontally scrolling row.
 *
 * Lazy and keyed, so recycling works and a card's identity survives the list
 * changing under it. The trailing edge fades into the background, the standard
 * ten-foot cue that the row continues past the safe area.
 */
@Composable
fun <T> MediaRow(
    title: String,
    items: List<T>,
    key: (T) -> Any,
    modifier: Modifier = Modifier,
    count: Int? = null,
    edgeInset: Dp = Spacing.screen,
    itemSpacing: Dp = Spacing.md,
    itemContent: @Composable (T) -> Unit,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        SectionHeader(
            title = title,
            count = count,
            modifier = Modifier.padding(horizontal = edgeInset),
        )
        Box {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(itemSpacing),
                contentPadding = PaddingValues(horizontal = edgeInset),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(items, key = key) { item -> itemContent(item) }
            }
            // Continuation cue, not a clipped layout: focus always scrolls a card
            // back inside the safe area before it can be acted on.
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(edgeInset)
                    .background(
                        Brush.horizontalGradient(
                            listOf(Color.Transparent, rowEdgeFadeColor.copy(alpha = 0.8f)),
                        ),
                    ),
            )
        }
    }
}

/** A single caption line under a card, ellipsised. Shared so rows agree. */
@Composable
fun CardCaption(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = CastivioType.bodySmall,
        color = CastivioTheme.colors.onBackgroundMuted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/**
 * Groups a large integer for display: 12480 → "12,480".
 *
 * The one place counts are formatted, so every header groups the same way.
 */
fun formatCount(value: Int): String = String.format(Locale.getDefault(), "%,d", value)
