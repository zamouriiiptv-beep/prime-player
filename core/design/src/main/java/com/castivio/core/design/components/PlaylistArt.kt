package com.castivio.core.design.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.CastivioType
import com.castivio.core.design.theme.LocalPerformanceProfile
import com.castivio.core.design.theme.Palette

/**
 * A television with a playlist in front of it: what adding a playlist *is*, drawn.
 *
 * ## Why a drawing and not a picture
 *
 * A raster at the sizes this is shown at — a 404dp set on a television, a quarter of
 * that on a short phone — is either a 2 MB asset in five densities or a soft one. It
 * is also a file nobody can re-tint, and the two grounds Castivio ships are a re-tint:
 * every colour below is a palette entry, so the illustration follows the theme the way
 * the rest of the application does.
 *
 * ## Why it is in `:core:design`
 *
 * Invariant 1: a feature may not name a colour, and this is thirty of them. The screen
 * that shows it brings the words — see [labels] — and the design system brings the
 * drawing.
 *
 * ## It is a picture, so it does not mirror
 *
 * The composition is pinned to [LayoutDirection.Ltr]. A photograph of a television is
 * not a paragraph: flipping it in Arabic would put the set's own menu on the other
 * side of its own screen, which is a different drawing rather than the same drawing
 * read the other way. The screen *around* it mirrors exactly as it should, and that is
 * the line — layout mirrors, artwork does not.
 *
 * ## The aspect is fixed and the box is not
 *
 * The caller gives this whatever space is left over after the words below it, which on
 * a short surface is a wide strip and on a television is nearly a square. So the
 * drawing takes the largest [ART_RATIO] rectangle that fits and centres it, rather than
 * stretching — a set 404 wide and 228 tall is a set, and the same set stretched is a
 * fault a reader notices without being able to name.
 *
 * @param labels the four rows on the set's own menu, in the reader's language. Four
 *   are drawn; a shorter list draws what it has, a longer one is cut. They are the
 *   caller's because `:core:design` has no strings and must not grow any.
 */
@Composable
fun CastivioPlaylistArt(
    labels: List<String>,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        // One announcement for the whole illustration, and it is none: everything it
        // says is said in words immediately below it, and a reader who hears the set's
        // four menu rows read out has been told about a drawing instead of about the
        // screen they are on.
        modifier = modifier.clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        // The side of the drawing, which every dimension below is a share of. Taken
        // from whichever axis runs out first so the rectangle fits both.
        val unit = minOf(maxWidth, maxHeight / ART_RATIO)

        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Box(Modifier.width(unit).height(unit * ART_RATIO)) {
                Ring()
                TelevisionSet(unit, labels)
                PlaylistSheet(unit)
            }
        }
    }
}

/** The lit ellipse the set stands on: a stage, and the only thing here that glows. */
@Composable
private fun Ring() {
    val colors = CastivioTheme.colors
    // The halo is five strokes, which is five draw calls per frame on a surface that
    // never animates -- but a cheap box draws this behind a form on first paint, and
    // the performance profile is the project's one answer to "how much may a
    // decoration cost here".
    val halo = LocalPerformanceProfile.current.animatedBackdrop
    val line = colors.accent.copy(alpha = RING_INK)

    Canvas(Modifier.fillMaxSize()) {
        val cx = size.width * RING_CX
        val cy = size.height * RING_CY
        val w = size.width * RING_W
        val h = size.width * RING_H

        if (halo) {
            repeat(HALO_STEPS) { i ->
                val step = (i + 1).toFloat()
                oval(
                    cx, cy,
                    w + w * HALO_SPREAD * step,
                    h + h * HALO_SPREAD * step,
                    colors.accent.copy(alpha = RING_INK * HALO_FADE / step),
                    size.width * RING_STROKE,
                )
            }
        }
        oval(cx, cy, w, h, line, size.width * RING_STROKE)
    }
}

/** An ellipse of [w] by [h] centred on [cx], [cy], outlined. */
private fun DrawScope.oval(cx: Float, cy: Float, w: Float, h: Float, ink: Color, stroke: Float) {
    drawOval(
        color = ink,
        topLeft = Offset(cx - w / 2f, cy - h / 2f),
        size = Size(w, h),
        style = Stroke(width = stroke),
    )
}

/** The set: a bezel, a screen with its own menu down one side, and two feet. */
@Composable
private fun TelevisionSet(unit: Dp, labels: List<String>) {
    Column(
        modifier = Modifier
            .offset(x = unit * TV_X, y = unit * TV_Y)
            .width(unit * TV_W),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(unit * TV_BEZEL_H)
                .clip(RoundedCornerShape(unit * TV_BEZEL_R))
                .background(Brush.linearGradient(listOf(Palette.Haze, Palette.Deep)))
                .padding(unit * TV_BEZEL_PAD),
        ) {
            Row(
                Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(unit * TV_SCREEN_R))
                    .background(
                        Brush.linearGradient(
                            listOf(Palette.Violet10, Palette.Violet40, Palette.Violet50, Palette.Violet60),
                        ),
                    ),
            ) {
                Rail(unit, labels)
                Scene(Modifier.weight(1f).fillMaxHeight())
            }
        }
        Foot(unit, TV_STAND_W, TV_STAND_H)
        Foot(unit, TV_FOOT_W, TV_FOOT_H)
    }
}

