package com.castivio.feature.licence

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.castivio.core.design.theme.CastivioMetrics
import com.castivio.core.design.theme.CastivioType
import com.castivio.core.design.theme.Sizing
import com.castivio.core.design.theme.boundedFraction
import com.castivio.core.design.theme.castivioMetrics

/**
 * What the licence screen is drawn from: the shared metrics, plus the sizes only an
 * activation-and-billing screen has — two capsules, two plan cards, a QR plate and a
 * reserved status line.
 *
 * ## It was a table of four devices, and now it is arithmetic
 *
 * `design/mockups/licence.html` is still the record and the drawing is unchanged. What
 * changed is how its numbers reach a device. There used to be four rows here —
 * television, tablet, phone, short phone — each stating a value for nearly every gap,
 * chosen by a height threshold. That is as many drawings as somebody had made, and a
 * surface between two of them took whichever row it fell into.
 *
 * Every size below is now a share of the axis it spends, clamped at both ends, through
 * the one expression the whole product uses:
 * [com.castivio.core.design.theme.boundedFraction]. The shares are read off the
 * 1280×720 reference, which is the 960×540 television drawing at 4/3 — so the
 * television reproduces its approved numbers **exactly**: zoneGap 52, capsule 64,
 * plate 208, plan 96, price 36, caption 236.
 *
 * ## What the numbers still have to clear
 *
 * The heights are the **whole display**. `:app` is edge-to-edge and this screen runs
 * immersive, so it is given every dp — which is also why the mockup's own status bar
 * and gesture bar are overlays there rather than flex children.
 *
 * `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE` means a swipe brings the navigation bar back
 * for a few seconds, `safeDrawing` padding appears, and the band loses 24dp. A `Column`
 * that no longer fits does not clip or scroll — it hands **zero** height to whatever it
 * measured last. So the budget is written against the bar being there, and
 * `LicenceBudgetTest` asserts it across the whole range of surfaces rather than at four
 * points in it.
 *
 * The ordering of the column is load-bearing and is the last line of defence. Children
 * are measured capsules → plans → status line, so if a surface is ever squeezed past
 * its budget the thing that loses height is the reserved sentence and not a control.
 * That is a designed property, not a happy accident, and `LicenceLayoutTest` proves the
 * ordering.
 *
 * ## The price is a size now, not a token
 *
 * It was `displayMedium` on a television and `headlineLarge` everywhere else — a
 * two-row type table, and the last device branch on this screen. It is a bounded share
 * of the height built on `headlineLarge`'s own face and leading, so the television
 * still sets 36sp and the relationship the drawing has — the price is the largest thing
 * on the screen, about 1.38× the screen's own title — holds at every size rather than
 * at two.
 */
