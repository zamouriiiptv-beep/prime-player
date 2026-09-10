package com.castivio.feature.player

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.castivio.core.design.theme.CastivioMetrics
import com.castivio.core.design.theme.CastivioReference
import com.castivio.core.design.theme.Sizing
import com.castivio.core.design.theme.boundedFraction
import com.castivio.core.design.theme.castivioMetrics

/**
 * What the player's chrome is drawn from: the shared metrics, plus the sizes only a
 * transport bar has — a play control, a scrubber, a caption's clearance, a side sheet.
 *
 * ## What it replaced
 *
 * Eight `if (CastivioTheme.device.isTv)` branches and thirteen constants. The branches
 * chose a gap, a play control, a strip height, a caption clearance, a sheet width and a
 * screen inset by asking what kind of box this is; the constants chose everything else
 * by asking nothing at all — a 3dp progress line and a 14dp scrubber thumb were the
 * same on a 360dp handset and on a 55-inch television watched from three metres.
 *
 * Every size below is now a share of the axis it spends, clamped at both ends, through
 * the one expression the whole product uses:
 * [com.castivio.core.design.theme.boundedFraction]. The shares are read off the 1280×720
 * reference, which is the 960×540 television drawing at 4/3 — so a television reproduces
 * the numbers its drawing was approved at: a 80dp play control, a 56dp strip, 16 and 24
 * of bar gap, and 196dp of clearance under a caption.
 *
 * ## Why this arrives through a composition local
 *
 * The chrome is one screen whose parts are twenty private composables deep — a bar, a
 * cluster, a strip, a scrubber, a sheet — and threading a metrics parameter through all
 * of them would be twenty signatures carrying a value none of them chooses. The four
 * helpers this replaces were already `@Composable` reads of an ambient
 * (`CastivioTheme.device`), so this is the same shape of dependency with the surface in
 * it instead of the device's name.
 *
 * It is provided **once**, at [PlayerScreen]'s own `BoxWithConstraints`, from the
 * measured surface. The default is the reference geometry, so a composable rendered
 * outside the player draws something sane rather than throwing.
 */
@Immutable
internal data class PlayerMetrics(
    /** The stage, the header and the four type steps — the whole product's. */
    val frame: CastivioMetrics,
    /* the transport bar */
    val barGap: Dp,
    val barGapLarge: Dp,
    val play: Dp,
    val strip: Dp,
    /* the scrubber */
    val track: Dp,
    val thumb: Dp,
    val progressWidth: Dp,
    val progressHeight: Dp,
    /* what a skeleton stands in for */
    val skeletonTitle: Dp,
    val skeletonWindow: Dp,
    /* the transients */
    val spinner: Dp,
    val reportHeight: Dp,
    /* captions */
    val captionSide: Dp,
    val captionEdge: Dp,
    val captionClearance: Dp,
    val captionRaise: Dp,
    /**
     * How much of the width the side sheet takes.
     *
     * A fraction because that is what `fillMaxWidth` wants, and a **bounded** one because
     * a fraction alone is the unbounded case the sizing system forbids: 0.40 of a 1920dp
     * television is a 768dp sheet over the film. It is computed from a bounded width, so
     * the sheet stops growing at [SHEET_MAX] and the fraction falls away underneath it.
     */
    val sheetFraction: Float,
) {
    /**
     * The inset every control keeps from the edge of the picture.
     *
     * This is the stage's own margin — `edge` — and on a television that is also the
     * overscan allowance, which is the same number for the same reason: a broadcast
     * panel crops its edges, and content inside the margin is content that survives it.
     */
    val inset: Dp get() = frame.edge
}

/**
 * The player's numbers for a measured surface.
 *
 * `width` and `height` are what the root `BoxWithConstraints` reports — the window the
 * player fills, not the letterboxed picture inside it. The chrome is laid out inside the
 * picture, but what decides how large a control should be is how far away the viewer is
 * and how much screen there is, and that is the window.
 */
