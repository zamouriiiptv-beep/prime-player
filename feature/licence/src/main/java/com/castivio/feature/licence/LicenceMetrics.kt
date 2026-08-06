package com.castivio.feature.licence

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.castivio.core.design.theme.CastivioType

/**
 * The approved screen's numbers, per frame, transcribed from the mockup.
 *
 * ## Why a table and not a spacing scale
 *
 * `design/mockups/licence.html` states a different value for nearly every gap on
 * each of the three frames. Approximating all three with generic tokens is what
 * the sibling screen did first: close enough to look right on the reference
 * frame, and not on the shortest one. So the drawing's values are transcribed.
 *
 * ## What the numbers have to clear
 *
 * The heights are the **whole display**. `:app` is edge-to-edge and this screen
 * runs immersive, so it is given every dp — which is also why the mockup's own
 * status bar and gesture bar are overlays there rather than flex children, and
 * why the bands below match the sibling's exactly.
 *
 * | frame | band | column | spare | with a 24dp bar |
 * |---|---|---|---|---|
 * | 873×393 | 284dp | 249dp | 35dp | 11dp |
 * | 800×360 | 264dp | 229dp | 35dp | 11dp |
 * | TV 960×540 | 337dp | 302dp | 35dp | — ¹ |
 *
 * ¹ A television has no system bars; `safeDrawing` is zero there.
 *
 * The last column is the one that matters. This screen runs immersive, but
 * `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE` means a swipe brings the navigation
 * bar back for a few seconds, `safeDrawing` padding appears, and the band loses
 * 24dp. A `Column` that no longer fits does not clip or scroll — it hands
 * **zero** height to whatever it measured last. So the budget is written against
 * the bar being there, and `LicenceBudgetTest` asserts it.
 *
 * ## The typography exception that is no longer here
 *
 * For one commit [priceStyle] stepped down a token on the 800×360 frame, because
 * the column stood 16dp proud of its band and eight of the sixteen were that
 * line's leading. It was defensible — the title, the address and the device key
 * all step per frame — and it was still an exception, and the arithmetic did not
 * require it.
 *
 * Four dp of card padding and six of outer margin pay for the same eight, and
 * they are the thing §10 of the specification already sanctions for this frame:
 * *tighter card padding, same structure*. The price is now `headlineLarge` on
 * both phones, `displayMedium` on the television, and the three frames have the
 * **same** 35dp of margin — which is a better answer than the one that worked,
 * because a uniform number is one somebody can check at a glance.
 *
 * The ordering of the column is load-bearing and is the last line of defence.
 * Children are measured capsules → plans → status line, so if a frame is ever
 * squeezed past its budget the thing that loses height is the reserved sentence
 * and not a control. That is a designed property, not a happy accident.
 */
internal data class LicenceMetrics(
    val edge: Dp,
    val stageTop: Dp,
    val stageBottom: Dp,
    val headBottom: Dp,
    /** Between the identity column and the code. */
    val zoneGap: Dp,
    /** Between the two capsules. */
    val rowGap: Dp,
    val capsuleStart: Dp,
    /** The pill's height: 52 on a phone, 64 on a television. See [target]. */
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
     * The minimum a control may be on this frame.
     *
     * 48 on a phone, 56 on a television, and the capsule grows to hold it rather
     * than the target shrinking to fit the capsule. That inversion is a defect
     * this project has now shipped twice.
     */
    val target: Dp,
)

/**
 * Which frame this is, decided by the height the screen actually has.
 *
 * Height rather than width or a device class, for the reason the sibling gives:
 * height is the dimension that runs out, and `DeviceClass` would call 800dp
 * "Medium" and 873dp "Expanded", which is a fact about width.
 */
internal val SHORT_FRAME = 380.dp

internal fun licenceMetricsFor(tv: Boolean, available: Dp): LicenceMetrics = when {
    tv -> LicenceMetrics(
        edge = 48.dp, stageTop = 48.dp, stageBottom = 48.dp, headBottom = 16.dp,
        zoneGap = 52.dp, rowGap = 14.dp, capsuleStart = 24.dp, capsule = 64.dp, copyGap = 22.dp,
        plansTop = 24.dp, plansGap = 20.dp,
        planMinHeight = 96.dp, planPaddingH = 24.dp, planPaddingV = 12.dp,
        priceStyle = CastivioType.displayMedium,
        statusTop = 16.dp, statusHeight = 24.dp, expiryTop = 3.dp,
        footTop = 13.dp, footBottom = 0.dp,
        plate = 208.dp, platePadding = 12.dp, captionTop = 15.dp, captionWidth = 236.dp,
        target = 56.dp,
    )
    available < SHORT_FRAME -> LicenceMetrics(
        edge = 26.dp, stageTop = 8.dp, stageBottom = 2.dp, headBottom = 9.dp,
        zoneGap = 34.dp, rowGap = 11.dp, capsuleStart = 18.dp, capsule = 52.dp, copyGap = 14.dp,
        plansTop = 12.dp, plansGap = 12.dp,
        planMinHeight = 74.dp, planPaddingH = 16.dp, planPaddingV = 8.dp,
        // The same token as the reference phone. See the note on the class for
        // why this was a step down for one commit and is not any more.
        priceStyle = CastivioType.headlineLarge,
        statusTop = 8.dp, statusHeight = 20.dp, expiryTop = 1.dp,
        footTop = 6.dp, footBottom = 1.dp,
        plate = 138.dp, platePadding = 8.dp, captionTop = 9.dp, captionWidth = 162.dp,
        target = 48.dp,
    )
    else -> LicenceMetrics(
        edge = 30.dp, stageTop = 12.dp, stageBottom = 6.dp, headBottom = 11.dp,
        zoneGap = 40.dp, rowGap = 13.dp, capsuleStart = 20.dp, capsule = 52.dp, copyGap = 16.dp,
        plansTop = 22.dp, plansGap = 14.dp,
        planMinHeight = 76.dp, planPaddingH = 18.dp, planPaddingV = 10.dp,
        priceStyle = CastivioType.headlineLarge,
        statusTop = 12.dp, statusHeight = 20.dp, expiryTop = 2.dp,
        footTop = 8.dp, footBottom = 2.dp,
        plate = 157.dp, platePadding = 9.dp, captionTop = 11.dp, captionWidth = 180.dp,
        target = 48.dp,
    )
}

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
 * @param frame the whole display, minus whatever insets are actually applied.
 * @param title the title's declared line height. The header is the taller of the
 *   title and the language control, not their sum.
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
    frame: Dp,
    title: Dp,
    legal: Dp,
    legalLines: Int = 1,
): Dp =
    frame - stageTop - stageBottom -
        (maxOf(title, target) + headBottom) - // header
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
