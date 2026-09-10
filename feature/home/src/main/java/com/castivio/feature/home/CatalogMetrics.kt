package com.castivio.feature.home

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.castivio.core.design.theme.CastivioMetrics
import com.castivio.core.design.theme.Sizing
import com.castivio.core.design.theme.boundedFraction
import com.castivio.core.design.theme.castivioMetrics

/**
 * What the catalogue screens are drawn from: the shared metrics, plus the handful of
 * sizes a browsing surface owns — a category pane, a poster grid, a channel row.
 *
 * ## What it replaced
 *
 * Three screens' worth of fixed tokens and one device table. `BrowseScreen`,
 * `ShowScreen` and `CatalogSearchScreen` were built from `Spacing.md`, `Spacing.sm`,
 * `Spacing.gridGutter` and a 46dp constant — the same numbers on a 360dp handset and
 * on a 55-inch television — with exactly two things allowed to vary, both of them read
 * off `DeviceClass`: `screenPadding` (24 or 48) and `gridColumns` (3, 4, 5 or 6).
 *
 * That is the arrangement the sizing system exists to end, and it produced the two
 * defects these screens actually had. A channel row was 50dp tall on every device —
 * **under the 56dp D-pad floor on the only device driven by a D-pad** — and a poster on
 * a 960×540 television came out 81dp wide, because six columns were being cut out of
 * the 596dp left beside a category pane. Neither is visible in any one file; both are
 * arithmetic nobody had done.
 *
 * ## How it works, which is phase 1's rule unchanged
 *
 * Every size below is a share of the axis it spends — horizontal off the width,
 * vertical off the height — clamped at both ends by
 * [com.castivio.core.design.theme.boundedFraction], with the shares read off the
 * 1280×720 reference. There is no device table here and nothing in this type could
 * express one: it returns sizes and two counts, never a layout.
 *
 * @see castivioMetrics for the stage, the header and the four type steps, which these
 *   screens share with Home and with activation rather than restating.
 */
@Immutable
internal data class CatalogMetrics(
    /** The stage, the header and the four type steps — the whole product's. */
    val frame: CastivioMetrics,
    /* the vertical rhythm */
    val bandGap: Dp,
    val rowGap: Dp,
    val gutter: Dp,
    val listBottom: Dp,
    /* the category pane, and the entries in it */
    val pane: Dp,
    val paneGap: Dp,
    val entryPadH: Dp,
    val entryPadV: Dp,
    val entryGap: Dp,
    /* a row card: the logo tile, its inset, and the floor the whole row keeps */
    val logo: Dp,
    val cardPad: Dp,
    val rowMin: Dp,
    /** A placeholder row, at the height the real row will take. */
    val skeleton: Dp,
    /**
     * How many posters fit across, and whether the categories are a pane or a strip.
     *
     * Both are **counts derived from the measured surface**, never from what kind of
     * device this is — see [catalogMetricsFor] for how each is decided and why the
     * second one is the only threshold on these screens.
     */
    val columns: Int,
    val twoPane: Boolean,
)

/**
 * The catalogue's numbers for a measured surface.
 *
 * `width` and `height` are what a `BoxWithConstraints` around the screen's own content
 * reports — the surface, not the window and not the display.
 */
internal fun catalogMetricsFor(tv: Boolean, width: Dp, height: Dp): CatalogMetrics {
    val frame = castivioMetrics(width, height, tv)

    val pane = width.boundedFraction(PANE, 240.dp, 360.dp)
    val paneGap = width.boundedFraction(PANE_GAP, 20.dp, 44.dp)
    val gutter = height.boundedFraction(GUTTER, 14.dp, 40.dp)
    val twoPane = width >= TWO_PANE

    // What the grid actually has to divide: the stage, less the category pane when
    // there is one beside it. Computed here rather than in the screen so the column
    // count and the cells are answering the same question.
    val content = width - frame.edge * 2
    val grid = if (twoPane) content - pane - paneGap else content

    return CatalogMetrics(
        frame = frame,
        bandGap = height.boundedFraction(BAND_GAP, 12.dp, 32.dp),
        rowGap = height.boundedFraction(ROW_GAP, 6.dp, 20.dp),
        gutter = gutter,
        listBottom = height.boundedFraction(LIST_BOTTOM, 24.dp, 72.dp),
        pane = pane,
        paneGap = paneGap,
        entryPadH = width.boundedFraction(ENTRY_PAD_H, 10.dp, 24.dp),
        entryPadV = height.boundedFraction(ENTRY_PAD_V, 6.dp, 16.dp),
        entryGap = height.boundedFraction(ENTRY_GAP, 4.dp, 10.dp),
        logo = height.boundedFraction(LOGO, 32.dp, 68.dp),
        cardPad = height.boundedFraction(CARD_PAD, 8.dp, 18.dp),
        // A row is a control: whatever the arithmetic says, it is never smaller than
        // what a thumb or a D-pad needs. This is the functional adaptation the design
        // system permits, and the one defect on these screens it directly fixes — the
        // row was 50dp on every device, 6dp under the floor on the one with a remote.
        rowMin = maxOf(height.boundedFraction(ROW_MIN, 46.dp, 96.dp), Sizing.minTarget(tv)),
        skeleton = height.boundedFraction(ROW_MIN, 46.dp, 96.dp),
        columns = columnsFor(grid, gutter, width.boundedFraction(POSTER, 112.dp, 200.dp)),
        twoPane = twoPane,
    )
}