internal fun playerMetricsFor(tv: Boolean, width: Dp, height: Dp): PlayerMetrics {
    val sheet = width.boundedFraction(SHEET, SHEET_MIN, SHEET_MAX)
    val target = Sizing.minTarget(tv)
    val inset = castivioMetrics(width, height, tv).edge
    val barGap = width.boundedFraction(BAR_GAP, 8.dp, 22.dp)
    val strip = maxOf(height.boundedFraction(STRIP, 44.dp, 64.dp), target)
    return PlayerMetrics(
        frame = castivioMetrics(width, height, tv),
        barGap = barGap,
        barGapLarge = width.boundedFraction(BAR_GAP_LARGE, 14.dp, 32.dp),
        // The one control a thumb finds without looking and a remote lands on first, so
        // its floor is well above the device's own target rather than equal to it.
        play = maxOf(height.boundedFraction(PLAY, 64.dp, 88.dp), target),
        strip = strip,
        track = height.boundedFraction(TRACK, 3.dp, 6.dp),
        thumb = height.boundedFraction(THUMB, 12.dp, 20.dp),
        progressWidth = width.boundedFraction(PROGRESS_WIDTH, 80.dp, 140.dp),
        progressHeight = height.boundedFraction(PROGRESS_HEIGHT, 2.dp, 5.dp),
        skeletonTitle = width.boundedFraction(SKELETON_TITLE, 120.dp, 220.dp),
        skeletonWindow = width.boundedFraction(SKELETON_WINDOW, 76.dp, 140.dp),
        spinner = height.boundedFraction(SPINNER, 44.dp, 76.dp),
        reportHeight = height.boundedFraction(REPORT_HEIGHT, 140.dp, 260.dp),
        captionSide = width.boundedFraction(CAPTION_SIDE, 24.dp, 48.dp),
        captionEdge = height.boundedFraction(CAPTION_EDGE, 20.dp, 40.dp),
        // Derived from the chrome it has to clear rather than declared beside it —
        // see [CAPTION_AIR] for why that stopped being a detail.
        captionClearance = inset + strip + barGap + target +
            height.boundedFraction(CAPTION_AIR, 12.dp, 30.dp),
        captionRaise = height.boundedFraction(CAPTION_RAISE, 56.dp, 100.dp),
        sheetFraction = (sheet / width).coerceIn(0f, 1f),
    )
}

/** The reference geometry, for anything composed outside the player's own root. */
internal val LocalPlayerMetrics: ProvidableCompositionLocal<PlayerMetrics> =
    staticCompositionLocalOf {
        playerMetricsFor(
            tv = false,
            width = CastivioReference.Width,
            height = CastivioReference.Height,
        )
    }

/* ------------------------------------------------------------------ the shares
 *
 * Read off the 1280×720 reference, which is the 960×540 television drawing at 4/3 —
 * so a television reproduces the numbers its drawing was approved at. Where a value was
 * one constant for every device, the share is chosen so the reference handset lands
 * within a dp or two of what that constant already drew, and the television — which the
 * constant was always wrong for — is the surface that moves.
 */

private const val BAR_GAP = 21.3f / 1280f
private const val BAR_GAP_LARGE = 32f / 1280f
private const val PLAY = 106.7f / 720f
private const val STRIP = 74.7f / 720f
private const val TRACK = 5.3f / 720f
private const val THUMB = 18.7f / 720f
private const val PROGRESS_WIDTH = 128f / 1280f
private const val PROGRESS_HEIGHT = 4f / 720f
private const val SKELETON_TITLE = 197.3f / 1280f
private const val SKELETON_WINDOW = 122.7f / 1280f
private const val SPINNER = 69.3f / 720f
private const val REPORT_HEIGHT = 240f / 720f
private const val CAPTION_SIDE = 42.7f / 1280f
private const val CAPTION_EDGE = 37.3f / 720f

/**
 * The air above the chrome that a caption keeps, and the reason the clearance is a sum.
 *
 * ## A share of the height was the wrong shape for this number
 *
 * The clearance was two constants, 148dp on a handset and 196 on a television, and the
 * first thing tried here was one share landing on both. It does land on both — and it is
 * still wrong, because what a caption has to clear is not a share of the height, it is
 * *the chrome*: an inset, a tools row, a gap and a timeline row, each of which is now its
 * own bounded share. Two independent shares that have to stay in a relationship will
 * leave it, and this pair did: at 873×393 the share came out at 142dp against 152 of
 * chrome, so a caption would have sat **on** the timeline — on the reference handset,
 * which is the surface the 148 was drawn for.
 *
 * It was caught by asserting the relationship rather than the number, which is the whole
 * argument for gating a ratio instead of a constant.
 *
 * So the clearance is the sum of what it clears, plus this. A television still lands on
 * 196 exactly — 46 + 56 + 16 + 56 + 22 — and every other surface clears its own chrome by
 * construction rather than by coincidence.
 */
private const val CAPTION_AIR = 29.3f / 720f

/** How much higher Raised sits: above a film's own burnt-in text along the bottom edge. */
private const val CAPTION_RAISE = 90.7f / 720f

/**
 * The side sheet, as a share of the width with a ceiling on the result.
 *
 * The share is the handset's own 0.52, so a phone draws the sheet it always drew. What
 * is new is the ceiling: a fraction with nothing above it gave a 1920dp television a
 * 768dp sheet, which is the sizing system's second rule broken in the one place on this
 * screen where covering the film actually costs something.
 */
private const val SHEET = 0.52f
private val SHEET_MIN = 340.dp
private val SHEET_MAX = 440.dp
