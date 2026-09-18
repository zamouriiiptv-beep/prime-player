package com.castivio.feature.home

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.castivio.core.design.theme.CastivioMetrics
import com.castivio.core.design.theme.boundedFraction
import com.castivio.core.design.theme.castivioMetrics

/**
 * What the Channels board is drawn from.
 *
 * ## Where these numbers come from
 *
 * A **2340×1080 reference**, measured rather than estimated: every share below is a
 * pixel boundary found by running an edge detector down and across the reference
 * screenshot, divided by the axis it spends. Widths are `px / 2340`, heights are
 * `px / 1080`. That is the only conversion applied, which is why the board reproduces
 * the reference's *proportions* on a surface with a different aspect instead of
 * reproducing its pixels.
 *
 * The measured columns, for anyone checking the arithmetic:
 *
 * | zone | px | share |
 * |---|---|---|
 * | category rail | 494 | 21.1% |
 * | channel list | 702 | 30.0% (the remainder) |
 * | player well | 986 | 42.1% |
 * | header band | 88 | 8.1% of the height (this board spends 76 — see HEADER) |
 *
 * Every value then goes through [boundedFraction] with a floor and a ceiling, which is
 * the rule the whole sizing system is built on. There is no device table here and
 * nothing in this type could express one: it returns sizes, never a layout.
 *
 * ## The floor that was lowered, and why that is not a loosening
 *
 * [rowMin] and [railMin] used to be `maxOf(share, Sizing.minTarget(isTv))` — 48dp for a
 * thumb, 56dp for a D-pad. That floor is the reason the board showed **three** channels
 * where the reference shows twelve, and the arithmetic says it always would: twelve rows
 * at 56dp is 672dp of list, and a 16:9 surface 540dp tall does not have it. No ceiling
 * can be tuned out of that. It is not a fit problem, it is a floor that cannot be met.
 *
 * So the floor is now the *list* floor rather than the *control* floor, and the
 * distinction is real rather than convenient:
 *
 *  - `Sizing.minTarget` exists for a control a finger has to **hit** — a small circle
 *    among others, where missing means pressing the wrong one.
 *  - A channel row is a full-width strip reached by **moving focus** with a D-pad, and
 *    by touch it is 700dp wide. There is no neighbouring target to miss by 8dp.
 *
 * The board carries no such circles any more: the action strip and the remote legend
 * were both removed at the owner's request, pending a decision about where their
 * controls belong. The distinction is kept in this note because it is the reason the
 * rows are the height they are, and because those controls are coming back.
 *
 * This is a deliberate departure with a cost, stated here so it is a decision and not a
 * drift: on a phone held in landscape a row is about 30dp tall, which is smaller than
 * Android's guidance for a tappable control. It is what the density the product is
 * measured against requires, and the reference it is measured against is smaller still.
 *
 * @see castivioMetrics for the stage and the four type steps, which this board shares
 *   with the rest of the product rather than restating.
 */
