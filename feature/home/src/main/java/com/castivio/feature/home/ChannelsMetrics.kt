package com.castivio.feature.home

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.castivio.core.design.theme.CastivioMetrics
import com.castivio.core.design.theme.Sizing
import com.castivio.core.design.theme.boundedFraction
import com.castivio.core.design.theme.castivioMetrics

/**
 * What the Channels board is drawn from.
 *
 * ## Where these numbers come from
 *
 * The approved Channels reference, measured. It is a 1536×1024 drawing, and this
 * product's reference geometry is 1280×720 — so a pixel in the reference becomes a
 * *share* of the axis it spends before it becomes a size here: a width of `w` is
 * `w / 1536` of the width, a height of `h` is `h / 1024` of the height. That is the
 * only conversion applied, and it is why the board reproduces the reference's
 * proportions on a surface with a different aspect rather than reproducing its pixels.
 *
 * Every value then goes through [boundedFraction] with a floor and a ceiling, which is
 * the rule the whole sizing system is built on. There is no device table here and
 * nothing in this type could express one: it returns sizes, never a layout.
 *
 * ## The two places the reference is deliberately not reproduced
 *
 * Both are the *functional adaptation* the design system permits, and both are forced
 * by arithmetic rather than chosen:
 *
 *  1. **[rowMin] and [railMin] are floored at [Sizing.minTarget].** The reference's row
 *     is 68px of 1024, which on a 720dp-tall surface is 47.8dp — **8dp under what a
 *     D-pad needs**, on the one device that has one. A channel row is the most-pressed
 *     control in the application; it is not allowed to be drawn under its floor to make
 *     a picture match.
 *  2. **Fewer rows are therefore visible than in the reference.** The reference is 3:2
 *     and shows ten; a 16:9 surface at a 56dp floor holds about eight. Both the list and
 *     the rail are lazy and scroll, so this costs a scroll rather than a clipped row —
 *     which is the whole reason neither was laid out as a fixed column.
 *
 * @see castivioMetrics for the stage, the header and the four type steps, which this
 *   board shares with Home rather than restating.
 */
@Immutable
internal data class ChannelsMetrics(
    /** The stage, the header and the four type steps — the whole product's. */
    val frame: CastivioMetrics,

    /* the board's own outer stage, which is tighter than the shared one — see EDGE */
    val edge: Dp,
    val boardTop: Dp,
    val boardBottom: Dp,

    /* the three bands */
    val header: Dp,
    val headerGap: Dp,
    val remote: Dp,
    val remoteGap: Dp,

    /* the panel that holds the toolbar and the three columns */
    val panelPad: Dp,
    val panelRadius: Dp,
    val toolbar: Dp,
    val toolbarGap: Dp,
    val search: Dp,

    /* the three columns */
    val rail: Dp,
    val railGap: Dp,
    val player: Dp,
    val playerGap: Dp,

    /* the category rail */
    val railEntryGap: Dp,
    val railDivider: Dp,
    /** A rail entry's height. Floored at the D-pad target — see the class note. */
    val railMin: Dp,

    /* the channel list */
    /** A channel row's height. Floored at the D-pad target — see the class note. */
    val rowMin: Dp,
    val rowPadH: Dp,
    val numberWidth: Dp,
    val logoWidth: Dp,
    val scrollbar: Dp,

    /* the player well */
    val wellPad: Dp,
    val wellRadius: Dp,
    val previewRadius: Dp,
    val nameGap: Dp,
    val timelineGap: Dp,
    val trackHeight: Dp,
    val trackDot: Dp,
    val factChip: Dp,
    val factGap: Dp,
    val badgePadH: Dp,
    val badgePadV: Dp,

    /* the remote bar */
    val remoteDot: Dp,
    val remoteGapInner: Dp,
    val remoteKeyPadH: Dp,
    val remoteKeyPadV: Dp,
) {
    /**
     * The board's own type step for the channel name in the player well.
     *
     * The reference sets it at 23 of 1024, between the frame's title and its label, and
     * neither of those is it — so it is derived here rather than borrowed from a step
     * that means something else.
     */
    val fsChannelName: Dp get() = frame.fsTitle * CHANNEL_NAME_OF_TITLE
}

