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
     *
     * The bands that come and go -- the reorder bar, a failed refresh's notice -- are not
     * in it. The well measures the height it is actually given and rederives its figures
     * from that (see `ChannelWell`), so a band over the columns costs the picture its
     * height rather than the facts under it.
     */
    val column: Dp,

    /* the three columns */
    val rail: Dp,
    val railGap: Dp,
    /** The well's width: its share of the board, or its picture's when that is narrower. See `wellWidth`. */
    val player: Dp,
    val playerGap: Dp,
    /**
     * The video frame's own width, independent of the column that holds it.
     *
     * Separate from [player] so the picture can be sized without moving the channel list.
     * It is the input to [wellPictureWidth], which bounds it by the column and by the room
     * the facts block needs.
     */
    val picture: Dp,
    /**
     * The identity row at the head of the facts block: the logo's box, and the row's height.
     *
     * A control-sized floor rather than a type-sized one. It carries the channel's mark,
     * and a mark shrunk to the height of a line of text stops being recognisable — which
     * is the whole reason it is drawn rather than the name alone.
     */
    val identity: Dp,
    /**
     * A button's height in the action strip under the well.
     *
     * A height share with a **control** floor rather than the list floor the rows carry,
     * and the distinction is the one `ChannelsMetrics`' own note draws: a channel row is
     * moved *through*, these are four small targets in a row that a thumb has to land
     * *on*. Missing one here means locking a channel instead of favouriting it.
     *
     * Which control floor is asked of the device rather than assumed — 48 for a thumb, 56
     * for a remote. See [STRIP] for the television that was three dp under it.
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
    /**
     * A channel row's inset, and the gap between the things on it.
     *
     * Its own metric since the owner set the two columns' insets apart — 9dp for a
     * package and 11dp for a channel at the 1280×720 reference. A channel row carries
     * four things where a package carries two, so the wider inset is the one that has to
     * hold a number plate, a logo, a name and a quality tag off each other.
     */
    val rowPadH: Dp,
    /**
     * A package row's inset, and the gap between the name and the total under it.
     *
     * Narrower than [rowPadH], because the column is narrower and holds less: a name and
     * a count, where the channel row holds four things. The rail's search field takes the
     * same inset, so the field and the entries under it line up on one edge.
     */
    val railPadH: Dp,
    /**
     * The inset for the controls that are in neither column.
     *
     * The header's search field, the fetch notice, the well's action buttons, the guide
     * overlay's header. These shared `rowPadH` when there was one inset for the whole
     * board; splitting the columns apart would have dragged them along, and an action
     * button would have gone from 57.5dp of usable width to 48.7. So they keep the value
     * they had, under a name that says what they are — this is a rename, not a change.
     */
    val controlPadH: Dp,
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
     * The picture at the head of the well: its width, and the number the frame is sized by.
     *
     * **It is the picture's own share now, not the column's.** The frame used to be
     * exactly as wide as the well, so asking for a different picture meant moving the
     * channel list — the one thing a size test must not do. [picture] is measured
     * independently, and what is left of the column around it is simply inset.
     *
     * Three things bound it, and the smallest wins:
     *
     *  1. **its share**, which is what the owner is choosing — see [PICTURE];
     *  2. **the column**, because a picture wider than the well it sits in would overflow
     *     the board;
     *  3. **the room under it**, because the facts block keeps [CHANNELS_FACTS_MIN]
     *     whatever else happens. **The picture yields, not the text**: on a 21:9 handset
     *     the column is 255dp and the block holding a name, what is on and what is next
     *     needs 98 of it, so the frame gives height back rather than have a line cut off.
     */
    val wellPictureWidth: Dp get() = minOf(
        picture,
        player,
        (column - strip - guideGap * 2 - CHANNELS_FACTS_MIN) * CHANNELS_PREVIEW_ASPECT,
    ).coerceAtLeast(CHANNELS_PICTURE_MIN * CHANNELS_PREVIEW_ASPECT)

    /**
     * And its height, which is its width divided by the aspect — so the frame is 16:9 by
     * construction rather than by agreement between two expressions.
     */
    val wellPicture: Dp get() = wellPictureWidth / CHANNELS_PREVIEW_ASPECT

    /**
     * What the well has left for the facts block: the column, less the picture, the gaps
     * either side of it and the action strip.
     *
     * The block takes all of it. It used to hug its content, which left this as bare
     * ground between the last programme and the strip — 170dp of it at the 1280×720
     * reference. Stated here rather than derived in the screen because it is the same
     * subtraction [wellPictureWidth] already does from the other end, and two derivations
     * of one number are two chances for the picture and the panel to disagree about the
     * column they are both in.
     */
    val wellRoom: Dp get() = column - wellPicture - guideGap * 2 - strip

    /**
     * What separates the facts block's sections — one figure, on every channel.
     *
     * ## Why it is a figure at all
     *
     * The block used to set its sections apart with `Arrangement.SpaceBetween`, which
     * divides the spare height between a column's *children* — and the number of children
     * is the number of programmes the provider happened to return. Three screenshots from
     * one device, taken minutes apart, measured the gap between the channel's name and the
     * programme on it at **13.5dp** on a channel with no coming rows, **23.1dp** on one
     * with two, and **36.3dp** on one with one. The channel with fewer programmes had the
     * wider gaps, because the same slack was cut into two pieces instead of three.
     *
     * A reader cannot be expected to know that. The rhythm of a panel is part of how it
     * reads, and here it was being set by an EPG feed.
     *
     * ## Why it is a share of the room and not of the screen
     *
     * Because the room is what it has to fit inside. [wellRoom] is already the block's
     * own height — bounded from the other end by [wellPictureWidth], which gives the
     * picture's height back until the block has [CHANNELS_FACTS_MIN] — so a share of it
     * scales with the space actually available rather than with a screen dimension the
     * block only partly owns. Three gaps take [SECTION_OF_ROOM] × 3 of the block, which
     * is under a fifth of it at every surface; the rest stays with the content, and
     * whatever is left over collects in one place at the bottom instead of being smeared
     * unevenly between the rows.
     *
     * At 1280×720: 333.4 of room, a 20.0 gap. At 960×540: 247.0 and 14.8.
     * At 833×385: 128.6 and 7.7.
     */
    val guideSection: Dp get() = (wellRoom * SECTION_OF_ROOM).coerceIn(5.dp, 22.dp)
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
    val column = height - (boardPad * 2 + header + headerGap + panelPad * 2)
    val strip = height.boundedFraction(STRIP, Sizing.minTarget(tv), 110.dp)
    val guideGap = height.boundedFraction(GUIDE_GAP, 4.dp, 11.dp)
    val factGap = width.boundedFraction(FACT_GAP, 4.dp, 12.dp)
    val guidePad = width.boundedFraction(GUIDE_PAD, 5.dp, 14.dp)

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
        column = column,

        rail = width.boundedFraction(RAIL, 170.dp, 330.dp),
        railGap = width.boundedFraction(RAIL_GAP, 6.dp, 18.dp),
        player = wellWidth(
            share = width.boundedFraction(PLAYER, 260.dp, 560.dp),
            column = column,
            strip = strip,
            guideGap = guideGap,
            factGap = factGap,
            guidePad = guidePad,
            fsBody = frame.fsBody,
            tv = tv,
            listGain = width * LIST_GAIN,
        ),
        playerGap = width.boundedFraction(PLAYER_GAP, 6.dp, 20.dp),
        picture = width.boundedFraction(PICTURE, 260.dp, 560.dp),
        identity = height.boundedFraction(IDENTITY, 24.dp, 48.dp),
        strip = strip,

        railEntryGap = height.boundedFraction(RAIL_ENTRY_GAP, 2.dp, 7.dp),
        railDivider = height.boundedFraction(RAIL_DIVIDER, 5.dp, 14.dp),
        railMin = height.boundedFraction(RAIL_MIN, 32.dp, 68.dp),

        rowMin = height.boundedFraction(ROW, 26.dp, 56.dp),
        rowPadH = width.boundedFraction(ROW_PAD_H, 10.dp, 12.dp),
        railPadH = width.boundedFraction(RAIL_PAD_H, 8.dp, 10.dp),
        controlPadH = width.boundedFraction(CONTROL_PAD_H, 5.dp, 16.dp),
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
        factGap = factGap,
        badgePadH = width.boundedFraction(BADGE_PAD_H, 4.dp, 11.dp),
        badgePadV = height.boundedFraction(BADGE_PAD_V, 2.dp, 7.dp),

        osdPad = width.boundedFraction(OSD_PAD, 5.dp, 14.dp),
        osdGap = width.boundedFraction(OSD_GAP, 4.dp, 11.dp),
        guideGap = guideGap,
        guidePad = guidePad,

    )
}