/**
 * The menu on the set's own screen: what Castivio looks like from across the room.
 *
 * The first row is drawn as the chosen one, because a set showing nothing selected is
 * a set that is off — and the whole claim of the drawing is that this link turns it on.
 */
@Composable
private fun Rail(unit: Dp, labels: List<String>) {
    val colors = CastivioTheme.colors
    Column(
        Modifier
            .fillMaxHeight()
            .width(unit * TV_SCREEN_W * RAIL_OF_SCREEN)
            .background(Palette.Ink.copy(alpha = RAIL_INK))
            .padding(horizontal = unit * RAIL_PAD_H, vertical = unit * RAIL_PAD_V),
        verticalArrangement = Arrangement.spacedBy(unit * RAIL_GAP),
    ) {
        labels.take(RAIL_ICONS.size).forEachIndexed { index, label ->
            val chosen = index == 0
            val shape = RoundedCornerShape(unit * RAIL_ROW_R)
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(unit * RAIL_ROW_H)
                    .clip(shape)
                    .then(
                        if (chosen) {
                            Modifier
                                .background(colors.accent.copy(alpha = RAIL_CHOSEN_FILL))
                                .border(ART_HAIRLINE, colors.accent.copy(alpha = RAIL_CHOSEN_EDGE), shape)
                        } else {
                            Modifier
                        },
                    )
                    .padding(horizontal = unit * RAIL_ROW_PAD),
                horizontalArrangement = Arrangement.spacedBy(unit * RAIL_ROW_PAD),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = RAIL_ICONS[index],
                    contentDescription = null,
                    tint = if (chosen) Palette.Azure70 else colors.accent,
                    modifier = Modifier.size(unit * RAIL_GLYPH),
                )
                Text(
                    text = label,
                    style = CastivioType.labelSmall.copy(
                        fontSize = (unit * RAIL_TYPE).value.sp,
                        fontWeight = if (chosen) FontWeight.Bold else FontWeight.Normal,
                        letterSpacing = 0.sp,
                    ),
                    color = if (chosen) Palette.White else colors.onBackgroundVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/** What is playing: two ridges against the screen's own light. */
@Composable
private fun Scene(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        ridge(PEAKS_FAR, Palette.Violet10.copy(alpha = PEAK_FAR_INK))
        ridge(PEAKS_NEAR, Palette.Ink.copy(alpha = PEAK_NEAR_INK))
    }
}

/**
 * One ridge, from a run of normalised points.
 *
 * The points are fractions of the scene rather than pixels, so the same ridge is the
 * same drawing whatever the set is scaled to — a path in absolute units would be a
 * mountain range that climbed as the screen grew.
 */
private fun DrawScope.ridge(points: FloatArray, ink: Color) {
    val base = size.height
    val top = size.height * (1f - PEAKS_OF_SCENE)
    val path = Path().apply {
        moveTo(0f, base)
        var i = 0
        while (i < points.size) {
            lineTo(size.width * points[i], top + (base - top) * points[i + 1])
            i += 2
        }
        lineTo(size.width, base)
        close()
    }
    drawPath(path, ink)
}

/** The stand and the plinth, which are the same object at two widths. */
@Composable
private fun Foot(unit: Dp, width: Float, height: Float) {
    Box(
        Modifier
            .width(unit * width)
            .height(unit * height)
            .clip(RoundedCornerShape(unit * TV_FOOT_R))
            .background(Brush.verticalGradient(listOf(Palette.Haze, Palette.Deep))),
    )
}

/** The playlist itself, in front of the set: one file that holds the lot. */
@Composable
private fun PlaylistSheet(unit: Dp) {
    val shape = RoundedCornerShape(unit * SHEET_R)
    Box(
        modifier = Modifier
            .offset(x = unit * SHEET_X, y = unit * SHEET_Y)
            .width(unit * SHEET_W)
            .height(unit * SHEET_H)
            .rotate(SHEET_TILT)
            .clip(shape)
            .background(
                Brush.linearGradient(listOf(Palette.Azure60, Palette.Violet50, Palette.Violet60)),
            ),
        contentAlignment = Alignment.Center,
    ) {
        // The dog-ear: a sheet of something, rather than a tile.
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .size(unit * SHEET_EAR)
                .clip(RoundedCornerShape(topEnd = unit * SHEET_R))
                .background(Palette.White.copy(alpha = SHEET_EAR_INK)),
        )
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(unit * SHEET_GAP),
        ) {
            Box(
                Modifier
                    .size(unit * SHEET_PLAY)
                    .clip(CircleShape)
                    .background(Palette.White.copy(alpha = SHEET_PLAY_INK)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    tint = Palette.White,
                    modifier = Modifier.size(unit * SHEET_PLAY * DISC_ICON),
                )
            }
            Text(
                text = SHEET_MARK,
                style = CastivioType.titleLarge.copy(
                    fontSize = (unit * SHEET_TYPE).value.sp,
                    fontWeight = FontWeight.Bold,
                ),
                color = Palette.White,
                maxLines = 1,
            )
        }
    }
}