@Immutable
internal data class ChannelsMetrics(
    /** The stage, the header and the four type steps — the whole product's. */
    val frame: CastivioMetrics,

    /* the board's own outer stage, which is tighter than the shared one — see EDGE */
    val edge: Dp,
    val boardTop: Dp,
    val boardBottom: Dp,

    /* the band above the columns */
    val header: Dp,
    val headerGap: Dp,
    /** The search field in the middle of the band. */
    val search: Dp,
    /**
     * The breadcrumb: which section, and which category under it.
     *
     * Bounded, and it has to be. The category line carries a *provider's* words, and
     * providers ship names like `US| SPECTRUM NETWORK SPORTS AND ENTERTAINMENT`. An
     * unbounded cell takes its text's intrinsic width, so one such name would have
     * pushed the field, the clock and the dates along the band until they ran off the
     * end of it — the band's width decided by whichever category happened to be open.
     */
    val crumb: Dp,
    /**
     * The band's trailing cell: the clock and today's date under it.
     *
     * **Reserved, not left over,** and that distinction was a real defect. The two expiry
     * dates that used to live here were the last child of an unweighted row, so they were
     * measured with whatever everything before them had not taken. In English that was
     * enough; in Arabic the captions are wider, the box came up short, and a right-to-left
     * line that does not fit loses its *left* end — which is where the date sat. The
     * screen showed `ينتهي الاشتراك:` and no date at all.
     *
     * The dates are gone at the owner's request and the clock has the cell to itself, but
     * the reservation stays: the clock is text too, and text may not decide where the rest
     * of the band sits. The name is kept so the share below stays the measured one.
     */
    val dates: Dp,

    /* the panel that holds the three columns */
    val panelPad: Dp,
    val panelRadius: Dp,

    /**
     * What the three columns are given: the surface less every band above and below them.
     *
     * Stored rather than recomputed where it is needed, because two places need it — the
     * screen, to lay the columns out, and [wellPicture], to decide how much of the well
     * the picture may take. Two derivations of one number is two chances for the picture
     * to be sized against a column it is not in.
     */
    val column: Dp,

    /* the three columns */
    val rail: Dp,
    val railGap: Dp,
    val player: Dp,
    val playerGap: Dp,
    /**
     * A button's height in the action strip under the well.
     *
     * A height share with a **control** floor rather than the list floor the rows carry,
     * and the distinction is the one `ChannelsMetrics`' own note draws: a channel row is
     * moved *through*, these are five small targets in a row that a thumb has to land
     * *on*. Missing one here means locking a channel instead of favouriting it.
     */
    val strip: Dp,

    /* the category rail */
    val railEntryGap: Dp,
    val railDivider: Dp,
    /** A rail entry's height. The list floor, not the control floor — see the note. */
    val railMin: Dp,

    /* the channel list */
    /** A channel row's height. The list floor, not the control floor — see the note. */
    val rowMin: Dp,
    val rowPadH: Dp,
    val numberWidth: Dp,
    /** The number's plate. A pill, because the reference makes the number a *token*. */
    val numberHeight: Dp,
    /**
     * The channel logo.
     *
     * Small, and that is the whole point of it. It was `108/1536` — seven per cent of
     * the width — in a column that is thirty per cent of the width, and between it and a
     * trailing spacer the channel *name* was left with about forty pixels and rendered as
     * a bare ellipsis. The reference's logo is a 60px square beside a 700px row. A mark
     * identifies a channel; the name is what a viewer reads.
     */
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

    /* the on-screen display over the preview, and the guide under it */
    val osdPad: Dp,
    val osdGap: Dp,
    val guideGap: Dp,
    val guidePad: Dp,
) {
    /**
     * The board's own type step for the channel name in the player well.
     *
     * The reference sets it between the frame's title and its label, and neither of those
     * is it — so it is derived here rather than borrowed from a step that means something
     * else.
     */
    val fsChannelName: Dp get() = frame.fsTitle * CHANNEL_NAME_OF_TITLE

    /**
     * The picture at the head of the well: its height, which is what the column can spare.
     *
     * **The picture yields, not the text.** At the column's full width a 16:9 frame is
     * 146dp tall, and on a 21:9 handset the whole column is 255 — which leaves the facts
     * block 52dp for a name, two programme lines and a bar that need about 76. Something
     * has to give, and it is the picture: a frame two thirds the size is still a picture,
     * whereas a name with its last line cut off is a defect.
     *
     * So the height is capped by what is left after the strip, the gaps and
     * [CHANNELS_FACTS_MIN], and [wellPictureWidth] follows it — the frame stays 16:9 and
     * is simply narrower than its column on a surface that cannot hold it. It is never
     * letterboxed and never cropped.
     */
    val wellPicture: Dp get() = minOf(
        player / CHANNELS_PREVIEW_ASPECT,
        column - strip - guideGap * 2 - CHANNELS_FACTS_MIN,
    ).coerceAtLeast(CHANNELS_PICTURE_MIN)

    /** The picture's width, which follows its height so the frame is always 16:9. */
    val wellPictureWidth: Dp get() = minOf(wellPicture * CHANNELS_PREVIEW_ASPECT, player)
}

