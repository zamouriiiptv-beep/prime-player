package com.castivio.core.design.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The stage every Castivio screen is composed on.
 *
 * ## Why this exists
 *
 * Two screens had two frame tables. They agreed, because they were written to agree
 * by hand — and an agreement written by hand is an agreement one edit breaks silently.
 * Nothing would have failed; the two screens would simply have stopped looking like
 * one product, a dp at a time, and the only instrument that could catch it is
 * somebody's memory.
 *
 * So the numbers a screen does not own live here: the stage's margins, the header's
 * geometry, and the four type steps every screen shares. What a screen *does* own —
 * a capsule, a QR plate, a card, an assurance strip — stays with the screen, because
 * those differ by what the screen is for rather than by what device it is on.
 *
 * ## Chosen by height, not by device name
 *
 * Height, because height is the dimension that runs out: this app is locked to
 * landscape, so a phone gives roughly 360–400dp of it and a television 540. A width
 * class would call an 800dp-wide phone "Medium" and say nothing about whether the
 * composition fits.
 *
 * ## Four frames, and why not a formula
 *
 * A continuous scale — every size a fraction of the frame — is the tempting answer
 * and it is the wrong one. It preserves the *picture* and ignores the *distance*: a
 * composition scaled to fill a 393dp phone held at 30cm has the same proportions as
 * one filling a 540dp television seen from three metres, and it reads as a phone
 * somebody zoomed. Apparent size is what a reader has, and apparent size is angle,
 * not fraction.
 *
 * So the frames are discrete and each is deliberate:
 *
 * | frame | height | held at | what the extra room buys |
 * |---|---|---|---|
 * | [ShortPhone] | under 380dp | 30cm | nothing; it is the floor |
 * | [Phone] | 380–599dp | 30cm | a little margin |
 * | [Tablet] | 600dp and up | 45cm | **margin**, not scale |
 * | [Television] | any, when the device says so | 3m | **scale**, not margin |
 *
 * The tablet is the row worth reading twice. Its type sits *between* the phone's and
 * the set's rather than above both, and the whole of its extra frame goes into the
 * space around a 1000dp content block. Space is what a large screen buys; scale is
 * what a distant one buys, and confusing the two is exactly how a tablet ends up
 * looking like a magnified phone.
 *
 * Nothing here is a floor for touch: [Sizing.minTarget] owns that, and no frame may
 * put a control below it. The budget tests assert it per frame.
 */