internal data class LicenceMetrics(
    /**
     * The stage, the header and the four type steps — from [CastivioMetrics], the one
     * system every screen in Castivio reads.
     *
     * This screen used to hold its own copy of five of them: `edge`, `stageTop`,
     * `stageBottom`, `headBottom` and a `target` beside them, on three frames, behind
     * a `SHORT_FRAME` constant of its own that happened to equal the shared one. Two
     * tables that agree because somebody typed the same number twice agree until one
     * of the two is edited.
     */
    val frame: CastivioMetrics,
    /** Between the identity column and the code. */
    val zoneGap: Dp,
    /** Between the two capsules. */
    val rowGap: Dp,
    val capsuleStart: Dp,
    /**
     * The pill's drawn height — 64 on a television, and never below [target].
     *
     * The capsule grows to hold the target rather than the target shrinking to fit the
     * capsule. That inversion is a defect this project has shipped twice.
     */
    val capsule: Dp,
    /** Between a capsule's label, value and control. */
    val copyGap: Dp,
    /** From the lower capsule to the plans. */
    val plansTop: Dp,
    /** Between the two plan cards. */
    val plansGap: Dp,
    val planMinHeight: Dp,
    val planPaddingH: Dp,
    val planPaddingV: Dp,
    val priceStyle: TextStyle,
    /**
     * The address and the device key, at the size this surface earns them.
     *
     * They were `codeHero` or `codeCompact` and `codeKeyTv` or `codeKey`, chosen by
     * `if (tv)` inside the screen — four tokens that differ from their pair only in
     * size and, for the key, in the tracking that goes with a size. That is a device
     * table for type, in the one place on this screen where a table is least
     * defensible: the address is the string a user reads aloud to somebody else, and
     * how large it needs to be is a question about the surface and the distance, not
     * about what the box calls itself.
     *
     * The **face** is not responsive and is not a table either: both members of each
     * pair are the same monospace at the same weight, so there is nothing to choose.
     */
    val addressStyle: TextStyle,
    val keyStyle: TextStyle,
    val statusTop: Dp,
    val statusHeight: Dp,
    /**
     * From the status line to the expiry date under it.
     *
     * Deliberately tight — one to three dp — because the two lines are one piece
     * of information, not two. "Your licence is active" and "Valid until 21
     * February 2027" answer the same question, and a gap wide enough to read as
     * a separation makes the reader look for what the second line belongs to.
     * The status line's own reserved height already supplies the optical space.
     *
     * Composed only for an active annual licence, which is a state with no plan
     * cards, so it is spent out of room the card row would otherwise have had.
     * It is not in `columnHeight`: adding it there would reserve height on every
     * frame for a line that eight of the nine states never draw.
     */
    val expiryTop: Dp,
    val footTop: Dp,
    val footBottom: Dp,
    val plate: Dp,
    val platePadding: Dp,
    val captionTop: Dp,
    val captionWidth: Dp,
    /**
     * The smallest box that may receive a press or a D-pad landing.
     *
     * 48 for a thumb, 56 for a remote, and the one value on this type that asks what
     * kind of device this is — the functional adaptation the design system permits.
     * Everything else here is a share of the surface.
     */
    val target: Dp,
) {
    /* The frame's numbers, reachable as this screen's own. */
    val edge get() = frame.edge
    val stageTop get() = frame.stageTop
    val stageBottom get() = frame.stageBottom
    val header get() = frame.header
    val headGap get() = frame.headGap
    val brand get() = frame.brand
    val chip get() = frame.chip
    val chipPad get() = frame.chipPad
    val headBottom get() = frame.bandTop
    val radius get() = frame.radius
    val fsTitle get() = frame.fsTitle
    val fsLabel get() = frame.fsLabel
    val fsBody get() = frame.fsBody
    val fsChip get() = frame.fsChip
}

/**
 * The screen's numbers for a measured surface.
 *
 * `width` and `height` are what a `BoxWithConstraints` around the screen's own content
 * reports — the surface, not the window and not the display. Horizontal things are read
 * off the width and vertical things off the height, because those are the axes they
 * actually spend: the gap between the two zones and a capsule's inner padding come off
 * one, and everything stacked down the stage off the other.
 *
 * There is no threshold here and nothing in this function could express one. It used to
 * be four branches chosen by height, with `TABLET_FRAME` and `SHORT_FRAME` as the
 * cut-offs; a 1280×800 tablet drew the reference phone's numbers until a late fix gave
 * it a row, and every surface outside the four was a guess nobody had looked at.
 */
internal fun licenceMetricsFor(tv: Boolean, width: Dp, height: Dp): LicenceMetrics {
    val target = Sizing.minTarget(tv)
    val price = height.boundedFraction(PRICE, 26.dp, 40.dp)
    return LicenceMetrics(
        frame = castivioMetrics(width, height, tv),
        zoneGap = width.boundedFraction(ZONE_GAP, 30.dp, 72.dp),
        rowGap = height.boundedFraction(ROW_GAP, 10.dp, 20.dp),
        capsuleStart = width.boundedFraction(CAPSULE_START, 16.dp, 32.dp),
        capsule = maxOf(height.boundedFraction(CAPSULE, 48.dp, 72.dp), target),
        copyGap = width.boundedFraction(COPY_GAP, 12.dp, 30.dp),
        plansTop = height.boundedFraction(PLANS_TOP, 10.dp, 32.dp),
        plansGap = width.boundedFraction(PLANS_GAP, 11.dp, 28.dp),
        // A plan card is the button on this screen, so its floor is a control's.
        planMinHeight = maxOf(height.boundedFraction(PLAN_MIN, 72.dp, 108.dp), target),
        planPaddingH = width.boundedFraction(PLAN_PAD_H, 14.dp, 32.dp),
        planPaddingV = height.boundedFraction(PLAN_PAD_V, 8.dp, 16.dp),
        priceStyle = priceStyle(price),
        addressStyle = addressStyle(height.boundedFraction(ADDRESS, 26.dp, 44.dp)),
        keyStyle = keyStyle(height.boundedFraction(KEY, 20.dp, 34.dp)),
        statusTop = height.boundedFraction(STATUS_TOP, 8.dp, 20.dp),
        statusHeight = height.boundedFraction(STATUS_HEIGHT, 20.dp, 28.dp),
        expiryTop = height.boundedFraction(EXPIRY_TOP, 1.dp, 5.dp),
        footTop = height.boundedFraction(FOOT_TOP, 6.dp, 18.dp),
        footBottom = height.boundedFraction(FOOT_BOTTOM, 0.dp, 4.dp),
        plate = height.boundedFraction(PLATE, 130.dp, 230.dp),
        platePadding = height.boundedFraction(PLATE_PAD, 7.dp, 16.dp),
        captionTop = height.boundedFraction(CAPTION_TOP, 8.dp, 20.dp),
        captionWidth = width.boundedFraction(CAPTION_WIDTH, 155.dp, 330.dp),
        target = target,
    )
}