/**
 * How wide the well is: its share of the board, or its picture's width when that is narrower,
 * less what the channel list takes from it -- see [LIST_GAIN].
 *
 * On a handset the picture is bounded by the height under it, not by the well -- the facts
 * block keeps [CHANNELS_FACTS_MIN] and the frame gives way -- so a well at its full share
 * held the picture with a margin either side of it, width the channel list was cut short for.
 * The well ends where its picture does, and the list has the rest.
 *
 * Then [listGain] more is given to the list: the owner asked for the channel names to have more
 * room, and the picture -- which is the well's width at 16:9 -- is what pays, narrower by the
 * same amount and shorter by nine sixteenths of it. The facts block under it keeps its height and
 * gains the picture's.
 *
 * **Never narrower than what is under the picture needs**: the strip's five buttons at the
 * control target, and the line of what is next -- see [nextLineRoom]. That is a floor that
 * follows the type step, because a larger step makes the word and the hours wider and leaves the
 * title less, and a fixed number was right for one surface and short on the next. And never wider
 * than it was before the gain, so a surface that sits on a floor is not widened by it.
 */
private fun wellWidth(
    share: Dp,
    column: Dp,
    strip: Dp,
    guideGap: Dp,
    factGap: Dp,
    guidePad: Dp,
    fsBody: Dp,
    tv: Boolean,
    listGain: Dp,
): Dp {
    val framed = (column - strip - guideGap * 2 - CHANNELS_FACTS_MIN) * CHANNELS_PREVIEW_ASPECT
    val buttons = Sizing.minTarget(tv) * CHANNELS_STRIP_ACTIONS + factGap * (CHANNELS_STRIP_ACTIONS - 1)
    val floor = maxOf(buttons, nextLineRoom(fsBody, factGap, guidePad))
    val fitted = minOf(share, maxOf(framed, floor))
    return maxOf(fitted - listGain, floor).coerceAtMost(fitted)
}

