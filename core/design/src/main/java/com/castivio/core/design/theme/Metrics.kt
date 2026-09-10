package com.castivio.core.design.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Castivio's sizing system: **one composition, bounded responsive dimensions,
 * functional adaptation.**
 *
 * ## What this is, and what it deliberately is not
 *
 * Every Castivio screen is *one* drawing. A phone, a tablet, a television, a stick —
 * all four get the same components in the same order with the same hierarchy, and the
 * device adapts to the design rather than the design rebuilding itself per device.
 * What changes with the surface is **size**, and every size here has a floor and a
 * ceiling.
 *
 * It is not a global scale. There is no `screenWidth × factor` applied to the whole
 * tree, and no full-screen transform: those preserve the picture and destroy the
 * thing a picture is for — a 48dp control multiplied by 0.55 is 26dp, which is still
 * a correct-looking drawing of a button nobody can press. Each dimension below is
 * derived from the axis it actually belongs to and then clamped, so a small surface
 * gets a *tighter* Castivio rather than a shrunken one.
 *
 * ## The reference, and what it is for
 *
 * 1280×720, 16:9. It is the geometry the screens are designed and judged at, and the
 * fractions below are read off it — which is why a 960×540 television reproduces the
 * approved television drawing exactly, to the dp. It is **not** a canvas that gets
 * scaled onto the device.
 *
 * ## The three rules a caller has to know
 *
 *  1. **Composition never changes.** Four section cards are four section cards on
 *     every surface. No breakpoint here returns a different number of columns, a
 *     different order, or a different set of components, and nothing in this file
 *     could express one — it returns sizes, not layouts.
 *  2. **Every dimension is bounded.** `MIN` and `MAX` on each, chosen so the smallest
 *     surface stays usable and the largest stays proportionate.
 *  3. **Interaction adapts, appearance does not.** [touchTarget] is the one value
 *     that asks what the device *is*, because a thumb and a D-pad want different
 *     minimums, and that is a functional difference rather than a visual one.
 */
@Immutable
data class CastivioMetrics(
    /* the stage */
    val edge: Dp,
    val stageTop: Dp,
    val stageBottom: Dp,
    /* the header */
    val header: Dp,
    val headGap: Dp,
    val brand: Dp,
    /* the furniture */
    val chip: Dp,
    val chipPad: Dp,
    val bandTop: Dp,
    val radius: Dp,
    /**
     * The smallest box that may receive a press or a D-pad landing.
     *
     * A floor and never a fraction: 48dp for a thumb, 56dp for a remote. This is the
     * only value on the whole type that asks what kind of device it is on, and it is
     * the functional adaptation the design system permits — the pill a reader *sees*
     * is [chip], which is responsive, and the box a finger *hits* is this, which is
     * not allowed to shrink whatever the surface does.
     */
    val touchTarget: Dp,
    /* the four type steps, in dp so a screen can hand them to `castivio*Style`,
       which emits sp — text is never sized in px or in raw pixels anywhere */
    val fsTitle: Dp,
    val fsLabel: Dp,
    val fsBody: Dp,
    val fsChip: Dp,
)

/**
 * The metrics for a surface, bounded.
 *
 * `width` and `height` are the measured surface — what a `BoxWithConstraints` around
 * the screen's own content reports, not the window and not the display. Reading the
 * window measures a stage the screen is never given.
 *
 * Horizontal things are read off the width and vertical things off the height,
 * because those are the axes they actually spend. The type steps come off the height
 * for the same reason the reference is 16:9: it is the dimension that runs out on a
 * landscape-locked app, so it is the honest measure of how much room a line of text
 * has to live in.
 */
fun castivioMetrics(width: Dp, height: Dp, isTv: Boolean): CastivioMetrics =
    CastivioMetrics(
        edge = width.fraction(EDGE, EDGE_MIN, EDGE_MAX),
        stageTop = height.fraction(STAGE_TOP, STAGE_MIN, STAGE_MAX),
        stageBottom = height.fraction(STAGE_BOTTOM, STAGE_MIN, STAGE_MAX),
        header = height.fraction(HEADER, HEADER_MIN, HEADER_MAX),
        headGap = width.fraction(HEAD_GAP, HEAD_GAP_MIN, HEAD_GAP_MAX),
        brand = height.fraction(BRAND, BRAND_MIN, BRAND_MAX),
        chip = height.fraction(CHIP, CHIP_MIN, CHIP_MAX),
        chipPad = width.fraction(CHIP_PAD, CHIP_PAD_MIN, CHIP_PAD_MAX),
        bandTop = height.fraction(BAND_TOP, BAND_MIN, BAND_MAX),
        radius = height.fraction(RADIUS, RADIUS_MIN, RADIUS_MAX),
        touchTarget = Sizing.minTarget(isTv),
        fsTitle = height.fraction(FS_TITLE, FS_TITLE_MIN, FS_TITLE_MAX),
        fsLabel = height.fraction(FS_LABEL, FS_LABEL_MIN, FS_LABEL_MAX),
        fsBody = height.fraction(FS_BODY, FS_BODY_MIN, FS_BODY_MAX),
        fsChip = height.fraction(FS_CHIP, FS_CHIP_MIN, FS_CHIP_MAX),
    )