/**
 * How many posters fit across the width the grid was left.
 *
 * ## Not a device table any more, and the difference is arithmetic
 *
 * It was `Compact 3, Medium 4, Expanded 5, Television 6` — a count chosen by what kind
 * of box this is, applied to a width nobody had measured. On a 960×540 television that
 * cut six columns out of the 596dp left beside the category pane and drew **81dp**
 * posters, on the device watched from three metres.
 *
 * So the question asked is the one that decides the answer: how many cells of at least
 * `minimum` fit in `available`, with a `gutter` between each pair. Bounded at both
 * ends — under three a grid reads as a list and over ten a poster is a thumbnail —
 * and the cells then take an equal share of whatever is left, so the row always fills
 * the width exactly.
 */
private fun columnsFor(available: Dp, gutter: Dp, minimum: Dp): Int =
    ((available + gutter) / (minimum + gutter)).toInt().coerceIn(MIN_COLUMNS, MAX_COLUMNS)

/**
 * Where the categories stop being a strip above the content and become a pane beside
 * it — the one threshold on these screens, and it is not a device.
 *
 * ## Why it is allowed to exist
 *
 * The design system permits a breakpoint for a real functional difference and forbids
 * one that produces a second visual design. This is the first: a category pane is
 * 240dp at its narrowest, and on a surface under 840dp that is more than a quarter of
 * the screen taken from the content the pane exists to filter. The strip is the same
 * control, the same entries in the same order, laid along the axis that has room.
 *
 * ## It reproduces exactly what shipped
 *
 * `DeviceClass.Expanded` begins at 840dp of window width and `twoPane` was
 * `Television || Expanded`; every television reports at least 960. So the same
 * surfaces get the same arrangement as before — 800dp a strip, 873dp and up a pane —
 * with the width measured rather than inferred from what the box calls itself.
 */
private val TWO_PANE = 840.dp

/* ------------------------------------------------------------------ the shares
 *
 * Read off the 1280×720 reference. Where a value replaces a fixed token, the share is
 * chosen so the reference handset — 873×393 — lands within a dp or two of what that
 * token already drew, and the surfaces the token was wrong for are the ones that move.
 */

private const val BAND_GAP = 26.7f / 720f
private const val ROW_GAP = 16f / 720f
private const val GUTTER = 34.7f / 720f
private const val LIST_BOTTOM = 56f / 720f
private const val PANE = 288f / 1280f
private const val PANE_GAP = 42.7f / 1280f
private const val ENTRY_PAD_H = 21.3f / 1280f
private const val ENTRY_PAD_V = 14.2f / 720f
private const val ENTRY_GAP = 7.1f / 720f
private const val LOGO = 60f / 720f
private const val CARD_PAD = 14.2f / 720f
private const val ROW_MIN = 84f / 720f

/** The narrowest a poster may be drawn before a grid of them stops being readable. */
private const val POSTER = 160f / 1280f

private const val MIN_COLUMNS = 3
private const val MAX_COLUMNS = 10

/**
 * A poster's shape, which responsive sizing decides the size of and never the shape of.
 *
 * The same 2:3 the artwork actually is, and the same
 * [com.castivio.core.design.components.CardShape.Poster] draws — stated here because
 * the column arithmetic above has to know how tall a cell it is producing.
 */
internal const val CATALOG_POSTER_ASPECT = 2f / 3f