data class CastivioFrame(
    /** Which of the four this is. A screen almost never needs it; see [FrameType]. */
    val type: FrameType,
    /* the stage */
    val edge: Dp,
    val stageTop: Dp,
    val stageBottom: Dp,
    /* the header, which is one component and therefore one geometry */
    val header: Dp,
    val headGap: Dp,
    val brand: Dp,
    /**
     * The **drawn** height of a chip: the pill a reader sees.
     *
     * Deliberately below [touchTarget] on all four frames, and that is not an
     * oversight — see the note on [touchTarget] for why the two are different
     * numbers and must stay different.
     */
    val chip: Dp,
    val chipPad: Dp,
    /* the gap between the header and whatever a screen puts under it */
    val bandTop: Dp,
    /* the corner every surface on the stage is cut with */
    val radius: Dp,
    /* the four type steps every screen shares, in dp so a screen can scale them */
    val fsTitle: Dp,
    val fsLabel: Dp,
    val fsBody: Dp,
    val fsChip: Dp,
) {
    /**
     * The floor a control's **interaction area** may not go below on this frame:
     * 56dp on a television, 48dp everywhere else.
     *
     * ## It is not [chip], and the difference is the whole point
     *
     * A chip is 44dp on a television and 34dp on the shortest phone. The floor is 56
     * and 48. Read as one number those two facts are a contradiction, and the
     * contradiction has a wrong answer on each side: growing the pill to 56 rewrites
     * an approved drawing to satisfy a rule about fingers, and growing the *row* to
     * hold it costs the band 2 to 12dp on three frames — which on the licence screen's
     * tightest case takes a reserved sentence from 5dp short to 17dp short.
     *
     * So they are two numbers describing two things. [chip] is what is **drawn**;
     * this is the smallest box that may **receive a press or a D-pad landing**. The
     * box is centred on the pill and overhangs it by `(touchTarget - header) / 2`
     * where the row is the shorter of the two — 1dp on a television, 3 on a phone, 6
     * on the shortest, 0 on a tablet. Every one of those overhangs lands in the
     * stage's own margin: `stageTop` above the header and `bandTop` below it are
     * 24/22, 160/24, 15/10 and 11/8, so the widest overhang is 6dp inside an 8dp gap.
     * It never reaches content and never leaves the stage.
     *
     * `CastivioHeader` therefore measures its slots with an unbounded height. It used
     * to clamp them to the row, which meant an interaction box taller than the row was
     * silently cut back to it — the actual defect here, and not the numbers.
     *
     * Derived rather than declared, so there is one definition of the floor and no
     * table entry anybody can edit it out of. A screen that asks the frame cannot ask
     * the wrong device: the D-pad floor arrives with the television's numbers and the
     * thumb's floor with everybody else's.
     */
    val touchTarget: Dp get() = Sizing.minTarget(type == FrameType.Television)

    companion object {
        /** 960×540dp: what a 1080p set reports, which is the device and not an idea of it. */
        val Television = CastivioFrame(
            type = FrameType.Television,
            edge = 46.dp, stageTop = 24.dp, stageBottom = 22.dp,
            header = 54.dp, headGap = 26.dp, brand = 40.dp, chip = 44.dp, chipPad = 11.dp,
            bandTop = 22.dp, radius = 20.dp,
            fsTitle = 26.dp, fsLabel = 15.8.dp, fsBody = 13.5.dp, fsChip = 13.dp,
        )

        /** 1280×800dp, with a 1000×500 content block and the rest given to margin. */
        val Tablet = CastivioFrame(
            type = FrameType.Tablet,
            edge = 140.dp, stageTop = 160.dp, stageBottom = 140.dp,
            header = 52.dp, headGap = 26.dp, brand = 34.dp, chip = 40.dp, chipPad = 12.dp,
            bandTop = 24.dp, radius = 20.dp,
            fsTitle = 22.dp, fsLabel = 15.dp, fsBody = 13.dp, fsChip = 12.5.dp,
        )

        /** 873×393dp: an ordinary handset turned sideways. */
        val Phone = CastivioFrame(
            type = FrameType.Phone,
            edge = 32.dp, stageTop = 15.dp, stageBottom = 11.dp,
            header = 42.dp, headGap = 24.dp, brand = 31.dp, chip = 36.dp, chipPad = 12.dp,
            bandTop = 10.dp, radius = 17.dp,
            fsTitle = 20.dp, fsLabel = 14.7.dp, fsBody = 13.dp, fsChip = 12.dp,
        )

        /** 800×360dp: the shortest frame this project ships to. */
        val ShortPhone = CastivioFrame(
            type = FrameType.ShortPhone,
            edge = 26.dp, stageTop = 11.dp, stageBottom = 8.dp,
            header = 36.dp, headGap = 20.dp, brand = 28.dp, chip = 34.dp, chipPad = 11.dp,
            bandTop = 8.dp, radius = 16.dp,
            fsTitle = 19.dp, fsLabel = 14.1.dp, fsBody = 12.5.dp, fsChip = 11.5.dp,
        )
    }
}

/**
 * Which of the four a screen is on.
 *
 * It exists so that the *choice* has a name and can be asserted on, not so that
 * screens can branch on it. A screen that writes `when (frame.type)` has written
 * a second frame table with different numbers in it, which is the thing this file
 * was created to end. The one legitimate reader is [CastivioFrame.touchTarget], and the
 * tests, which iterate the four.
 */
enum class FrameType { Television, Tablet, Phone, ShortPhone }

/** At and above this height the frame is a tablet, not a large phone. */
val TABLET_FRAME: Dp = 600.dp

/** Below this height the frame is the shortest one drawn. */
val SHORT_FRAME: Dp = 380.dp

/**
 * The frame for the height a screen was actually handed.
 *
 * `available` is the measured height of the surface, not the window's and not the
 * display's: a screen that reads the window measures a frame it is never given, which
 * is how the 873×393 handset once drew the 800×360 numbers.
 */
fun castivioFrame(tv: Boolean, available: Dp): CastivioFrame = when {
    tv -> CastivioFrame.Television
    available >= TABLET_FRAME -> CastivioFrame.Tablet
    available < SHORT_FRAME -> CastivioFrame.ShortPhone
    else -> CastivioFrame.Phone
}

/**
 * The frame for the surface this composable was handed, asking the device itself
 * whether it is a television.
 *
 * The height must come from a `BoxWithConstraints` around the screen's own
 * content — `maxHeight`, the surface — and not from `LocalConfiguration`, which
 * describes a window this screen may only have part of. Reading the window is how
 * the 873×393 handset once drew the 800×360 table.
 *
 * ```
 * BoxWithConstraints(Modifier.fillMaxSize()) {
 *     val frame = rememberFrame(maxHeight)
 *     Column(Modifier.padding(horizontal = frame.edge, …)) { … }
 * }
 * ```
 */
@Composable
@ReadOnlyComposable
fun rememberFrame(
    available: Dp,
    isTv: Boolean = CastivioTheme.device.isTv,
): CastivioFrame = castivioFrame(isTv, available)