/**
 * The board's numbers for a measured surface.
 *
 * `width` and `height` are what a `BoxWithConstraints` around the board's own content
 * reports — the surface, not the window and not the display.
 */
internal fun channelsMetricsFor(tv: Boolean, width: Dp, height: Dp): ChannelsMetrics {
    val frame = castivioMetrics(width, height, tv)

    val boardPad = height.boundedFraction(BOARD_PAD, 4.dp, 14.dp)
    val header = height.boundedFraction(HEADER, 34.dp, 60.dp)
    val headerGap = height.boundedFraction(HEADER_GAP, 4.dp, 12.dp)
    val panelPad = width.boundedFraction(PANEL_PAD, 4.dp, 12.dp)

    return ChannelsMetrics(
        frame = frame,

        edge = width.boundedFraction(EDGE, 8.dp, 24.dp),
        boardTop = boardPad,
        boardBottom = boardPad,

        header = header,
        headerGap = headerGap,
        search = width.boundedFraction(SEARCH, 180.dp, 620.dp),
        crumb = width.boundedFraction(CRUMB, 90.dp, 260.dp),
        dates = width.boundedFraction(DATES, 210.dp, 400.dp),

        panelPad = panelPad,
        panelRadius = height.boundedFraction(PANEL_RADIUS, 10.dp, 22.dp),
        column = height - (boardPad * 2 + header + headerGap + panelPad * 2),

        rail = width.boundedFraction(RAIL, 170.dp, 330.dp),
        railGap = width.boundedFraction(RAIL_GAP, 6.dp, 18.dp),
        player = width.boundedFraction(PLAYER, 260.dp, 520.dp),
        playerGap = width.boundedFraction(PLAYER_GAP, 6.dp, 20.dp),
        strip = height.boundedFraction(STRIP, 48.dp, 110.dp),

        railEntryGap = height.boundedFraction(RAIL_ENTRY_GAP, 2.dp, 7.dp),
        railDivider = height.boundedFraction(RAIL_DIVIDER, 5.dp, 14.dp),
        railMin = height.boundedFraction(RAIL_MIN, 32.dp, 68.dp),

        rowMin = height.boundedFraction(ROW, 26.dp, 48.dp),
        rowPadH = width.boundedFraction(ROW_PAD_H, 5.dp, 16.dp),
        numberWidth = width.boundedFraction(NUMBER, 30.dp, 58.dp),
        numberHeight = height.boundedFraction(NUMBER_H, 18.dp, 34.dp),
        logoWidth = width.boundedFraction(LOGO, 26.dp, 54.dp),
        scrollbar = width.boundedFraction(SCROLLBAR, 3.dp, 8.dp),

        wellPad = width.boundedFraction(WELL_PAD, 5.dp, 14.dp),
        wellRadius = height.boundedFraction(WELL_RADIUS, 10.dp, 20.dp),
        previewRadius = height.boundedFraction(PREVIEW_RADIUS, 6.dp, 14.dp),
        nameGap = height.boundedFraction(NAME_GAP, 4.dp, 14.dp),
        timelineGap = height.boundedFraction(TIMELINE_GAP, 8.dp, 28.dp),
        trackHeight = height.boundedFraction(TRACK, 2.dp, 5.dp),
        trackDot = height.boundedFraction(TRACK_DOT, 6.dp, 13.dp),
        factChip = height.boundedFraction(FACT_CHIP, 20.dp, 38.dp),
        factGap = width.boundedFraction(FACT_GAP, 4.dp, 12.dp),
        badgePadH = width.boundedFraction(BADGE_PAD_H, 4.dp, 11.dp),
        badgePadV = height.boundedFraction(BADGE_PAD_V, 2.dp, 7.dp),

        osdPad = width.boundedFraction(OSD_PAD, 5.dp, 14.dp),
        osdGap = width.boundedFraction(OSD_GAP, 4.dp, 11.dp),
        guideGap = height.boundedFraction(GUIDE_GAP, 4.dp, 11.dp),
        guidePad = width.boundedFraction(GUIDE_PAD, 5.dp, 14.dp),

    )
}