/**
 * What the line of what is next needs of the well, so that its title keeps [NEXT_TITLE_ROOM].
 *
 * A line is three cells -- the word that says which, the hours, the title -- inside the facts
 * block's padding. The first two are as wide as their widest text, and at the legend step that is
 * [NEXT_LINE_OF_BODY] of the body step: the longest word any of the thirty-nine languages uses and
 * the hours with their dash. A dp over, for what a device's glyphs add to a JVM's estimate.
 * `ChannelsMetricsTest` itemises the same sum from its own constants and holds every surface to
 * it, so the two cannot drift apart without a test saying so.
 */
private fun nextLineRoom(fsBody: Dp, factGap: Dp, guidePad: Dp): Dp =
    NEXT_TITLE_ROOM + fsBody * NEXT_LINE_OF_BODY + factGap * 2 + guidePad * 2 + 1.dp

/** What a programme's title keeps of its line, whatever else the well gives up: about fourteen characters. */
private val NEXT_TITLE_ROOM = 105.dp

/** The word and the hours of a line, in body steps -- 0.88 of it for the legend, times 14.6 em of text. */
private const val NEXT_LINE_OF_BODY = 12.85f

/** The buttons in the strip under the picture: guide, favourite, lock, hide, reorder. */
internal const val CHANNELS_STRIP_ACTIONS = 5

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
 * **Solved from the block's own content, not chosen.** The block is the channel's identity,
 * what is on -- its title, then the word, its hours and its bar -- and what is next, on one
 * line; on a handset, each line at the full height its style declares:
 *
 * | region | at 891×411 |
 * |---|---|
 * | identity row (logo, name, quality) | 24 |
 * | the gap under it | 5.8 |
 * | the rule, and the gap under it | 3 |
 * | what is on: its title | 18 |
 * | a gap, and its line: the word, the hours, the bar | 17.8 |
 * | the gap between what is on and what is next | 4 |
 * | what is next | 18 |
 * | the block's own vertical padding | 5 |
 * | **total** | **95.6** |
 *
 * Every style here declares its line height, and a line never takes more than that on a
 * device whatever its script -- so this is the most the block can need rather than a guess
 * with room to spare. It grows with the surface, and the surfaces this reserve binds on grow
 * with the reserve: 98 is the smallest whole figure that covers every surface it binds on
 * (97.02 at 1132×485, a television). Composed on the desktop, where the leading of a line's
 * first and last edge is trimmed, the block measures 84 on the handset. `ChannelsMetricsTest`
 * holds the content under it at every surface the product ships to.
 *
 * It was 90 while what is on was one line with its bar over its title; the title on a line of
 * its own over the word that says it is on is the owner's layout, and it costs the picture
 * 8dp of height on a handset -- the frame yields and the text does not, as everywhere here.
 */