/** [castivioMetrics] for a measured surface, asking the device what it is. */
@Composable
@ReadOnlyComposable
fun rememberMetrics(
    width: Dp,
    height: Dp,
    isTv: Boolean = CastivioTheme.device.isTv,
): CastivioMetrics = castivioMetrics(width, height, isTv)

/**
 * This many of the axis, but never below [min] and never above [max].
 *
 * The whole of "bounded responsive sizing" in one expression, and the reason it is an
 * expression rather than a table: a table of four device sizes is four things to keep
 * in step, which is what drifted.
 */
private fun Dp.fraction(of: Float, min: Dp, max: Dp): Dp = (this * of).coerceIn(min, max)

/* --------------------------------------------------------------- the fractions
 *
 * Read off the 1280×720 reference, so a 960×540 television lands on the numbers the
 * television drawing was approved at: edge 46, header 54, chip 44, bandTop 22,
 * title 26. Nothing here was chosen twice.
 */

private const val EDGE = 61.3f / 1280f
private const val STAGE_TOP = 32f / 720f
private const val STAGE_BOTTOM = 29.3f / 720f
private const val HEADER = 72f / 720f
private const val HEAD_GAP = 34.7f / 1280f
private const val BRAND = 53.3f / 720f
private const val CHIP = 58.7f / 720f
private const val CHIP_PAD = 14.7f / 1280f
private const val BAND_TOP = 29.3f / 720f
private const val RADIUS = 26.7f / 720f
private const val FS_TITLE = 34.7f / 720f
private const val FS_LABEL = 21.1f / 720f
private const val FS_BODY = 18f / 720f
private const val FS_CHIP = 17.3f / 720f

/* ------------------------------------------------------------------ the bounds
 *
 * A floor so the shortest surface this ships to — 800×360 — stays legible and
 * pressable, and a ceiling so a 4K set does not draw a chip the size of a card.
 * The type floors are the tightest of them: 11sp is the smallest label Castivio
 * will draw at any size, whatever the arithmetic asks for.
 */

private val EDGE_MIN = 24.dp
private val EDGE_MAX = 72.dp
private val STAGE_MIN = 12.dp
private val STAGE_MAX = 40.dp
private val HEADER_MIN = 40.dp
private val HEADER_MAX = 88.dp
private val HEAD_GAP_MIN = 12.dp
private val HEAD_GAP_MAX = 40.dp
private val BRAND_MIN = 28.dp
private val BRAND_MAX = 60.dp
private val CHIP_MIN = 32.dp
private val CHIP_MAX = 64.dp
private val CHIP_PAD_MIN = 8.dp
private val CHIP_PAD_MAX = 18.dp
private val BAND_MIN = 8.dp
private val BAND_MAX = 32.dp
private val RADIUS_MIN = 14.dp
private val RADIUS_MAX = 28.dp
private val FS_TITLE_MIN = 18.dp
private val FS_TITLE_MAX = 36.dp
private val FS_LABEL_MIN = 13.dp
private val FS_LABEL_MAX = 22.dp
private val FS_BODY_MIN = 12.dp
private val FS_BODY_MAX = 19.dp
private val FS_CHIP_MIN = 11.dp
private val FS_CHIP_MAX = 18.dp

/**
 * The reference geometry, for the tests and for anyone judging a drawing against it.
 *
 * Not used to scale anything: it is what the fractions above were read off, and what
 * a mockup is rendered at.
 */
object CastivioReference {
    val Width: Dp = 1280.dp
    val Height: Dp = 720.dp
}

/**
 * What artwork may not be stretched out of.
 *
 * A section plate, a channel frame, a thumbnail: all 16:9, the ratio the reference is
 * and the ratio the content actually is. Responsive sizing decides how *large* one of
 * these is; it is never allowed to decide how *shaped* it is.
 */
const val CASTIVIO_ARTWORK_ASPECT = 16f / 9f