/**
 * How wide the preview plate is relative to its height.
 *
 * A picture has one shape. Anything else letterboxes live television inside a panel
 * built to avoid letterboxing — and the reference's preview is 16:9 to the pixel.
 *
 * Stated once here because two places need it: the plate, and the arithmetic that checks
 * the well fits.
 */
internal const val CHANNELS_PREVIEW_ASPECT = 16f / 9f

/**
 * Roughly how many channel rows the board is built to show at once.
 *
 * Not a layout constant — nothing reads it — but the number [ROW] was solved for, and
 * the number `ChannelsMetricsTest` holds the board to. The reference shows twelve, and
 * twelve is the whole point of the row share being what it is.
 */
internal const val CHANNELS_TARGET_ROWS = 12

/**
 * What the facts block under the picture may never be squeezed below.
 *
 * The channel's name, what is on, the bar under it and what is next — four things, and
 * the smallest surface this ships to draws them at the frame's body step. Solved from
 * that rather than chosen: below this the last line is cut off, which is the defect the
 * cap on [ChannelsMetrics.wellPicture] exists to prevent.
 */
internal val CHANNELS_FACTS_MIN = 76.dp

/**
 * And what the picture may never be squeezed below, whatever the column says.
 *
 * A frame smaller than this is not a preview of anything. If a surface ever forces the
 * cap this low, the honest failure is a picture that is too small rather than one that
 * has vanished — and the assertion in `ChannelsMetricsTest` says no shipping surface does.
 */
internal val CHANNELS_PICTURE_MIN = 60.dp

/* ------------------------------------------------------------------ the shares
 *
 * Measured off the 2340×1080 reference. Widths are `px / 2340`, heights are
 * `px / 1080`. Nothing here was chosen twice and nothing was rounded to something
 * prettier than what the edge detector reported.
 */

private const val EDGE = 22f / 2340f
private const val BOARD_PAD = 10f / 1080f

/**
 * The header band.
 *
 * Thinner than the reference's 88, and deliberately: the reference draws a clock, a
 * playlist name and a status chip in that band and this one draws neither the clock nor
 * the chip. Four things need less height than six, and the height they give back goes to
 * the category rail and the channel list, which is what the band was taking it from.
 */
private const val HEADER = 76f / 1080f
private const val HEADER_GAP = 8f / 1080f

/**
 * The search field's width.
 *
 * Wide enough to read a placeholder, and bounded so it stays a field in the middle of
 * the band rather than a second panel: the weighted gaps on either side of it are what
 * centre it, and a field with no ceiling would eat them.
 *
 * The floor came down from 220 to 180 when the dates were given a reserved column. Both
 * cannot have what they want on an 800dp surface, and of the two this is the one that
 * degrades gracefully: it is a *way in* to the search screen rather than an input, so a
 * narrower one shows its icon and less of its hint and still does its whole job. A
 * narrower date column, by contrast, drops the date — which is the defect the reserved
 * width exists to end.
 */
private const val SEARCH = 520f / 2340f

/**
 * The expiry pair's own column.
 *
 * Solved for the widest of the thirty-seven languages rather than measured off the
 * reference, which does not draw this pair at all: the longest caption plus a
 * `dd-MM-yyyy` token has to fit on one line, because the alternative — the line that
 * does not fit — is the Arabic defect this field exists to end. The floor is what the
 * English pair needs; the ceiling stops a wide surface from spending width on two short
 * lines that the channel list would use better.
 */
private const val DATES = 360f / 2340f

/**
 * The breadcrumb's cell, and the clock's.
 *
 * Both are text, and the band's rule is that nothing whose width is text gets to decide
 * where the rest of the band sits. These are what each is allowed; the line inside
 * ellipsises rather than growing.
 */
