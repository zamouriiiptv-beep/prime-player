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

    /** A place inside a place: the folder a picker lists other things in. */
    val Folder: ImageVector = strokeIcon(
        "Folder",
        "M3 7a2 2 0 0 1 2-2h3.6l2 2H19a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z",
    )

    /**
     * The folder above this one.
     *
     * A folder with a way out of it rather than a bare chevron: everything else in a
     * picker's list is a folder or a file, and the row that leaves reads as one of
     * them at a glance instead of as a control that wandered in.
     */
    val FolderUp: ImageVector = strokeIcon(
        "FolderUp",
        "M3 7a2 2 0 0 1 2-2h3.6l2 2H19a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z",
        "M12 16.5v-5",
        "M9.5 14L12 11.5l2.5 2.5",
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

    /* ------------------------------------------------------------------ the player
     *
     * Drawn rather than taken from Material for the reason the four above are, plus one
     * that only applies here: over video. A filled glyph on a bright frame is a black
     * blob, and a stroked one at this weight stays legible on snow and on night without a
     * plate behind it — which is what lets the player's controls be glass instead of
     * boxes.
     *
     * The paths are `design/mockups/video-player.html`'s, character for character. Two of
     * them are there because the first attempt was misread in review and the drawing
     * records why: the quality glyph was a star, which everybody read as "favourite", and
     * is now a ladder of bars; the subtitle-size glyph was two Fs, which read as the word
     * "FF", and is now a small A beside a large one.
     */

    val Play: ImageVector = strokeIcon("Play", "M8 5.5v13l11-6.5z", size = Sizing.iconMd)
    val Pause: ImageVector = strokeIcon("Pause", "M9 5.5v13M15 5.5v13", size = Sizing.iconMd)

    val Previous: ImageVector = strokeIcon(
        "Previous", "M18 5.5v13L8 12z", "M6 5.5v13", size = Sizing.iconMd,
    )
    val Next: ImageVector = strokeIcon(
        "Next", "M6 5.5v13L16 12z", "M18 5.5v13", size = Sizing.iconMd,
    )

    /** Ten seconds back, and ten forward. The figure is drawn by the caller, inside the arc. */
    val Replay10: ImageVector = strokeIcon(
        "Replay10", "M11 5.5a7 7 0 1 0 7 7", "M11 2.5L8 5.5l3 3", size = Sizing.iconMd,
    )
    val Forward10: ImageVector = strokeIcon(
        "Forward10", "M13 5.5a7 7 0 1 1-7 7", "M13 2.5l3 3-3 3", size = Sizing.iconMd,
    )

    val Subtitles: ImageVector = strokeIcon(
        "Subtitles",
        "M3 5.5h18a2.5 2.5 0 0 1 0 0v13a2.5 2.5 0 0 1-2.5 2.5H5.5A2.5 2.5 0 0 1 3 18.5z",
        "M10 10.5a2.5 2.5 0 1 0 0 3",
        "M16.5 10.5a2.5 2.5 0 1 0 0 3",
        size = Sizing.iconMd,
    )

    val AudioTrack: ImageVector = strokeIcon(
        "AudioTrack",
        "M9 17.5V5l11-2v12.5",
        "M4 17.5a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0-5 0",
        "M15 15.5a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0-5 0",
        size = Sizing.iconMd,
    )

    val Speed: ImageVector = strokeIcon(
        "Speed", "M4.5 18a8.5 8.5 0 1 1 15 0", "M12 12.5l4-3.5", size = Sizing.iconMd,
    )

    val Aspect: ImageVector = strokeIcon(
        "Aspect", "M3 6h18v12H3z", "M7 10v4M17 10v4", size = Sizing.iconMd,
    )

    val Fullscreen: ImageVector = strokeIcon(
        "Fullscreen",
        "M4 9V5.5A1.5 1.5 0 0 1 5.5 4H9",
        "M15 4h3.5A1.5 1.5 0 0 1 20 5.5V9",
        "M20 15v3.5a1.5 1.5 0 0 1-1.5 1.5H15",
        "M9 20H5.5A1.5 1.5 0 0 1 4 18.5V15",
        size = Sizing.iconMd,
    )

    val Channels: ImageVector = strokeIcon(
        "Channels", "M4 6h16M4 12h16M4 18h10", size = Sizing.iconMd,
    )
    val Guide: ImageVector = strokeIcon(
        "Guide", "M3 5h18v14H3z", "M3 9.5h18", "M9 9.5V19", size = Sizing.iconMd,
    )

    /**
     * Video quality: a ladder of bars, not a star.
     *
     * The first draft was a star, and every reviewer read it as "add to favourites" —
     * which is a different control that will exist on this screen. Bars of rising height
     * are the equaliser/quality idiom and cannot be confused with an approval.
     */
    val Quality: ImageVector = strokeIcon(
        "Quality", "M5 19v-4", "M10 19V9", "M15 19V5", "M20 19v-8", size = Sizing.iconMd,
    )

    val More: ImageVector = strokeIcon(
        "More",
        "M12 4.1a1.4 1.4 0 1 0 0 2.8a1.4 1.4 0 1 0 0-2.8",
        "M12 10.6a1.4 1.4 0 1 0 0 2.8a1.4 1.4 0 1 0 0-2.8",
        "M12 17.1a1.4 1.4 0 1 0 0 2.8a1.4 1.4 0 1 0 0-2.8",
        size = Sizing.iconMd,
    )

    val Close: ImageVector = strokeIcon(
        "Close", "M6 6l12 12M18 6L6 18", size = Sizing.iconMd,
    )

    val Lock: ImageVector = strokeIcon(
        "Lock", "M5 10.5h14v9.5H5z", "M8.5 10.5V8a3.5 3.5 0 0 1 7 0v2.5", size = Sizing.iconMd,
    )
    val Unlock: ImageVector = strokeIcon(
        "Unlock",
        "M5 10.5h14v9.5H5z",
        "M8.5 10.5V8a3.5 3.5 0 0 1 6.8-1.2",
        size = Sizing.iconMd,
    )

    val Cast: ImageVector = strokeIcon(
        "Cast",
        "M3 18.5a2.5 2.5 0 0 1 2.5 2.5",
        "M3 14.5a6.5 6.5 0 0 1 6.5 6.5",
        "M3 10.5a10.5 10.5 0 0 1 10.5 10.5",
        "M7 6h12a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2h-3",
        size = Sizing.iconMd,
    )

    val PictureInPicture: ImageVector = strokeIcon(
        "PictureInPicture", "M3 5h18v14H3z", "M12 11h7v6h-7z", size = Sizing.iconMd,
    )

    /** Back to the live edge: the broadcast mark, which is the same one the LIVE pill uses. */
    val Live: ImageVector = strokeIcon(
        "Live",
        "M12 9a3 3 0 1 0 0 6a3 3 0 1 0 0-6",
        "M7.8 7.8a6 6 0 0 0 0 8.4",
        "M16.2 16.2a6 6 0 0 0 0-8.4",
        "M5 5a10 10 0 0 0 0 14",
        "M19 19a10 10 0 0 0 0-14",
        size = Sizing.iconMd,
    )

    /** The engine, and the retry: a clock face, because both are about a second attempt. */
    val Engine: ImageVector = strokeIcon(
        "Engine", "M12 3.5a8.5 8.5 0 1 1-8.5 8.5", "M12 8v4.5l3 2", size = Sizing.iconMd,
    )
    val Retry: ImageVector = strokeIcon(
        "Retry", "M4 12a8 8 0 1 1 2.4 5.7", "M4 7.5V12h4.5", size = Sizing.iconMd,
    )

    val Tick: ImageVector = strokeIcon(
        "Tick", "M5 12.5l4.5 4.5L19 7.5", size = Sizing.iconSm,
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