internal val CHANNELS_FACTS_MIN = 98.dp

/**
 * And what the picture may never be squeezed below, whatever the column says.
 *
 * A frame smaller than this is not a preview of anything. If a surface ever forces the
 * cap this low, the honest failure is a picture that is too small rather than one that
 * has vanished — and the assertion in `ChannelsMetricsTest` says no shipping surface does.
 */
internal val CHANNELS_PICTURE_MIN = 60.dp

/**
 * How much of the facts block one gap between its sections takes.
 *
 * Six per cent, so the three gaps take eighteen — under a fifth of the block, which is
 * the budget `ChannelsMetricsTest` holds this to. It is deliberately less than the slack
 * the old arrangement spread, because that arrangement could not overflow and this one
 * can: a fixed gap large enough to look generous on a channel with one programme is a
 * gap that pushes the third programme off a handset. The drawing found exactly that at
 * 18.2dp before any of this was written.
 *
 * What is left over after the content and the gaps collects at the bottom of the block:
 * on a handset almost nothing, because the picture has taken the room; on a television,
 * where the frame is bounded by the column's width rather than by the room under it, the
 * ground under what is next.
 */
private const val SECTION_OF_ROOM = 0.06f

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


/**
 * The category rail's width.
 *
 * **Stated against 1280 rather than 2340, like [PICTURE] and for the same reason.** The
 * 494/2340 this replaced was a boundary measured off the reference screenshot; 290 at
 * 1280×720 is a width the owner chose after seeing the board on a device. Writing it as
 * `530.2/2340` would dress a decision up as a measurement.
 *
 * The reference's rail was 21.1% of the board and this is 23.3%, and the two and a bit
 * points come from the channel list, which had 44% — a share it only ever had because the
 * well was narrowed when the schedule left it. The list keeps the majority of the board
 * either way; see `ChannelsMetricsTest`.
 */
private const val RAIL = 290f / 1280f
/**
 * And the gap between the rail and the list.
 *
 * Also against 1280: 16dp there, where the measured 16/2340 gave 8.75 and the two columns
 * read as one. The ceiling stays 18 — past that it stops being a gap and starts being a
 * third column of nothing.
 */
private const val RAIL_GAP = 16f / 1280f
/**
 * The well: the third column, and a column again rather than an overlay.
 *
 * **It was a card floating over the channel list, and the owner rejected that for the
 * reason the arithmetic also gives: it covered the names.** No arrangement of a rectangle
 * over a list of names avoids covering some of them, and a rule that moves the rectangle
 * only chooses *which* names are covered.
 *
 * So the width comes back, and it is the 986px of the reference again: two fifths of the
 * board. It was a third for a while -- the schedule had left the well for a page of its own
 * and the column was narrowed after it -- and the owner's drawing of 22 October 2026 asks for
 * the picture back at the size a viewer watches it at: the well at 42%, the channel list
 * shrunk to pay for it, and under the picture only the channel, what is on, what is next and
 * the strip. That is the reference's own boundary, so it is written as the measurement it is.
 *
 * The floor is what those three layers need to stay legible. The ceiling lets a 1280-wide
 * television have the whole share -- 539dp -- and stops a larger one spending more than that
 * on a preview instead of on the catalogue.
 */
private const val PLAYER = 986f / 2340f

/**
 * What the channel list takes from the well, as a share of the board's width.
 *
 * 4.5%: the owner's measure for the list to be wider -- between four and five per cent -- so the
 * names of channels have room, on a phone where a name cut to fit hid the end of it. It leaves the
 * rail, the type and the rows alone; the whole of it comes from the well. 38dp of 851.
 */
private const val LIST_GAIN = 0.045f

/**
 * The video frame's width: the well's own, so the picture fills the column it is in.
 *
 * It was 400 at 1280×720 inside a 409dp well, a size the owner chose and tested; the drawing
 * that widened the well puts the picture edge to edge in it, over the facts block of the same
 * width, so the two read as one panel. Kept as its own share rather than read off [PLAYER] so
 * that the picture can still be sized without moving the channel list.
 *
 * What it gets is bounded twice more in [ChannelsMetrics.wellPictureWidth]: by the column, and
 * by the room the facts block keeps under it. On a handset the second binds -- the frame gives
 * height back to what is on and what is next rather than have a line cut off -- and the well
 * narrows to the picture, giving the channel list what a margin either side of it would take.
 */
