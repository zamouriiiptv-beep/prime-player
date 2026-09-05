package com.castivio.feature.activation

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.castivio.core.design.components.InteractiveGlassCard
import com.castivio.core.design.components.castivioBodyStyle
import com.castivio.core.design.components.castivioDescriptionColor
import com.castivio.core.design.components.rememberThumbnail
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.CastivioType
import com.castivio.core.design.theme.Radius
import com.castivio.core.design.theme.Sizing
import com.castivio.core.design.theme.Spacing
import com.castivio.core.design.theme.castivioStage

/* ===========================================================================
 * What the four cards open, as one shape.
 *
 * The video grid, the audio list and the two file pickers are the same screen with
 * a different box in the middle: a title with the mark in the opposite corner, a
 * glass container, and Back centred between two hairlines. That shell is written
 * once here, and each screen supplies only its content.
 *
 * ## The one way these differ from every screen before them
 *
 * They scroll. A chooser fits by construction — four cards divide a known box — but
 * a library is however many items the device holds, and `CLAUDE.md` sizes this
 * product for 400,000 of them. So the *frame* still never scrolls: the header, the
 * container and Back are fixed, and only the content between them moves. What that
 * buys is the thing the source choice was rebuilt twice to get — Back cannot be
 * pushed off the bottom of a television by a long list.
 * =========================================================================== */

/**
 * One row of a list, whichever list it is.
 *
 * A track in the audio library, a folder in a picker and a file in a picker are the
 * same row with different ink: an icon, a name that takes the width, and a figure at
 * the end. Modelled as one type rather than three because they are laid out
 * identically and any drift between them would be a bug rather than a design.
 */
internal data class MediaRow(
    val name: String,
    /** A duration for a file, a count for a folder. Empty for the parent row. */
    val detail: String,
    val icon: ImageVector,
    /**
     * The file this row stands for, when it stands for one.
     *
     * Null for a folder and for the row that walks up. Where it is present the row shows
     * the file's own cover in place of the glyph — which is the whole difference between a
     * list of names and a list somebody can recognise at a glance.
     */
    val uri: String? = null,
    val albumId: Long? = null,
    /**
     * A place rather than a thing.
     *
     * Only the ink changes — a folder's glyph takes the muted colour — so a glance
     * separates the two halves of a picker's list without reading either.
     */
    val isPlace: Boolean = false,
)

/**
 * One tile in the video grid: artwork, a duration over it, and a name under it.
 *
 * [uri] is what plays and what the thumbnail is read from — the same content URI, because
 * asking the platform for a picture of a file and asking it to play that file are two
 * requests about one thing and a second identifier would be a second thing to get wrong.
 *
 * The name and the duration are **not** derived from it. Both come out of the same
 * `MediaStore` row the URI did, which is why a row can be drawn complete before any
 * picture exists: the reference players that show `loading…` where a name belongs are
 * waiting on a per-file read that Castivio never makes.
 */
internal data class MediaTile(
    val name: String,
    val duration: String,
    val uri: String? = null,
    /** Audio only: where the cover lives on platforms that keep it in the album table. */
    val albumId: Long? = null,
)

/**
 * The shell every library and picker wears: the shared header, then the content.
 *
 * [content] is given the stage's `ColumnScope` and the frame it was chosen on, and is
 * expected to take `Modifier.weight(1f)` — the header is measured first and the content
 * gets what is left, which is what stops a list of four hundred thousand items from
 * pushing anything off the screen. The frame comes with it so a tile can take the
 * stage's own corner instead of a radius chosen beside it.
 *
 * ## What it stopped doing
 *
 * It drew its own header — a title with a corner wordmark at a size only these
 * screens used, no mark, and a `--mark-gap` and `--back-h` of its own — and its own
 * footer, a rule and Back and a rule, inside a glass container that existed to hold
 * the two. Three of those were a second implementation of `CastivioHeader`, and the
 * container was a surface with nothing on it: the grid and the list already carry
 * their own edges.
 *
 * So the shell is now the source choice's, exactly: the stage's margins from the
 * frame, the shared header with Back at its outer end, and the content taking the
 * rest. What these screens keep is what is genuinely theirs — the tile, the row, and
 * the fade at the fold.
 */
