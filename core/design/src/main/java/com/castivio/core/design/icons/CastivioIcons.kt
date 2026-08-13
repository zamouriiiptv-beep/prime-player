package com.castivio.core.design.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import com.castivio.core.design.theme.Sizing

/**
 * The drawn icons — the ones Material does not have in the weight Castivio needs.
 *
 * ## Why these are not `Icons.Rounded`
 *
 * Everything else in the product is, and that rule stands: one family, no mixing.
 * These four are here because of a property the family cannot express rather than
 * because a nicer glyph was wanted.
 *
 * Material's icons are filled shapes. At 20dp that reads correctly beside a 17sp
 * title — it is roughly the same optical weight as the type. The media source screen
 * needs them at [Sizing.iconXl], because at 20 on a television three metres away the
 * difference between a film frame and a folder is a few pixels of outline and the
 * glyph stops carrying meaning. A filled shape scaled to 32 gains area in both
 * dimensions and comes out heavier than the words it labels, and there is no
 * parameter on a filled path to take that back.
 *
 * A stroked path has one. These are drawn on the same 24-unit grid Material uses,
 * with round caps and joins, and their stroke is set so that the *rendered* line is
 * 1.25dp — the same ink a 20dp Material glyph puts down. So the icon is 60% larger
 * and no darker, which is what the design review asked for and the reason this file
 * exists at all.
 *
 * ## Why they live in `:core:design`
 *
 * Invariant 1 keeps colours out of features, and the same argument applies to the
 * shapes: an icon drawn inside a feature is a piece of the visual language that no
 * other screen can reach and that no review of the design system will ever see. The
 * paths are also the mockup's paths, character for character — `design/mockups/
 * media-source.html` draws these four, and keeping the strings identical is what
 * makes the drawing and the screen checkable against each other.
 */
object CastivioIcons {

    /** Everything the device has recorded or downloaded: a film frame, with a play. */
    val VideoLibrary: ImageVector = strokeIcon(
        "VideoLibrary",
        "M5 5h14a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z",
        "M7.5 5v14M16.5 5v14",
        "M10.5 9.5v5l4-2.5z",
    )

    /** Going and finding one, which is what a picker is: a folder, with a play. */
    val VideoFile: ImageVector = strokeIcon(
        "VideoFile",
        "M3 7a2 2 0 0 1 2-2h3.6l2 2H19a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z",
        "M10.5 11.5v4l3.5-2z",
    )

    /** A collection of audio rather than one track: two notes on a beam. */
    val AudioLibrary: ImageVector = strokeIcon(
        "AudioLibrary",
        "M9 17.5V5l11-2v12.5",
        "M4 17.5a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0-5 0",
        "M15 15.5a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0-5 0",
    )

    /**
     * One file, chosen by hand.
     *
     * The same sheet with a folded corner the source choice already uses for a file on
     * this device, with a note in it where that one has a play mark. Two screens, one
     * idea of what a file looks like.
     */
    val AudioFile: ImageVector = strokeIcon(
        "AudioFile",
        "M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z",
        "M14 3v5h5",
        "M14.5 12.2l-4 .9v3.4",
        "M8 16.5a1.3 1.3 0 1 0 2.6 0a1.3 1.3 0 1 0-2.6 0",
    )

    /**
     * Back, at [Sizing.iconMd] rather than [Sizing.iconXl].
     *
     * A control on a button, not a subject, so it stays the size every other button's
     * icon is. Its stroke is computed from *its* size, so it lays down the same 1.25dp
     * as the large four — which is what makes the two read as one family at two sizes
     * instead of as a thin set and a thick set.
     *
     * It points at the leading edge and is drawn that way once. A caller in a
     * right-to-left layout mirrors it; nothing here knows which way round it is.
     */
    val ArrowBack: ImageVector = strokeIcon(
        "ArrowBack",
        "M20 12H5",
        "M11.5 5.5L5 12l6.5 6.5",
        size = Sizing.iconMd,
    )
}

/** Material's grid, so these sit beside `Icons.Rounded` without being rescaled. */
private const val GRID = 24f

/**
 * The rendered line, in dp, at whatever size the icon is drawn.
 *
 * 1.25 is not a taste: it is what `Icons.Rounded` puts down at [Sizing.iconMd], which
 * is the weight every other icon in Castivio already has.
 */
private const val INK = 1.25f

/**
 * Stroke width on the 24-unit grid that renders as [INK] at [size].
 *
 * A vector scales everything, the stroke included, so drawing a 24-unit path in a
 * 32dp box multiplies the line by 32/24 as well. Dividing it back out here is the
 * whole trick, and it is why the four large icons and the small arrow are the same
 * weight on screen despite being different sizes.
 */
private fun inkOn(size: Dp): Float = INK / size.value * GRID

private fun strokeIcon(
    name: String,
    vararg pathData: String,
    size: Dp = Sizing.iconXl,
): ImageVector = ImageVector.Builder(
    name = name,
    defaultWidth = size,
    defaultHeight = size,
    viewportWidth = GRID,
    viewportHeight = GRID,
).apply {
    val width = inkOn(size)
    pathData.forEach { d ->
        addPath(
            pathData = PathParser().parsePathString(d).toNodes(),
            // No fill, and the stroke's own colour is a placeholder: `Icon` tints the
            // whole vector with the content colour, which is how every icon in the
            // product takes its colour from where it is used rather than carrying one.
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = width,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }
}.build()