private const val PICTURE = PLAYER

/**
 * The identity row's height, which is also the channel logo's box.
 *
 * Stated against 1080 like every other height in this file. Its floor is what keeps a
 * provider's mark legible on a handset; its ceiling stops a television turning the row
 * into a banner.
 */
private const val IDENTITY = 44f / 1080f

/**
 * A button's height in the strip under the well.
 *
 * Floored at a control target rather than at the list floor. The share is what the
 * approved drawing gives it on a 1080-tall surface; on a handset the floor wins, which is
 * the correct way round for four small targets side by side.
 *
 * **And the floor is asked of the device, not written down.** It was a literal `48.dp` —
 * `Sizing.minTouchTarget`, the thumb's figure — on every surface including a television,
 * where the remote's figure is 56. The share only clears 56 above a 571dp-tall surface,
 * so a 1080p television at density 2 reports 960×540 and got **53dp**: three under the
 * D-pad floor, on the commonest television there is.
 *
 * That is the exact mistake `Sizing`' own note records — "every button in the application
 * was 8dp under the D-pad floor, and the two occasions it was caught were both by eye on
 * a photograph". A named function exists so the question can be asked instead of guessed,
 * and this is the last place on this board that was not asking it.
 *
 * Nothing changes for a handset: `minTarget(false)` is the same 48 this said before.
 */
private const val STRIP = 106f / 1080f

private const val PLAYER_GAP = 18f / 2340f

/**
 * The gap between two category entries.
 *
 * 6dp at 1280×720 where the measured share gave 2.67 — the same gap that separates two
 * *lines inside* an entry, so a column of entries read as one block of text. Against 720
 * for that reason: it is a spacing the owner set against a rendered board.
 */
private const val RAIL_ENTRY_GAP = 6f / 720f
private const val RAIL_DIVIDER = 10f / 1080f

/**
 * A category entry's height.
 *
 * **52 at 1280×720**, down from the 100/1080 that gave 66.7 there. The entry holds a name
 * and a total, and two lines do not need two thirds of the height a channel row gets —
 * the reference's own rail carried them in less. Ten entries fit at the reference and
 * eight on a handset, against a floor of six in `ChannelsMetricsTest`.
 *
 * The rail's search field falls out of this: [FIELD_OF_ROW] is 0.86, so 52 makes it 44.7
 * without a second number to keep in step.
 *
 * On a handset the 32dp floor binds before the share does, which is the right way round —
 * it is what stops a two-line entry from being shorter than its two lines.
 */
private const val RAIL_MIN = 52f / 720f

/**
 * A channel row's height, and the number the board's density is decided by.
 *
 * **51.5 at 1280×720, and the half is not a rounding error — it is the whole constraint.**
 * The owner asked for 56 and then for 53; both show eleven rows somewhere that has to
 * show twelve. The binding surface is the handset the board is tested on: its column is
 * 331.0dp, and 331.0 ÷ 12 is 27.583, so the largest row that keeps twelve *there* is
 * 51.58 at the reference. 51.5 passes and 51.6 does not.
 *
 * So this number is solved from two places at once — twelve rows at 1280×720 and twelve
 * at 833×385 — and `ChannelsMetricsTest` asserts both, because a share that satisfied only
 * the reference would drift off the handset without anything saying so.
 *
 * The ceiling went 48 → 56 with it: at 48 the share was cut back to 48 at the reference
 * and this would have been a number that never applied.
 */
private const val ROW = 51.5f / 720f

/**
 * A channel row's inset, and the gap between the four things on it.
 *
 * 11dp at 1280×720. It was 12/2340 — 6.56 — shared by the whole board; the owner set the
 * two columns apart, and this is the channel column's half.
 */
private const val ROW_PAD_H = 11f / 1280f

/** A package row's inset. 9dp at the reference: a narrower column holding less. */
private const val RAIL_PAD_H = 9f / 1280f

/**
 * And the inset for everything that is in neither column.
 *
 * **The value this file has always had**, kept under its own name so that splitting the
 * two columns apart does not drag the header's field, the fetch notice, the guide
 * overlay and the well's action buttons along with them. An action button is 70.67dp
 * wide at the reference; at 11dp of inset what fits inside one drops from 57.5 to 48.7,
 * and the owner's instruction was that the well does not move.
 */
private const val CONTROL_PAD_H = 12f / 2340f
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