/**
 * The price, at the size this surface earns it.
 *
 * Built from `headlineLarge` rather than declared, so the face, the weight and the
 * tracking are the design system's decision and only the size is the surface's — and
 * the leading is `headlineLarge`'s own ratio, which is what keeps [planHeight]
 * answering with the same arithmetic it did when the style was a token.
 *
 * The television lands on 36sp, which is what `displayMedium` set there, so the
 * approved drawing is reproduced rather than approximated.
 */
private fun priceStyle(size: Dp): TextStyle = CastivioType.headlineLarge.copy(
    fontSize = size.value.sp,
    lineHeight = (size.value * PRICE_LEADING).sp,
)

/**
 * The address, built from the shipped token rather than declared here.
 *
 * The family, the weight and the tracking are the design system's decision and only the
 * size is the surface's. The television lands on 42sp — `codeHero` — and the shortest
 * phone on 28, which is `codeCompact`, so both drawings are reproduced rather than
 * approximated.
 */
private fun addressStyle(size: Dp): TextStyle = CastivioType.codeHero.copy(
    fontSize = size.value.sp,
    lineHeight = (size.value * CODE_LEADING).sp,
)

/**
 * The device key, which is the one of the two whose tracking is part of its size.
 *
 * `codeKey` sets 24sp at 2.5 and `codeKeyTv` 32 at 3.5 — the same ratio twice, because
 * six groups of hexadecimal need proportionally more air between them as they grow. So
 * the tracking is that ratio rather than a third number, and the television still lands
 * on 32sp at 3.5.
 */
private fun keyStyle(size: Dp): TextStyle = CastivioType.codeKeyTv.copy(
    fontSize = size.value.sp,
    lineHeight = (size.value * KEY_LEADING).sp,
    letterSpacing = (size.value * KEY_TRACKING).sp,
)

/* ------------------------------------------------------------------ the shares
 *
 * Read off the 1280×720 reference, which is the 960×540 television drawing at 4/3 —
 * so the television reproduces its approved numbers exactly. Nothing here was chosen
 * twice: each is one drawing's value divided by the axis it was drawn on.
 */

private const val ZONE_GAP = 69.3f / 1280f
private const val ROW_GAP = 18.7f / 720f
private const val CAPSULE_START = 32f / 1280f
private const val CAPSULE = 85.3f / 720f
private const val COPY_GAP = 29.3f / 1280f
private const val PLANS_TOP = 32f / 720f
private const val PLANS_GAP = 26.7f / 1280f
private const val PLAN_MIN = 128f / 720f
private const val PLAN_PAD_H = 32f / 1280f
private const val PLAN_PAD_V = 16f / 720f
private const val PRICE = 48f / 720f
private const val ADDRESS = 56f / 720f
private const val KEY = 42.7f / 720f
private const val STATUS_TOP = 21.3f / 720f
private const val STATUS_HEIGHT = 32f / 720f
private const val EXPIRY_TOP = 4f / 720f
private const val FOOT_TOP = 17.3f / 720f
private const val FOOT_BOTTOM = 2.7f / 720f
private const val PLATE = 277.3f / 720f
private const val PLATE_PAD = 16f / 720f
private const val CAPTION_TOP = 20f / 720f
private const val CAPTION_WIDTH = 314.7f / 1280f

/** `headlineLarge`'s own leading, kept so the price's line box scales with its size. */
private const val PRICE_LEADING = 40f / 28f

/** `codeHero`'s and `codeKeyTv`'s own leadings, and the key's own tracking ratio. */
private const val CODE_LEADING = 52f / 42f
private const val KEY_LEADING = 42f / 32f
private const val KEY_TRACKING = 3.5f / 32f