/**
 * The format, which is a format and therefore not translated.
 *
 * A string constant rather than a resource for exactly that reason: "M3U" is the same
 * four characters in all thirty-seven languages, and a resource would be thirty-seven
 * invitations for one of them to be something else.
 */
private const val SHEET_MARK = "M3U"

/** The four rows on the set's menu. The caller brings the words; these are the marks. */
private val RAIL_ICONS: List<ImageVector> = listOf(
    Icons.Rounded.LiveTv,
    Icons.Rounded.Movie,
    Icons.Rounded.Tv,
    Icons.Rounded.History,
)

/* ------------------------------------------------------------- the drawing itself
 *
 * Every number is a share of the drawing's own width, read off the approved 575x330
 * illustration. Shares rather than dp for the reason the sizing system exists: the
 * same figures at a fixed size would be a set that filled a television and overran a
 * phone, and the same figures scaled as a group would be the one thing the metric
 * system forbids.
 */

/** 330 over 575: the rectangle the illustration is drawn in. */
private const val ART_RATIO = 330f / 575f

private const val RING_CX = 0.52f
private const val RING_CY = 0.76f
private const val RING_W = 452f / 575f
private const val RING_H = 124f / 575f
private const val RING_STROKE = 2.5f / 575f
private const val RING_INK = 0.50f

private const val HALO_STEPS = 4
private const val HALO_SPREAD = 0.035f
private const val HALO_FADE = 0.42f

private const val TV_X = 96f / 575f
private const val TV_Y = 8f / 575f
private const val TV_W = 404f / 575f
private const val TV_BEZEL_H = 228f / 575f
private const val TV_BEZEL_R = 12f / 575f
private const val TV_BEZEL_PAD = 7f / 575f
private const val TV_SCREEN_R = 7f / 575f

/** The screen inside the bezel: the set's width less the bezel on both sides. */
private const val TV_SCREEN_W = TV_W - 2f * TV_BEZEL_PAD

private const val TV_STAND_W = 92f / 575f
private const val TV_STAND_H = 9f / 575f
private const val TV_FOOT_W = 150f / 575f
private const val TV_FOOT_H = 6f / 575f
private const val TV_FOOT_R = 4f / 575f

private const val RAIL_OF_SCREEN = 0.43f
private const val RAIL_INK = 0.86f
private const val RAIL_PAD_H = 10f / 575f
private const val RAIL_PAD_V = 9f / 575f
private const val RAIL_GAP = 5f / 575f
private const val RAIL_ROW_H = 30f / 575f
private const val RAIL_ROW_R = 7f / 575f
private const val RAIL_ROW_PAD = 8f / 575f
private const val RAIL_GLYPH = 17f / 575f
private const val RAIL_TYPE = 13f / 575f
private const val RAIL_CHOSEN_FILL = 0.16f
private const val RAIL_CHOSEN_EDGE = 0.42f

/** The ridge occupies the lower half of the scene, as it does in the drawing. */
private const val PEAKS_OF_SCENE = 0.52f
private const val PEAK_FAR_INK = 0.72f
private const val PEAK_NEAR_INK = 0.86f

/** x, y pairs, both normalised: x across the scene, y down the ridge's own band. */
private val PEAKS_FAR = floatArrayOf(
    0.217f, 0.364f,
    0.358f, 0.709f,
    0.533f, 0.200f,
    0.742f, 0.782f,
    1.000f, 0.436f,
)
private val PEAKS_NEAR = floatArrayOf(
    0.167f, 0.600f,
    0.383f, 0.873f,
    0.608f, 0.527f,
    0.833f, 0.909f,
    1.000f, 0.673f,
)

private const val SHEET_X = 236f / 575f
private const val SHEET_Y = 122f / 575f
private const val SHEET_W = 178f / 575f
private const val SHEET_H = 188f / 575f
private const val SHEET_R = 22f / 575f
private const val SHEET_EAR = 44f / 575f
private const val SHEET_EAR_INK = 0.34f
private const val SHEET_GAP = 10f / 575f
private const val SHEET_PLAY = 64f / 575f
private const val SHEET_PLAY_INK = 0.30f
private const val SHEET_TYPE = 32f / 575f

/** A few degrees off square: a sheet somebody put down, not a tile in a grid. */
private const val SHEET_TILT = -3f

/** One device pixel. The drawing's own hairline, matching every other edge in it. */
private val ART_HAIRLINE: Dp = 1.dp
