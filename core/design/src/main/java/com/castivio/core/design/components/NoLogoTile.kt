package com.castivio.core.design.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.castivio.core.design.theme.CastivioTheme

/** Found by tests: the tile is drawn with no words, no role, nothing a screen reader reads. */
const val NO_LOGO_TILE_TAG = "design.noLogoTile"

/**
 * What a channel that has no logo is drawn with: a rounded tile with the generic picture glyph -- a sun over two hills -- in it.
 *
 * It claims no picture. No initials and no generated tile stand in for a logo here -- that was removed from the list on purpose, because
 * a coloured square with a channel's letters in it is a different picture wearing the logo's place -- and the glyph is the same on every
 * channel: "a picture goes here". What the tile has is a colour. It is one of the palette's four hues ([ArtworkHue]), chosen from
 * [seed], in the tile's tint and its glyph; with no [seed] it is the quiet grey of a note about an absence.
 *
 * Fills the square it is given; [ProviderArtwork] gives it the square its slot allows. Drawn first in
 * `design/mockups/channels-no-logo-ar.html`, in the shape of the owner's reference.
 */
@Composable
fun NoLogoTile(seed: String?, modifier: Modifier = Modifier) {
    val colors = CastivioTheme.colors
    val ink: Color = if (seed.isNullOrBlank()) {
        colors.onBackgroundMuted
    } else {
        when (ArtworkHue.indexOf(seed)) {
            0 -> colors.hueAzure
            1 -> colors.hueViolet
            2 -> colors.hueGreen
            else -> colors.hueAmber
        }
    }
    val shape = RoundedCornerShape(percent = CORNER_PERCENT)
    Box(
        modifier
            .testTag(NO_LOGO_TILE_TAG)
            .clip(shape)
            .background(ink.copy(alpha = TILE_ALPHA))
            .border(1.dp, ink.copy(alpha = EDGE_ALPHA), shape),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize(GLYPH_OF_TILE)) { drawGlyph(ink) }
    }
}

/** The glyph, drawn in a 24-unit square scaled to the canvas: the sun, and the two hills in one path. */
private fun DrawScope.drawGlyph(ink: Color) {
    val unit = size.minDimension / GLYPH_UNITS
    drawCircle(ink, radius = SUN_R * unit, center = Offset(SUN_X * unit, SUN_Y * unit))
    val hills = Path().apply {
        moveTo(HILLS[0] * unit, HILLS[1] * unit)
        var i = 2
        while (i < HILLS.size) {
            lineTo(HILLS[i] * unit, HILLS[i + 1] * unit)
            i += 2
        }
        close()
    }
    drawPath(hills, ink)
}

/** The tile's corner, as a share of its side: a quarter, which is what the reference's is. */
private const val CORNER_PERCENT = 26

/** The tile's tint and its edge, as shares of the hue. */
private const val TILE_ALPHA = 0.18f
private const val EDGE_ALPHA = 0.40f

/** The glyph's square, as a share of the tile's side: three fifths, and the drawing in it is four fifths of that. */
private const val GLYPH_OF_TILE = 0.6f

private const val GLYPH_UNITS = 24f
private const val SUN_X = 16.5f
private const val SUN_Y = 7.5f
private const val SUN_R = 2.6f

/** The hills' outline in the glyph's units: left foot, left summit, saddle, right summit-shoulder, right foot. */
private val HILLS = floatArrayOf(2.5f, 20f, 9.1f, 10.6f, 13.2f, 15.8f, 15.8f, 12.7f, 21.5f, 20f)