private const val CRUMB = 240f / 2340f

private const val PANEL_PAD = 10f / 2340f
private const val PANEL_RADIUS = 16f / 1080f


private const val RAIL = 494f / 2340f
private const val RAIL_GAP = 16f / 2340f
/**
 * The well: the third column, and a column again rather than an overlay.
 *
 * **It was a card floating over the channel list, and the owner rejected that for the
 * reason the arithmetic also gives: it covered the names.** No arrangement of a rectangle
 * over a list of names avoids covering some of them, and a rule that moves the rectangle
 * only chooses *which* names are covered.
 *
 * So the width comes back — but it is not the 986px preview well of the original
 * reference either. That column was a picture with a whole schedule under it, and the
 * schedule has left for a page of its own. What is left is a picture, the four lines that
 * say what it is, and a strip of things to do to it; and that needs about a third of the
 * board rather than two fifths.
 *
 * The floor is what those three layers need to stay legible; the ceiling stops a
 * television from spending half its width on a preview.
 */
private const val PLAYER = 748f / 2340f

/**
 * A button's height in the strip under the well.
 *
 * Floored at a control target rather than at the list floor. The share is what the
 * approved drawing gives it on a 1080-tall surface; on a handset the floor wins, which is
 * the correct way round for five small targets side by side.
 */
private const val STRIP = 106f / 1080f

private const val PLAYER_GAP = 18f / 2340f

private const val RAIL_ENTRY_GAP = 4f / 1080f
private const val RAIL_DIVIDER = 10f / 1080f

/**
 * A category entry's height.
 *
 * The reference's is 109 of 1080 and this is 100, which is the one share deliberately
 * tightened rather than copied: the reference's rail carries a name and a total in a
 * column 21% wide, and so does this one, but this one also has to hold at 800dp where
 * the reference was never drawn. Nine entries at 100 is what the reference shows.
 */
private const val RAIL_MIN = 100f / 1080f

/**
 * A channel row's height, and the number the board's density is decided by.
 *
 * The reference's row is 82 of 1080 and this is 70. The difference is the two things
 * the reference does not spend height on and this board did: a remote-key legend along
 * the bottom, and the panel's own padding. The legend is gone now and the room it took
 * went to the list. Solved rather than copied, so that
 * [CHANNELS_TARGET_ROWS] rows fit *after* those are paid for — which is the property
 * worth reproducing, the pixel height being only how the reference happened to reach it.
 */
private const val ROW = 70f / 1080f

private const val ROW_PAD_H = 12f / 2340f
private const val NUMBER = 62f / 2340f
private const val NUMBER_H = 40f / 1080f
private const val LOGO = 60f / 2340f
private const val SCROLLBAR = 6f / 2340f

private const val WELL_PAD = 10f / 2340f
private const val WELL_RADIUS = 14f / 1080f
private const val PREVIEW_RADIUS = 10f / 1080f
private const val NAME_GAP = 8f / 1080f
private const val TIMELINE_GAP = 20f / 1080f
private const val TRACK = 3f / 1080f
private const val TRACK_DOT = 9f / 1080f
private const val FACT_CHIP = 30f / 1080f
private const val FACT_GAP = 10f / 2340f
private const val BADGE_PAD_H = 9f / 2340f
private const val BADGE_PAD_V = 4f / 1080f

private const val OSD_PAD = 12f / 2340f
private const val OSD_GAP = 8f / 2340f
private const val GUIDE_GAP = 8f / 1080f
private const val GUIDE_PAD = 12f / 2340f


/**
 * The channel name in the player well, as a ratio of the frame's title step.
 *
 * The reference sets the name at 30/1080 and the frame's title lands at 34.7/720; the
 * ratio between them is what is portable, so the name tracks the product's type scale
 * instead of drifting away from it on a surface the reference never covered.
 */
private const val CHANNEL_NAME_OF_TITLE = (30f / 1080f) / (34.7f / 720f)