/**
 * The board's numbers for a measured surface.
 *
 * `width` and `height` are what a `BoxWithConstraints` around the board's own content
 * reports — the surface, not the window and not the display.
 */
internal fun channelsMetricsFor(tv: Boolean, width: Dp, height: Dp): ChannelsMetrics {
    val frame = castivioMetrics(width, height, tv)
    val target = Sizing.minTarget(tv)

    return ChannelsMetrics(
        frame = frame,

        edge = width.boundedFraction(EDGE, 14.dp, 40.dp),
        boardTop = height.boundedFraction(BOARD_PAD, 10.dp, 30.dp),
        boardBottom = height.boundedFraction(BOARD_PAD, 10.dp, 30.dp),

        header = height.boundedFraction(HEADER, 48.dp, 96.dp),
        headerGap = height.boundedFraction(HEADER_GAP, 12.dp, 40.dp),
        remote = height.boundedFraction(REMOTE, 44.dp, 88.dp),
        remoteGap = height.boundedFraction(REMOTE_GAP, 8.dp, 22.dp),

        panelPad = width.boundedFraction(PANEL_PAD, 8.dp, 22.dp),
        panelRadius = height.boundedFraction(PANEL_RADIUS, 12.dp, 26.dp),
        toolbar = height.boundedFraction(TOOLBAR, 34.dp, 70.dp),
        toolbarGap = height.boundedFraction(TOOLBAR_GAP, 4.dp, 16.dp),
        search = width.boundedFraction(SEARCH, 180.dp, 420.dp),

        rail = width.boundedFraction(RAIL, 190.dp, 340.dp),
        railGap = width.boundedFraction(RAIL_GAP, 10.dp, 32.dp),
        player = width.boundedFraction(PLAYER, 260.dp, 520.dp),
        playerGap = width.boundedFraction(PLAYER_GAP, 14.dp, 44.dp),

        railEntryGap = height.boundedFraction(RAIL_ENTRY_GAP, 2.dp, 8.dp),
        railDivider = height.boundedFraction(RAIL_DIVIDER, 6.dp, 18.dp),
        // The floor wins wherever the arithmetic falls below it. See the class note:
        // on a 720dp-tall surface the reference's own height is 8dp under the D-pad
        // target, and a rail entry is a control before it is a picture.
        railMin = maxOf(height.boundedFraction(RAIL_MIN, 34.dp, 76.dp), target),

        rowMin = maxOf(height.boundedFraction(ROW, 40.dp, 88.dp), target),
        rowPadH = width.boundedFraction(ROW_PAD_H, 8.dp, 24.dp),
        numberWidth = width.boundedFraction(NUMBER, 36.dp, 76.dp),
        logoWidth = width.boundedFraction(LOGO, 56.dp, 132.dp),
        scrollbar = width.boundedFraction(SCROLLBAR, 3.dp, 8.dp),

        wellPad = width.boundedFraction(WELL_PAD, 6.dp, 18.dp),
        wellRadius = height.boundedFraction(WELL_RADIUS, 10.dp, 22.dp),
        previewRadius = height.boundedFraction(PREVIEW_RADIUS, 6.dp, 16.dp),
        nameGap = height.boundedFraction(NAME_GAP, 5.dp, 18.dp),
        timelineGap = height.boundedFraction(TIMELINE_GAP, 10.dp, 40.dp),
        trackHeight = height.boundedFraction(TRACK, 2.dp, 5.dp),
        trackDot = height.boundedFraction(TRACK_DOT, 6.dp, 14.dp),
        factChip = height.boundedFraction(FACT_CHIP, 22.dp, 44.dp),
        factGap = width.boundedFraction(FACT_GAP, 4.dp, 12.dp),
        badgePadH = width.boundedFraction(BADGE_PAD_H, 4.dp, 12.dp),
        badgePadV = height.boundedFraction(BADGE_PAD_V, 2.dp, 7.dp),

        remoteDot = height.boundedFraction(REMOTE_DOT, 10.dp, 24.dp),
        remoteGapInner = width.boundedFraction(REMOTE_GAP_INNER, 10.dp, 34.dp),
        remoteKeyPadH = width.boundedFraction(REMOTE_KEY_PAD_H, 5.dp, 14.dp),
        remoteKeyPadV = height.boundedFraction(REMOTE_KEY_PAD_V, 3.dp, 9.dp),
    )
}