@Composable
internal fun MediaScaffold(
    title: String,
    onBack: () -> Unit,
    backTag: String,
    containerTag: String,
    headingTag: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.(SourceMetrics) -> Unit,
) {
    val tv = CastivioTheme.device.isTv
    BoxWithConstraints(modifier.fillMaxSize()) {
        val m = sourceMetricsFor(tv = tv, available = maxHeight)

        Column(
            Modifier
                .fillMaxSize()
                .castivioStage(m.frame)
                .testTag(containerTag),
        ) {
            ChooserHeader(
                m = m,
                title = title,
                headingTag = headingTag,
                backTag = backTag,
                onBack = onBack,
            )
            Spacer(Modifier.height(m.bandTop))
            content(m)
        }
    }
}

/**
 * "There is more below", without a scrollbar — a television has no pointer to show
 * one to, and a partial row at the fold only says it when the arithmetic happens to
 * leave one. On the 827dp frame the picker's rows divide the box almost exactly and
 * the fourth is cut by a pixel, which says nothing at all.
 *
 * An offscreen layer with a `DstIn` gradient is a mask rather than a veil: it fades
 * the *content* out, so what shows through is the container's own glass and the
 * aurora under it. Drawing a solid gradient over the top instead would paint a
 * colour that is not on this screen anywhere.
 */
internal fun Modifier.fadeAtBottom(height: Dp): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val fade = height.toPx().coerceAtMost(size.height)
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color.Black, Color.Transparent),
                startY = size.height - fade,
                endY = size.height,
            ),
            blendMode = BlendMode.DstIn,
        )
    }

/**
 * A list row, drawn by the design system's own interactive glass.
 *
 * `InteractiveGlassCard` carries the focus ring, the glow and the elevation, so a row
 * here and a card on the source choice light up identically under a D-pad — which is
 * the whole reason for not building a second focusable surface.
 *
 * One node, one label: the icon has no description of its own because the row already
 * announces the name and the figure as a single item.
 */
@Composable
internal fun MediaListRow(
    m: SourceMetrics,
    row: MediaRow,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CastivioTheme.colors
    val label = if (row.detail.isEmpty()) row.name else "${row.name}. ${row.detail}"

    InteractiveGlassCard(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .testTag(ActivationTags.BROWSE_ROW)
            .semantics(mergeDescendants = true) { contentDescription = label },
        fill = SolidColor(colors.glassFillStrong),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = Sizing.minTarget(CastivioTheme.device.isTv))
                .padding(horizontal = Spacing.md),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The file's own cover where there is one, the glyph where there is not. The
            // glyph is not a placeholder waiting to be replaced -- most tracks genuinely
            // have no art, and a row that reserved a square for a picture that never comes
            // would be a row with a hole in it.
            val cover by rememberThumbnail(row.uri, RowArtSize, RowArtSize, row.albumId)
            val art = cover
            if (art != null) {
                Image(
                    bitmap = art,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(RowArtSize)
                        .clip(RoundedCornerShape(Radius.xs)),
                )
            } else {
                Icon(
                    imageVector = row.icon,
                    contentDescription = null,
                    tint = if (row.isPlace) colors.onBackgroundVariant else colors.onBackground,
                    modifier = Modifier.size(Sizing.iconXl),
                )
            }
            Text(
                text = row.name,
                style = CastivioType.bodyLarge,
                color = colors.onBackground,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            if (row.detail.isNotEmpty()) {
                Text(
                    text = row.detail,
                    style = castivioBodyStyle(m.fsDetail),
                    color = castivioDescriptionColor,
                    maxLines = 1,
                )
            }
        }
    }
}

/* ------------------------------------------------------------------------ tokens */

/** Between rows, and between tiles across and down alike. */
internal val BrowseItemGap: Dp
    @Composable @ReadOnlyComposable get() =
        if (CastivioTheme.device.isTv) Spacing.sm else Spacing.xs

internal val BrowseTileGap: Dp
    @Composable @ReadOnlyComposable get() =
        if (CastivioTheme.device.isTv) Spacing.lg else Spacing.sm

/**
 * The narrowest a 16:9 tile may be before the picture stops carrying meaning.
 *
 * A minimum rather than a column count, so the grid keeps its tile size and changes
 * how many fit — which is what makes one figure serve a 800dp phone and a 960dp
 * television instead of a table of breakpoints.
 */
internal val BrowseTileMin: Dp
    @Composable @ReadOnlyComposable get() =
        if (CastivioTheme.device.isTv) 240.dp else 150.dp

/** How much of the content the fold eats into. */
internal val BrowseFade = 24.dp

/**
 * The cover on a list row: square, and the height of the row's own target.
 *
 * Square rather than 16:9 because it is album art far more often than it is a video frame,
 * and a cover letterboxed into a widescreen box reads as a mistake.
 */
internal val RowArtSize = 40.dp