/**
 * How much of the frame is left for the middle band.
 *
 * ## Why this is arithmetic and not a measurement
 *
 * It should be a measurement. It cannot be one on the JVM, because the harness
 * that runs Compose without a device does not lay text out: under Robolectric
 * every `Text` measures 35dp whatever its style — the headline, the legal line
 * and the overline all identical — and native graphics does not change it. That
 * inflates a column by about 40dp, which is more than this design's margin, so a
 * runtime assertion about fit would be an assertion about the harness.
 *
 * So the fit is checked where the numbers are real: from the [LicenceMetrics] the
 * screen is built from and the line heights `CastivioType` declares.
 * `LicenceLayoutTest` still asserts that Compose *places* all of it, and this
 * says that the places it puts them add up.
 *
 * @param display the whole display, minus whatever insets are actually applied.
 * @param legal the legal line's declared line height, for one line.
 * @param legalLines how many lines the footer takes. **Not always one.** The
 *   footer's sentence is short enough for one line in English and is not
 *   guaranteed to be in German or Finnish, so the footer is a parameter rather
 *   than an assumption and `LicenceBudgetTest` spends it on two.
 *
 *   This is also the number that decided the footer's design. The complete legal
 *   notice measures four lines at `bodySmall` on every frame Castivio ships to —
 *   90dp on 873×393, 87dp on 800×360, 93dp on the television, measured by
 *   `measure.js` against `design/mockups/licence.html`. Against a 20dp slot and
 *   35dp of whole-band margin that is 27dp more than the screen has, before the
 *   transient navigation bar takes another 24. So the footer carries the
 *   notice's operative first clause and the notice itself opens over the screen.
 *   See `LegalFooter`.
 */
internal fun LicenceMetrics.bandHeight(
    display: Dp,
    legal: Dp,
    legalLines: Int = 1,
): Dp =
    display - stageTop - stageBottom -
        (header + headBottom) - // the header is a declared height now, not a measured one
        HAIRLINES -
        (footTop + legal * legalLines + footBottom) // footer

/**
 * What a plan card needs, from the same numbers that build it.
 *
 * The name, the price row, and the padding. The period sits on the price's
 * baseline rather than under it, so the row is the price's line box and the
 * period does not add to it — which is what keeps a two-line card two lines in
 * every one of the 37 languages.
 *
 * The mockup measures two dp more, because a CSS border adds to a box and a
 * Compose `border` is a draw modifier that adds nothing. The drawing is
 * therefore fractionally pessimistic, which is the safe direction for a budget.
 *
 * @param name the declared line height of the plan name at `overline`.
 */
internal fun LicenceMetrics.planHeight(name: Dp): Dp =
    maxOf(planMinHeight, planPaddingV * 2 + name + priceStyle.lineHeight.value.dp)

/**
 * What the identity column needs, child for child.
 *
 * Mirrors `IdentityColumn`: two capsules, the plans, and the reserved status
 * line. None of the four is text-driven — a capsule is a declared height, a card
 * is a floor and two declared line heights, the status line is reserved — which
 * is why this arithmetic is trustworthy where a Robolectric measurement is not.
 */
internal fun LicenceMetrics.columnHeight(name: Dp): Dp =
    capsule + rowGap + capsule + plansTop + planHeight(name) + statusTop + statusHeight

/**
 * What the code side of the band needs: the plate, the gap, and the caption.
 *
 * The identity column is the taller of the two on every frame today. That is a
 * fact about the current numbers and not a law, and a gate that measured only
 * the column would go on passing while the code zone quietly overran.
 *
 * @param captionLines **three**, and measured rather than reasoned. This was two,
 *   on the argument that the caption wraps in most of the 37 languages and one
 *   line would be budgeting for English — right as far as it went, and still a
 *   guess. The mockup was asked: the caption takes three lines on both phone
 *   frames, German on each, and two on the television. Two was the same class of
 *   mistake in a smaller size.
 *
 *   Re-measured when the caption shortened to *Scan the QR code to activate this
 *   device.* — the worst case is still three, so the number stands. Worth
 *   restating that it was checked: a budget nobody re-measures after the text
 *   changes is a number that used to be true.
 */
internal fun LicenceMetrics.codeHeight(caption: Dp, captionLines: Int = 3): Dp =
    plate + captionTop + caption * captionLines

/** The two full-bleed rules that bracket the field band, at a pixel each. */
private val HAIRLINES = 2.dp