/**
 * How wide the preview plate is relative to its height.
 *
 * The reference's plate is 430×312. It is deliberately **not** 16:9: what sits in it is
 * a channel identity rather than a frame of video, and the reference gives it the
 * squarer box a logo actually fills. Stated once here because two places need it — the
 * plate itself, and the arithmetic that checks the well fits.
 */
internal const val CHANNELS_PREVIEW_ASPECT = 430f / 312f

/* ------------------------------------------------------------------ the shares
 *
 * Read off the approved Channels reference at 1536×1024. Widths are `px / 1536`,
 * heights are `px / 1024`. Nothing here was chosen twice and nothing was rounded to
 * something prettier than what was measured.
 */

private const val EDGE = 32f / 1536f
private const val BOARD_PAD = 24f / 1024f

private const val HEADER = 76f / 1024f
private const val HEADER_GAP = 34f / 1024f
private const val REMOTE = 72f / 1024f
private const val REMOTE_GAP = 16f / 1024f

private const val PANEL_PAD = 16f / 1536f
private const val PANEL_RADIUS = 18f / 1024f
private const val TOOLBAR = 54f / 1024f
private const val TOOLBAR_GAP = 10f / 1024f
private const val SEARCH = 326f / 1536f

private const val RAIL = 302f / 1536f
private const val RAIL_GAP = 24f / 1536f
private const val PLAYER = 444f / 1536f
private const val PLAYER_GAP = 34f / 1536f

private const val RAIL_ENTRY_GAP = 3f / 1024f
private const val RAIL_DIVIDER = 12f / 1024f
private const val RAIL_MIN = 55f / 1024f

private const val ROW = 68f / 1024f
private const val ROW_PAD_H = 16f / 1536f
private const val NUMBER = 58f / 1536f
private const val LOGO = 108f / 1536f
private const val SCROLLBAR = 6f / 1536f

private const val WELL_PAD = 12f / 1536f
private const val WELL_RADIUS = 16f / 1024f
private const val PREVIEW_RADIUS = 12f / 1024f
private const val NAME_GAP = 10f / 1024f
private const val TIMELINE_GAP = 32f / 1024f
private const val TRACK = 3f / 1024f
private const val TRACK_DOT = 10f / 1024f
private const val FACT_CHIP = 34f / 1024f
private const val FACT_GAP = 10f / 1536f
private const val BADGE_PAD_H = 9f / 1536f
private const val BADGE_PAD_V = 4f / 1024f

private const val REMOTE_DOT = 22f / 1024f
private const val REMOTE_GAP_INNER = 36f / 1536f
private const val REMOTE_KEY_PAD_H = 11f / 1536f
private const val REMOTE_KEY_PAD_V = 6f / 1024f

/**
 * The channel name in the player well, as a ratio of the frame's title step.
 *
 * The reference sets the name at 23/1024 and the frame's title lands at 34.7/720; the
 * ratio between them is what is portable, so the name tracks the product's type scale
 * instead of drifting away from it on a surface the reference never covered.
 */
private const val CHANNEL_NAME_OF_TITLE = (23f / 1024f) / (34.7f / 720f)
